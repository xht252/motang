package io.github.xht252.motang.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import io.github.xht252.motang.data.FontScale
import io.github.xht252.motang.ui.theme.LocalInkTokens
import io.github.xht252.motang.ui.theme.MotangTheme

/**
 * 设置页（M2 起提供字号）。
 *
 * 交互遵循 ui-ux-pro-max 的 UX 指引：
 * - 44/48dp 触控目标（整行可点，不只有小圆点）
 * - 选择项带 `selectableGroup` 与 `Role.RadioButton`，供无障碍服务识别
 * - 实时预览：改字号立刻看到效果，不用返回
 */
@Composable
fun SettingsPane(
    fontScale: FontScale,
    onFontScaleChange: (FontScale) -> Unit,
    reminder: io.github.xht252.motang.data.ReminderSettings,
    onReminderChange: (io.github.xht252.motang.data.ReminderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInkTokens.current
    // 仅在 vivo 设备展示 OriginOS 白名单引导（Build.MANUFACTURER 只用于展示文案，不参与布局）。
    val isVivo = android.os.Build.MANUFACTURER.equals("vivo", ignoreCase = true)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.displaySmall,
            color = tokens.ink,
        )

        Text(
            text = "阅读字号",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.ink,
        )
        Text(
            text = "影响正文与检索结果的文字大小，立即生效",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.pencil,
        )

        Column(
            modifier = Modifier.selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FontScale.entries.forEach { scale ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 整行可点：触控目标高度 48dp
                        .selectable(
                            selected = fontScale == scale,
                            onClick = { onFontScaleChange(scale) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RadioButton(
                        selected = fontScale == scale,
                        // 点击交给整行，避免无障碍下出现两个可点目标
                        onClick = null,
                    )
                    Text(
                        text = scale.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(scale.multiplier * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.pencil,
                    )
                }
            }
        }

        HorizontalDivider(color = tokens.hairline)

        // ---------------- 每日提醒（M3） ----------------
        Text(
            text = "每日提醒",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.ink,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = reminder.enabled,
                    onClick = { onReminderChange(reminder.copy(enabled = !reminder.enabled)) },
                    role = Role.Switch,
                )
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "开启每日提醒",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.ink,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = reminder.enabled, onCheckedChange = null)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Text(
                text = "提醒时间",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.ink,
                modifier = Modifier.weight(1f),
            )
            // 用步进按钮而不是实验性 TimePicker：避免 experimental API 与弹窗焦点问题。
            Text(
                text = "%02d:%02d".format(reminder.hour, reminder.minute),
                style = MaterialTheme.typography.titleMedium,
                color = tokens.ink,
            )
            TextButton(onClick = {
                onReminderChange(reminder.copy(hour = (reminder.hour + 23) % 24))
            }) { Text("−") }
            TextButton(onClick = {
                onReminderChange(reminder.copy(hour = (reminder.hour + 1) % 24))
            }) { Text("+") }
        }

        if (isVivo) {
            Text(
                text = "vivo / OriginOS 需额外放行，否则提醒可能不到：\n" +
                    "1. i管家 → 应用启动管理 → 允许自启动与后台运行\n" +
                    "2. 设置 → 电池 → 后台高耗电 → 允许墨唐\n" +
                    "3. 设置 → 通知 → 墨唐 → 允许锁屏显示",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.pencil,
            )
        }

        HorizontalDivider(color = tokens.hairline)

        Text(
            text = "预览",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.pencil,
        )
        Column(
            modifier = Modifier
                .widthIn(max = tokens.maxReadingWidth)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            detailBody("床前明月光，疑是地上霜。", fontScale)
            detailBody("举头望山月，低头思故乡。", fontScale)
        }

        HorizontalDivider(color = tokens.hairline)
        Text(
            text = "字体：霞鹜文楷（OFL）。未内置时回退系统衬线体。",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.pencil,
        )
    }
}

@Composable
private fun detailBody(text: String, scale: FontScale) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale.multiplier,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * scale.multiplier,
        ),
        color = LocalInkTokens.current.ink,
        textAlign = TextAlign.Start,
    )
}

@Preview(name = "设置-360dp", widthDp = 360, heightDp = 900)
@Composable
private fun SettingsPanePreview() {
    MotangTheme {
        io.github.xht252.motang.ui.InkBackground(modifier = Modifier.fillMaxSize()) {
            SettingsPane(
                fontScale = FontScale.Large,
                onFontScaleChange = {},
                reminder = io.github.xht252.motang.data.ReminderSettings(),
                onReminderChange = {},
            )
        }
    }
}
