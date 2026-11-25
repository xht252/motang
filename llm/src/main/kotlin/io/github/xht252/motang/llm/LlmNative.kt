package io.github.xht252.motang.llm

/**
 * llama.cpp 的 JNI 入口。
 *
 * 只做「薄封装」：参数与返回值保持简单（String / 基础类型），
 * 复杂对象在 Kotlin 侧组装，避免跨边界逐条传对象。
 *
 * 该库由 `:llm` 模块的 CMake 构建产出（libmotang_llm.so），
 * 推理运行在 `android:process=":llm"` 独立进程。
 */
object LlmNative {

    init {
        System.loadLibrary("motang_llm")
    }

    /** 探针：验证 so 能加载、能链接到 llama.cpp。 */
    external fun probe(): String

    /** 回显模型路径（JNI 字符串往返自检）。 */
    external fun echoModelPath(modelPath: String): String

    /**
     * 加载模型。
     *
     * @return 空串表示成功；非空为错误信息。
     */
    external fun loadModel(modelPath: String, nThreads: Int, nCtx: Int): String

    /** 释放模型与上下文。 */
    external fun releaseModel()

    /**
     * 生成一次完整回答（非流式）。
     *
     * 思考段在 native 侧按 `</think>` 过滤，返回的只会是最终答案。
     *
     * @param temperature <= 0.01 时走贪心解码（确定性强）。
     */
    external fun generate(prompt: String, maxTokens: Int, temperature: Float): String
}
