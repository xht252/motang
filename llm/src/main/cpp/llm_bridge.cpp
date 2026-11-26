#include <jni.h>

#include <android/log.h>

#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include <cstdio>
#include <vector>

#define LOG_TAG "MotangLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;
llama_model* g_model = nullptr;
llama_context* g_ctx = nullptr;
const llama_vocab* g_vocab = nullptr;
std::string g_loaded_path;
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
    g_loaded_path.clear();
}

/** 构造采样器。温度 <= 0.01 走贪心。 */
llama_sampler* make_sampler(float temperature) {
    llama_sampler_chain_params params = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(params);
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    return smpl;
}

/**
 * 思考段过滤状态机。
 *
 * 流式场景下不能等整段结束再过滤，否则「首字延迟」等于总时长。
 * 因此维护一个 pending 缓冲：在遇到 `</think>` 之前不向 UI 发送任何内容；
 * 遇到后立刻把后缀发出，此后每个 token 直接发。
 * 若全程没有 `</think>`（模型未思考），结束时一次性 flush。
 */
struct ThinkFilter {
    bool passed = false;
    std::string pending;

    /** 返回本次应发送给 UI 的增量（可能为空）。 */
    std::string push(const std::string& piece) {
        if (passed) return piece;
        pending += piece;
        const std::string marker = "</think>";
        const size_t pos = pending.find(marker);
        if (pos == std::string::npos) {
            return {};
        }
        passed = true;
        std::string rest = pending.substr(pos + marker.size());
        pending.clear();
        const size_t first = rest.find_first_not_of(" \t\r\n");
        if (first == std::string::npos) return {};
        return rest.substr(first);
    }

    std::string flush() {
        if (passed) return {};
        passed = true;
        std::string out;
        out.swap(pending);
        const size_t first = out.find_first_not_of(" \t\r\n");
        return first == std::string::npos ? std::string() : out.substr(first);
    }
};

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

/** 加载模型；同一路径重复调用直接返回（句柄复用，省 ~10s）。 */
JNIEXPORT jstring JNICALL
Java_io_github_xht252_motang_llm_LlmNative_loadModel(
    JNIEnv* env, jobject /*thiz*/, jstring model_path, jint n_threads, jint n_ctx) {
    const std::string path = jstring_to_std(env, model_path);

    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx != nullptr && g_loaded_path == path) {
        LOGI("model already loaded, reuse: %s", path.c_str());
        return env->NewStringUTF("");
    }

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
    g_loaded_path = path;

    LOGI("model loaded: %s (n_ctx=%d, threads=%u)", path.c_str(), g_n_ctx, cparams.n_threads);
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_io_github_xht252_motang_llm_LlmNative_releaseModel(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
}

/**
 * 流式生成。
 *
 * 每个「已通过思考过滤」的增量都回调 `sink.onToken(String)`，
 * 返回值是最终完整答案（便于调用方缓存）。
 */
JNIEXPORT jstring JNICALL
Java_io_github_xht252_motang_llm_LlmNative_generateStream(
    JNIEnv* env, jobject /*thiz*/, jstring prompt, jint max_tokens, jfloat temperature,
    jobject sink) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx == nullptr || g_model == nullptr || g_vocab == nullptr) {
        return env->NewStringUTF("");
    }

    jclass sink_cls = env->GetObjectClass(sink);
    jmethodID on_token = env->GetMethodID(sink_cls, "onToken", "(Ljava/lang/String;)V");
    if (on_token == nullptr) {
        LOGE("TokenSink.onToken 未找到");
        return env->NewStringUTF("");
    }

    const std::string raw_prompt = jstring_to_std(env, prompt);

    // 套用模型自带 chat template：不套模板时模型会把提示词当成续写文本，
    // 输出既不像解析也不稳定（实测问题）。
    std::string text = raw_prompt;
    {
        const char* tmpl = llama_model_chat_template(g_model, nullptr);
        llama_chat_message msgs[2];
        msgs[0].role = "system";
        msgs[0].content = "你是古典文学助教。仅依据给定诗文作答，不虚构典故，不确定处写「存疑」。直接给出答案，不要输出思考过程。\n/no_think";
        msgs[1].role = "user";
        msgs[1].content = raw_prompt.c_str();
        const int need = llama_chat_apply_template(tmpl, msgs, 2, true, nullptr, 0);
        if (need > 0) {
            std::string buf(static_cast<size_t>(need), '\0');
            const int got = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), need);
            if (got > 0) {
                buf.resize(static_cast<size_t>(got));
                text = buf;
            }
        }
    }

    llama_memory_clear(llama_get_memory(g_ctx), true);

    std::vector<llama_token> tokens(text.size() + 8);
    int n = llama_tokenize(
        g_vocab, text.c_str(), static_cast<int32_t>(text.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()),
        true, true);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            g_vocab, text.c_str(), static_cast<int32_t>(text.size()),
            tokens.data(), static_cast<int32_t>(tokens.size()),
            true, true);
    }
    if (n <= 0) return env->NewStringUTF("");
    tokens.resize(static_cast<size_t>(n));

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_ctx, batch) != 0) return env->NewStringUTF("");

    llama_sampler* smpl = make_sampler(temperature);
    ThinkFilter filter;
    std::string answer;
    const int limit = max_tokens > 0 ? max_tokens : 256;
    std::vector<char> piece(64);

    for (int i = 0; i < limit; ++i) {
        const llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, id)) break;
        llama_sampler_accept(smpl, id);

        int piece_len = llama_token_to_piece(
            g_vocab, id, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (piece_len < 0) {
            piece.resize(static_cast<size_t>(-piece_len));
            piece_len = llama_token_to_piece(
                g_vocab, id, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }

        if (piece_len > 0) {
            const std::string delta = filter.push(std::string(piece.data(), static_cast<size_t>(piece_len)));
            if (!delta.empty()) {
                answer += delta;
                jstring jdelta = env->NewStringUTF(delta.c_str());
                env->CallVoidMethod(sink, on_token, jdelta);
                env->DeleteLocalRef(jdelta);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    break;
                }
            }
        }

        llama_batch next = llama_batch_get_one(const_cast<llama_token*>(&id), 1);
        if (llama_decode(g_ctx, next) != 0) break;
    }

    llama_sampler_free(smpl);

    // 模型未输出思考段时，一次性 flush
    const std::string tail = filter.flush();
    if (!tail.empty()) {
        answer += tail;
        jstring jtail = env->NewStringUTF(tail.c_str());
        env->CallVoidMethod(sink, on_token, jtail);
        env->DeleteLocalRef(jtail);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    env->DeleteLocalRef(sink_cls);

    // 截断到轮次结束标记：否则模型答完会继续生成「下一轮」，看起来像 prompt 复读。
    {
        static const char* kStops[] = {
            "<|im_end|>", "<|im_start|>", "<|endoftext|>", "<|end|>",
            "<|eot_id|>", "</s>", "[/INST]",
        };
        size_t cut = std::string::npos;
        for (const char* marker : kStops) {
            const size_t pos = answer.find(marker);
            if (pos != std::string::npos && (cut == std::string::npos || pos < cut)) cut = pos;
        }
        if (cut != std::string::npos) answer.resize(cut);

        // 剥离思考块：模型可能输出未闭合的 <think>，直接展示会像 prompt 复读。
        const size_t think_end = answer.find("</think>");
        if (think_end != std::string::npos) {
            answer.erase(0, think_end + 8);
        } else {
            const size_t think_open = answer.find("<think>");
            if (think_open != std::string::npos) answer.erase(0, think_open + 7);
        }

        // 若模型把 prompt 原样回显，去掉这段前缀。
        if (!answer.empty() && answer.rfind(text, 0) == 0) {
            answer.erase(0, text.size());
        }
        const size_t first = answer.find_first_not_of(" \t\r\n");
        answer = (first == std::string::npos) ? std::string() : answer.substr(first);
    }

    LOGI("answer head: %.120s", answer.c_str());
    return env->NewStringUTF(answer.c_str());
}

/** 非流式生成（保留：缓存填充、批处理场景）。 */
JNIEXPORT jstring JNICALL
Java_io_github_xht252_motang_llm_LlmNative_generate(
    JNIEnv* env, jobject /*thiz*/, jstring prompt, jint max_tokens, jfloat temperature) {
    // 复用流式实现，传入一个丢弃增量的 sink
    jclass sink_cls = env->FindClass("io/github/xht252/motang/llm/LlmNative$NoopSink");
    if (sink_cls == nullptr) {
        env->ExceptionClear();
        return env->NewStringUTF("");
    }
    jmethodID ctor = env->GetMethodID(sink_cls, "<init>", "()V");
    jobject sink = env->NewObject(sink_cls, ctor);
    jstring result = Java_io_github_xht252_motang_llm_LlmNative_generateStream(
        env, nullptr, prompt, max_tokens, temperature, sink);
    if (sink != nullptr) env->DeleteLocalRef(sink);
    env->DeleteLocalRef(sink_cls);
    return result;
}

}  // extern "C"

// ---------------------------------------------------------------------------
