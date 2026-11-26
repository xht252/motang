package io.github.xht252.motang.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.xht252.motang.llm.LlmClient
import kotlinx.coroutines.launch

/**
 * 对话页容器：持有消息与输入状态，负责调用端侧模型。
 *
 * 放在这里而不是 MotangApp，避免把 5~6 个参数继续向下透传。
 */
@Composable
fun ChatScreen(
    llmClient: LlmClient,
    modelPath: String,
    modifier: Modifier = Modifier,
) {
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun send() {
        val question = input.trim()
        if (question.isEmpty() || busy) return
        input = ""
        busy = true
        messages = messages + ChatMessage(question, fromUser = true) + ChatMessage("", fromUser = false)

        scope.launch {
            runCatching { llmClient.loadModel(modelPath, 4, 2048) }
            runCatching {
                llmClient.generateStream(
                    prompt = buildChatPrompt(question),
                    maxTokens = 384,
                    temperature = 0.4f,
                ) { delta ->
                    // 更新最后一条（AI）消息
                    messages = messages.dropLast(1) +
                        messages.last().copy(text = messages.last().text + delta)
                }
            }.onFailure {
                messages = messages.dropLast(1) +
                    messages.last().copy(text = "生成失败：${it.message}")
            }
            busy = false
        }
    }

    ChatPane(
        messages = messages,
        input = input,
        busy = busy,
        onInputChange = { input = it },
        onSend = { send() },
        modifier = modifier,
    )
}

/** 对话 prompt：只谈诗，不要编造；短答优先。 */
private fun buildChatPrompt(question: String): String =
    "你是古典文学助教。仅依据可靠的文学常识回答，不编造作者生平、年代与典故出处；" +
        "不确定处标注「待考」。回答简洁，控制在 200 字以内。\n用户问：$question"
