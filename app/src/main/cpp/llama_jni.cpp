/**
 * llama_jni.cpp — Project.EXE
 * JNI bridge between Kotlin LlamaEngine and llama.cpp native API.
 *
 * Design: One model loaded at a time. Context is reset between pipeline
 * stages rather than creating/destroying the context (much faster on mobile).
 * Streaming tokens are delivered via callback into Kotlin Flow.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <sstream>

#include "llama.h"
#include "common.h"

#define TAG    "ProjectEXE_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Singleton state ───────────────────────────────────────────
static llama_model   * g_model   = nullptr;
static llama_context * g_ctx     = nullptr;
static llama_sampler * g_sampler = nullptr;
static std::atomic<bool> g_abort{false};

// ── JNI class/method cache ────────────────────────────────────
static JavaVM     * g_jvm             = nullptr;
static jclass       g_callback_class  = nullptr;
static jmethodID    g_callback_method = nullptr;  // onToken(String)

// ── Helpers ───────────────────────────────────────────────────
static std::string jstring_to_std(JNIEnv * env, jstring js) {
    if (!js) return "";
    const char * cs = env->GetStringUTFChars(js, nullptr);
    std::string result(cs);
    env->ReleaseStringUTFChars(js, cs);
    return result;
}

// ─────────────────────────────────────────────────────────────
// JNI_OnLoad — cache JavaVM, resolve callback class
// ─────────────────────────────────────────────────────────────
JNIEXPORT jint JNI_OnLoad(JavaVM * vm, void *) {
    g_jvm = vm;
    JNIEnv * env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_ERR;

    // Pre-resolve the Kotlin callback class
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

// ─────────────────────────────────────────────────────────────
// loadModel — loads a GGUF model from an absolute file path
// Returns true on success, false on failure
// ─────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_projectexe_engine_LlamaEngine_loadModel(
    JNIEnv * env, jobject,
    jstring  model_path,
    jint     n_ctx,          // context window (default 4096)
    jint     n_threads       // CPU threads (default 4 for Cortex-A78 big cores)
) {
    // Unload any existing model first
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }

    std::string path = jstring_to_std(env, model_path);
    LOGI("Loading model: %s", path.c_str());

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // CPU-only for Adreno 710 stability

    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) {
        LOGE("Failed to load model: %s", path.c_str());
        return JNI_FALSE;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx      = static_cast<uint32_t>(n_ctx);
    cp.n_threads  = static_cast<uint32_t>(n_threads);
    cp.n_threads_batch = static_cast<uint32_t>(n_threads);
    cp.flash_attn = true;   // Flash attention for memory efficiency

    g_ctx = llama_new_context_with_model(g_model, cp);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded. ctx=%d tokens, threads=%d", n_ctx, n_threads);
    return JNI_TRUE;
}

// ─────────────────────────────────────────────────────────────
// isModelLoaded — quick check from Kotlin
// ─────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_projectexe_engine_LlamaEngine_isModelLoaded(JNIEnv *, jobject) {
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

// ─────────────────────────────────────────────────────────────
// completion — runs inference, streams tokens via TokenCallback
//
// systemPrompt : compiled soul prompt for this pipeline stage
// messages     : JSON array string: [{"role":"user","content":"..."},...]
// temperature  : 0.05 (factual) – 1.2 (creative persona)
// topP         : nucleus sampling
// maxTokens    : generation limit
// repeatPenalty: prevent repetition loops
// callback     : Kotlin TokenCallback object receiving onToken(String)
// ─────────────────────────────────────────────────────────────
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
    jobject  j_callback         // nullable — if null, no streaming
) {
    if (!g_model || !g_ctx) {
        LOGE("completion() called with no model loaded");
        return env->NewStringUTF("[ERROR: model not loaded]");
    }

    g_abort.store(false);

    std::string sys  = jstring_to_std(env, j_system_prompt);
    std::string user = jstring_to_std(env, j_user_content);

    // Build the prompt using the model's chat template
    const llama_vocab * vocab = llama_model_get_vocab(g_model);

    // Format as chat messages
    std::vector<llama_chat_message> messages;
    if (!sys.empty()) {
        messages.push_back({"system", sys.c_str()});
    }
    messages.push_back({"user", user.c_str()});

    // Apply chat template
    std::vector<char> formatted(8192);
    int n_formatted = llama_chat_apply_template(
        g_model, nullptr,
        messages.data(), messages.size(),
        true,  // add generation prompt
        formatted.data(), static_cast<int>(formatted.size())
    );
    if (n_formatted < 0) {
        LOGE("Chat template formatting failed");
        return env->NewStringUTF("[ERROR: template formatting failed]");
    }
    if (n_formatted > static_cast<int>(formatted.size())) {
        formatted.resize(n_formatted + 1);
        n_formatted = llama_chat_apply_template(
            g_model, nullptr,
            messages.data(), messages.size(),
            true,
            formatted.data(), static_cast<int>(formatted.size())
        );
    }
    std::string prompt(formatted.data(), n_formatted);

    // Tokenize
    auto tokens = common_tokenize(g_ctx, prompt, true, true);

    // Reset KV cache and context state between stages
    llama_kv_cache_clear(g_ctx);

    // Setup sampler chain
    if (g_sampler) llama_sampler_free(g_sampler);
    g_sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(
        /*n_vocab*/     0,
        /*special_eos*/ llama_vocab_eos(vocab),
        /*linefeed_id*/ -1,
        /*n_ctx*/       0,
        /*penalty_repeat*/ repeat_penalty,
        /*penalty_freq*/   0.0f,
        /*penalty_present*/0.0f,
        /*penalize_nl*/    false,
        /*ignore_eos*/     false
    ));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Batch decode
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt tokens");
        return env->NewStringUTF("[ERROR: decode failed]");
    }

    // Generation loop
    std::string output;
    output.reserve(512);
    int n_generated = 0;

    // Resolve callback once (avoid per-token JNI overhead of FindClass)
    JNIEnv * cb_env = env;
    bool     has_cb = (j_callback != nullptr);

    while (n_generated < max_tokens && !g_abort.load()) {
        llama_token id = llama_sampler_sample(g_sampler, g_ctx, -1);

        if (llama_vocab_is_eog(vocab, id)) break;

        // Decode token to string
        char piece[256];
        int n = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, false);
        if (n < 0) { piece[0] = '\0'; n = 0; }
        piece[n] = '\0';
        std::string token_str(piece, n);
        output += token_str;

        // Stream to Kotlin callback
        if (has_cb) {
            jstring j_piece = cb_env->NewStringUTF(token_str.c_str());
            cb_env->CallVoidMethod(j_callback, g_callback_method, j_piece);
            cb_env->DeleteLocalRef(j_piece);
            if (cb_env->ExceptionCheck()) {
                cb_env->ExceptionClear();
                break;
            }
        }

        // Continue decoding
        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, next) != 0) break;
        n_generated++;
    }

    LOGI("Generated %d tokens", n_generated);
    return env->NewStringUTF(output.c_str());
}

// ─────────────────────────────────────────────────────────────
// abortGeneration — called from Kotlin to stop mid-stream
// ─────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_projectexe_engine_LlamaEngine_abortGeneration(JNIEnv *, jobject) {
    g_abort.store(true);
    LOGI("Generation aborted by user");
}

// ─────────────────────────────────────────────────────────────
// getContextSize / getTokenCount — diagnostic helpers
// ─────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jint JNICALL
Java_com_projectexe_engine_LlamaEngine_getContextSize(JNIEnv *, jobject) {
    return g_ctx ? static_cast<jint>(llama_n_ctx(g_ctx)) : 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_projectexe_engine_LlamaEngine_getVocabSize(JNIEnv *, jobject) {
    return g_model ? static_cast<jint>(llama_vocab_n_tokens(llama_model_get_vocab(g_model))) : 0;
}

// ─────────────────────────────────────────────────────────────
// getEmbedding — for Node 4 semantic memory (cosine similarity)
// Returns float array of the last token's embedding
// ─────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_projectexe_engine_LlamaEngine_getEmbedding(
    JNIEnv * env, jobject,
    jstring  j_text
) {
    if (!g_model || !g_ctx) return env->NewFloatArray(0);

    std::string text = jstring_to_std(env, j_text);
    auto tokens = common_tokenize(g_ctx, text, true, true);

    llama_kv_cache_clear(g_ctx);
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_ctx, batch) != 0) return env->NewFloatArray(0);

    // Get embedding from last token position
    int n_embd = llama_model_n_embd(g_model);
    float * embd = llama_get_embeddings_seq(g_ctx, 0);
    if (!embd) embd = llama_get_embeddings_ith(g_ctx, static_cast<int32_t>(tokens.size()) - 1);
    if (!embd) return env->NewFloatArray(0);

    jfloatArray result = env->NewFloatArray(n_embd);
    env->SetFloatArrayRegion(result, 0, n_embd, embd);
    return result;
}

// ─────────────────────────────────────────────────────────────
// freeModel — explicit release (also called on new loadModel)
// ─────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT void JNICALL
Java_com_projectexe_engine_LlamaEngine_freeModel(JNIEnv *, jobject) {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model   = nullptr; }
    LOGI("Model freed");
}
