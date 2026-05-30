/**
 * llama_jni.cpp — Project.EXE
 * JNI bridge — updated for current llama.cpp master API.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>

#include "llama.h"
#include "common.h"

#define TAG    "ProjectEXE_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model   * g_model   = nullptr;
static llama_context * g_ctx     = nullptr;
static llama_sampler * g_sampler = nullptr;
static std::atomic<bool> g_abort{false};

static JavaVM     * g_jvm             = nullptr;
static jclass       g_callback_class  = nullptr;
static jmethodID    g_callback_method = nullptr;

static std::string jstring_to_std(JNIEnv * env, jstring js) {
    if (!js) return "";
    const char * cs = env->GetStringUTFChars(js, nullptr);
    std::string result(cs);
    env->ReleaseStringUTFChars(js, cs);
    return result;
}

JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void *) {
    g_jvm = vm;
    JNIEnv * env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;
    jclass local = env->FindClass("com/projectexe/engine/TokenCallback");
    if (!local) { LOGE("TokenCallback class not found"); return JNI_ERR; }
    g_callback_class  = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    g_callback_method = env->GetMethodID(g_callback_class, "onToken", "(Ljava/lang/String;)V");
    llama_backend_init();
    LOGI("llama.cpp backend initialized");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM * vm, void *) {
    JNIEnv * env;
    vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (g_callback_class) env->DeleteGlobalRef(g_callback_class);
    llama_backend_free();
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_projectexe_engine_LlamaEngine_loadModel(
    JNIEnv * env, jobject,
    jstring  model_path,
    jint     n_ctx,
    jint     n_threads
) {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }

    std::string path = jstring_to_std(env, model_path);
    LOGI("Loading model: %s", path.c_str());

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) {
        LOGE("Failed to load model: %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx             = static_cast<uint32_t>(n_ctx);
    cp.n_threads         = static_cast<uint32_t>(n_threads);
    cp.n_threads_batch   = static_cast<uint32_t>(n_threads);
    // flash_attn removed from context params in newer llama.cpp — handled internally

    // Use new API: llama_init_from_model
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded. ctx=%d tokens, threads=%d", n_ctx, n_threads);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_projectexe_engine_LlamaEngine_isModelLoaded(JNIEnv *, jobject) {
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_projectexe_engine_LlamaEngine_completion(
    JNIEnv * env, jobject,
    jstring  j_system_prompt,
    jstring  j_user_content,
    jfloat   temperature,
    jfloat   top_p,
    jint     max_tokens,
    jfloat   repeat_penalty,
    jobject  j_callback
) {
    if (!g_model || !g_ctx) {
        LOGE("completion() called with no model loaded");
        return env->NewStringUTF("[ERROR: model not loaded]");
    }

    g_abort.store(false);

    std::string sys  = jstring_to_std(env, j_system_prompt);
    std::string user = jstring_to_std(env, j_user_content);

    const llama_vocab * vocab = llama_model_get_vocab(g_model);

    // Build chat messages
    std::vector<llama_chat_message> messages;
    if (!sys.empty())  messages.push_back({"system", sys.c_str()});
    messages.push_back({"user", user.c_str()});

    // Apply chat template — new API: 6 args (no model param, tmpl is null for default)
    std::vector<char> formatted(8192);
    int n_formatted = llama_chat_apply_template(
        nullptr,            // tmpl — null = use model's built-in template
        messages.data(),
        messages.size(),
        true,               // add_ass
        formatted.data(),
        static_cast<int>(formatted.size())
    );
    if (n_formatted < 0) {
        LOGE("Chat template formatting failed");
        return env->NewStringUTF("[ERROR: template formatting failed]");
    }
    if (n_formatted > static_cast<int>(formatted.size())) {
        formatted.resize(n_formatted + 1);
        n_formatted = llama_chat_apply_template(
            nullptr,
            messages.data(), messages.size(),
            true,
            formatted.data(), static_cast<int>(formatted.size())
        );
    }
    std::string prompt(formatted.data(), n_formatted);

    // Tokenize
    auto tokens = common_tokenize(g_ctx, prompt, true, true);

    // Clear KV cache between stages using new API
    llama_kv_cache_clear(g_ctx);

    // Build sampler chain — new penalties API: 4 args (repeat, freq, present, nl)
    if (g_sampler) llama_sampler_free(g_sampler);
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(
        repeat_penalty,   // penalty_repeat
        0.0f,             // penalty_freq
        0.0f,             // penalty_present
        false             // penalize_nl
    ));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Decode prompt tokens
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt tokens");
        return env->NewStringUTF("[ERROR: decode failed]");
    }

    // Generation loop
    std::string output;
    output.reserve(512);
    int n_generated = 0;
    bool has_cb = (j_callback != nullptr);

    while (n_generated < max_tokens && !g_abort.load()) {
        lla
