#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include "llama.h"
#include "common.h"

#define TAG "Entity_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_sampler = nullptr;
static std::atomic<bool> g_abort{false};
static JavaVM* g_jvm = nullptr;
static jclass g_callback_class = nullptr;
static jmethodID g_callback_method = nullptr;

static std::string jstring_to_std(JNIEnv* env, jstring js) {
    if (!js) return "";
    const char* cs = env->GetStringUTFChars(js, nullptr);
    std::string result(cs);
    env->ReleaseStringUTFChars(js, cs);
    return result;
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_jvm = vm;
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass local = env->FindClass("com/projectexe/engine/TokenCallback");
    g_callback_class = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    g_callback_method = env->GetMethodID(g_callback_class, "onToken", "(Ljava/lang/String;)V");
    llama_backend_init();
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_projectexe_engine_LlamaEngine_loadModel(JNIEnv* env, jobject, jstring model_path, jint n_ctx, jint n_threads) {
    std::string path = jstring_to_std(env, model_path);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) return JNI_FALSE;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx;
    cp.n_threads = n_threads;
    g_ctx = llama_init_from_model(g_model, cp);
    return g_ctx ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_projectexe_engine_LlamaEngine_isModelLoaded(JNIEnv*, jobject) {
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL Java_com_projectexe_engine_LlamaEngine_completion(
    JNIEnv* env, jobject, jstring j_system, jstring j_user, jfloat temp, jfloat top_p, jint max_tokens, jfloat repeat_penalty, jobject j_cb) 
{
    g_abort.store(false);
    std::string sys = jstring_to_std(env, j_system);
    std::string user = jstring_to_std(env, j_user);

    std::vector<llama_chat_message> msgs;
    if (!sys.empty()) msgs.push_back({"system", sys.c_str()});
    msgs.push_back({"user", user.c_str()});

    std::vector<char> fmt(8192);
    int n_fmt = llama_chat_apply_template(nullptr, msgs.data(), msgs.size(), true, fmt.data(), fmt.size());
    std::string prompt(fmt.data(), n_fmt);

    auto tokens = common_tokenize(g_ctx, prompt, true, true);
    llama_kv_cache_clear(g_ctx);

    if (g_sampler) llama_sampler_free(g_sampler);
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(repeat_penalty, 0.0f, 0.0f, false));

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    llama_decode(g_ctx, batch);

    std::string output;
    int n_generated = 0;
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    while (n_generated < max_tokens && !g_abort.load()) {
        llama_token id = llama_sampler_sample(g_sampler, g_ctx, -1);
        if (id == llama_token_eos(g_model)) break;

        char buf[256];
        int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        
        std::string piece(buf, n);
        output += piece;

        if (j_cb) {
            jstring js = env->NewStringUTF(piece.c_str());
            env->CallVoidMethod(j_cb, g_callback_method, js);
            env->DeleteLocalRef(js);
        }

        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, next)) break;
        n_generated++;
    }
    return env->NewStringUTF(output.c_str());
}
