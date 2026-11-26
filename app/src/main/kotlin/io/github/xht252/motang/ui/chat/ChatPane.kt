package io.github.xht252.motang.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xht252.motang.ui.theme.LocalInkTokens

/** 聊天消息。 */
data class ChatMessage(
    val text: String,
    val fromUser: Boolean,
)

/**
 * 对话页（M5 追加）。
 *
 * 设计遵循 UI Skill：
 * - 宣纸底、墨字、印章红点缀；不使用高饱和色块区分身份
 * - 用户消息右对齐（淡纸色气泡），AI 消息左对齐（无气泡、纯文本，阅读优先）
 * - 触控目标 ≥ 48dp；输入区处理 IME inset
 */
@Composable
fun ChatPane(
    messages: List<ChatMessage>,
    input: String,
    busy: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInkTokens.current
    val listState = rememberLazyListState()

    // 新消息或流式增量时贴底
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("问诗", style = MaterialTheme.typography.headlineSmall, color = tokens.ink)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages) { message ->
                if (message.fromUser) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = tokens.maxReadingWidth * 0.8f)
                                .background(tokens.paperDeep, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = message.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = tokens.ink,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = tokens.maxReadingWidth),
                    ) {
                        // AI 侧不用气泡：长解析以阅读为主，气泡会压缩行宽。
                        Text(
                            text = message.text.ifBlank { "思考中…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.ink,
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("问点什么，例如：这首诗好在哪里？") },
            enabled = !busy,
            maxLines = 4,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSend, enabled = !busy && input.isNotBlank()) {
                Text(if (busy) "生成中…" else "发送")
            }
        }
    }
}
