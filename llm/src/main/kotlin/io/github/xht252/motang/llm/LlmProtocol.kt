package io.github.xht252.motang.llm

/** :llm 进程的通信协议常量。 */
object LlmProtocol {
    const val MSG_LOAD = 1
    const val MSG_GENERATE = 2
    const val MSG_RELEASE = 3
    const val MSG_TOKEN = 101
    const val MSG_RESULT = 100

    const val KEY_TEXT = "text"
    const val KEY_MODEL_PATH = "model_path"
    const val KEY_THREADS = "threads"
    const val KEY_CTX = "ctx"
    const val KEY_PROMPT = "prompt"
    const val KEY_MAX_TOKENS = "max_tokens"
    const val KEY_TEMPERATURE = "temperature"
}
