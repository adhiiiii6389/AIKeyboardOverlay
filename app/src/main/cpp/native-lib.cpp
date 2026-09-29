#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaEngineNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaSession {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_aikeyboardoverlay_llm_LlamaEngine_nativeLoadModel(
        JNIEnv* env, jobject /* thiz */,
        jstring modelPath, jint nThreads, jint nCtx) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading model from: %s", path);

    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;

    llama_model* model = llama_load_model_from_file(path, modelParams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (model == nullptr) {
        LOGE("llama_load_model_from_file returned null");
        return 0L;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = nCtx > 0 ? nCtx : 2048;
    ctxParams.n_threads = nThreads > 0 ? nThreads : 4;
    ctxParams.n_threads_batch = ctxParams.n_threads;
    ctxParams.n_batch = ctxParams.n_ctx;

    llama_context* ctx = llama_new_context_with_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("llama_new_context_with_model failed");
        llama_free_model(model);
        return 0L;
    }

    auto* session = new LlamaSession{model, ctx};
    LOGI("Model loaded successfully, handle=%p", session);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_aikeyboardoverlay_llm_LlamaEngine_nativeGenerate(
        JNIEnv* env, jobject /* thiz */,
        jlong handle, jstring prompt, jint maxTokens) {

    auto* session = reinterpret_cast<LlamaSession*>(handle);
    if (session == nullptr || session->ctx == nullptr) {
        LOGE("nativeGenerate called with invalid handle");
        return env->NewStringUTF("");
    }

    const char* promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string promptStr(promptChars);
    env->ReleaseStringUTFChars(prompt, promptChars);

    llama_model* model = session->model;
    llama_context* ctx = session->ctx;

    std::vector<llama_token> tokens(promptStr.length() + 16);
    int nTokens = llama_tokenize(
            model, promptStr.c_str(), (int32_t) promptStr.length(),
            tokens.data(), (int32_t) tokens.size(), true, true);

    if (nTokens < 0) {
        tokens.resize(-nTokens);
        nTokens = llama_tokenize(
                model, promptStr.c_str(), (int32_t) promptStr.length(),
                tokens.data(), (int32_t) tokens.size(), true, true);
    }
    if (nTokens <= 0 || nTokens > (int) llama_n_ctx(ctx)) {
        LOGE("Invalid prompt token count: %d (context: %u)",
             nTokens, llama_n_ctx(ctx));
        return env->NewStringUTF("");
    }
    tokens.resize(nTokens);

    const int batchCapacity = nTokens > 512 ? nTokens : 512;
    llama_batch batch = llama_batch_init(batchCapacity, 0, 1);
    if (batch.token == nullptr) {
        LOGE("Failed to allocate llama batch with capacity %d", batchCapacity);
        return env->NewStringUTF("");
    }

    for (int i = 0; i < nTokens; i++) {
        batch.token[batch.n_tokens] = tokens[i];
        batch.pos[batch.n_tokens] = i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens][0] = 0;
        batch.logits[batch.n_tokens] = (i == nTokens - 1);
        batch.n_tokens++;
    }

    if (llama_decode(ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt prefill");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    std::string result;
    int nCur = nTokens;
    const int nVocab = llama_n_vocab(model);

    const int remainingContext = (int) llama_n_ctx(ctx) - nTokens;
    const int tokensToGenerate = maxTokens < remainingContext ? maxTokens : remainingContext;
    for (int i = 0; i < tokensToGenerate; i++) {
        float* logits = llama_get_logits_ith(ctx, batch.n_tokens - 1);

        llama_token bestToken = 0;
        float bestLogit = logits[0];
        for (int t = 1; t < nVocab; t++) {
            if (logits[t] > bestLogit) {
                bestLogit = logits[t];
                bestToken = t;
            }
        }

        if (llama_token_is_eog(model, bestToken)) {
            break;
        }

        char piece[256];
        int pieceLen = llama_token_to_piece(model, bestToken, piece, sizeof(piece), 0, true);
        if (pieceLen > 0) {
            result.append(piece, pieceLen);
        }

        batch.n_tokens = 1;
        batch.token[0] = bestToken;
        batch.pos[0] = nCur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;

        if (llama_decode(ctx, batch) != 0) {
            LOGE("llama_decode failed during generation");
            break;
        }
        nCur++;
    }

    llama_batch_free(batch);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_aikeyboardoverlay_llm_LlamaEngine_nativeFreeModel(
        JNIEnv* env, jobject /* thiz */, jlong handle) {

    auto* session = reinterpret_cast<LlamaSession*>(handle);
    if (session != nullptr) {
        if (session->ctx) llama_free(session->ctx);
        if (session->model) llama_free_model(session->model);
        delete session;
        LOGI("Model session freed");
    }
    llama_backend_free();
}
