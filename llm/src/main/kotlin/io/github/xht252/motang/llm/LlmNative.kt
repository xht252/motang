package io.github.xht252.motang.llm

/** 流式增量回调。 */
fun interface TokenSink {
    fun onToken(text: String)
}

/**
 * llama.cpp 的 JNI 入口。
 *
 * 只做「薄封装」：参数与返回值保持简单，复杂对象在 Kotlin 侧组装。
 * 推理运行在 `android:process=":llm"` 独立进程。
 */
object LlmNative {

    init {
        System.loadLibrary("motang_llm")
    }

    /** 供 native 侧 `generate` 复用的空 sink（JNI 按名字查找）。 */
    class NoopSink : TokenSink {
        override fun onToken(text: String) = Unit
    }

    external fun probe(): String

    external fun echoModelPath(modelPath: String): String

    /**
     * 加载模型。同一路径重复调用会直接复用（省 ~10s）。
     *
     * @return 空串表示成功。
     */
    external fun loadModel(modelPath: String, nThreads: Int, nCtx: Int): String

    external fun releaseModel()

    /**
     * 流式生成：每个通过思考过滤的增量回调 [sink]。
     *
     * @return 最终完整答案（用于写缓存）。
     */
    external fun generateStream(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        sink: TokenSink,
    ): String

    /** 非流式（内部复用 generateStream）。 */
    external fun generate(prompt: String, maxTokens: Int, temperature: Float): String
}
