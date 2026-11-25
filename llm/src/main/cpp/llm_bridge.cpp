#include <jni.h>

#include <android/log.h>

#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "MotangLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;
llama_model* g_model = nullptr;
llama_context* g_ctx = nullptr;
const llama_vocab* g_vocab = nullptr;
int g_n_ctx = 2048;

std::string jstring_to_std(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

void release_locked() {
    if (g_ctx != nullptr) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_xht252_motang_llm_LlmNative_probe(JNIEnv* env, jobject /*thiz*/) {
    char buffer[128];
    snprintf(buffer, sizeof(buffer), "motang-llm llama.cpp ok (devices=%zu)", llama_max_devices());
    return env->NewStringUTF(buffer);
}

JNIEXPORT jstring JNICALL
Java_io_github_xht252_motang_llm_LlmNative_echoModelPath(
    JNIEnv* env, jobject /*thiz*/, jstring model_path) {
    const std::string path = jstring_to_std(env, model_path);
    return env->NewStringUTF(path.c_str());
}

/**
 * 加载模型。返回空串表示成功，否则返回错误信息。
 *
 * n_gpu_layers = 0：端侧统一走 CPU（KleidiAI 内核），避免 GPU 后端差异。
 */
JNIEXPORT jstring JNICALL
Java_io_github_xht252_motang_llm_LlmNative_loadModel(
    JNIEnv* env, jobject /*thiz*/, jstring model_path, jint n_threads, jint n_ctx) {
    const std::string path = jstring_to_std(env, model_path);

    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();

    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (g_model == nullptr) {
        return env->NewStringUTF("llama_model_load_from_file 失败");
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : 2048);
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = cparams.n_threads;
    cparams.n_batch = 256;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (g_ctx == nullptr) {
        release_locked();
        return env->NewStringUTF("llama_init_from_model 失败");
    }

    g_vocab = llama_model_get_vocab(g_model);
    g_n_ctx = static_cast<int>(cparams.n_ctx);

    LOGI("model loaded: %s (n_ctx=%d, threads=%u)", path.c_str(), g_n_ctx, cparams.n_threads);
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_io_github_xht252_motang_llm_LlmNative_releaseModel(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
}

/**
 * 生成。
 *
 * 关闭 thinking 的方式：**输出侧过滤**。
 * 若输出出现 `</think>`，丢弃它之前的内容只保留其后的答案；
 * 同时把 `</think>` 之后的文本才算正文。这样不依赖各模型私有的
 * chat template 开关（MiniCPM5/Qwen3 表达方式不同）。
 */
JNIEXPORT jstring JNICALL
Java_io_github_xht252_motang_llm_LlmNative_generate(
    JNIEnv* env, jobject /*thiz*/, jstring prompt, jint max_tokens, jfloat temperature) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_ctx == nullptr || g_model == nullptr || g_vocab == nullptr) {
        return env->NewStringUTF("");
    }

    const std::string text = jstring_to_std(env, prompt);

    // 1) tokenize（清空 KV：每次生成独立，先做最简单的正确版本）
    llama_memory_clear(llama_get_memory(g_ctx), true);

    std::vector<llama_token> tokens(text.size() + 8);
    int n = llama_tokenize(
        g_vocab, text.c_str(), static_cast<int32_t>(text.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()),
        /*add_special=*/true, /*parse_special=*/true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(
            g_vocab, text.c_str(), static_cast<int32_t>(text.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()),
            true, true);
    }
    if (n <= 0) {
        return env->NewStringUTF("");
    }
    tokens.resize(n);

    // 2) prompt 解码
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_ctx, batch) != 0) {
        return env->NewStringUTF("");
    }

    // 3) 采样链：低温、确定性强
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // 4) 逐 token 生成
    std::string raw;
    const int limit = max_tokens > 0 ? max_tokens : 256;
    std::vector<char> piece(64);

    for (int i = 0; i < limit; ++i) {
        const llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, id)) {
            break;
        }
        llama_sampler_accept(smpl, id);

        int piece_len = llama_token_to_piece(
            g_vocab, id, piece.data(), static_cast<int32_t>(piece.size()), 0, /*special=*/false);
        if (piece_len < 0) {
            piece.resize(-piece_len);
            piece_len = llama_token_to_piece(
                g_vocab, id, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }
        if (piece_len > 0) {
            raw.append(piece.data(), static_cast<size_t>(piece_len));
        }

        llama_batch next = llama_batch_get_one(const_cast<llama_token*>(&id), 1);
        if (llama_decode(g_ctx, next) != 0) {
            break;
        }
    }

    llama_sampler_free(smpl);

    // 5) 过滤思考段：只保留 </think> 之后的内容
    const std::string marker = "</think>";
    const size_t pos = raw.find(marker);
    std::string answer = (pos == std::string::npos) ? raw : raw.substr(pos + marker.size());
    // 去掉开头空白
    const size_t first = answer.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) {
        answer.clear();
    } else if (first > 0) {
        answer.erase(0, first);
    }

    return env->NewStringUTF(answer.c_str());
}

}  // extern "C"
