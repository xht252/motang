package io.github.xht252.motang.ui.checkin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xht252.motang.data.StudySession
import io.github.xht252.motang.ui.theme.LocalInkTokens

/** 已背会列表。 */
@Composable
fun MasteredPane(
    sessions: List<StudySession>,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInkTokens.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("已背会", style = MaterialTheme.typography.displaySmall, color = tokens.ink)
        Text(
            "共 ${sessions.size} 篇",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.pencil,
        )
        HorizontalDivider(color = tokens.hairline)

        if (sessions.isEmpty()) {
            Text(
                "还没有标记为「已背会」的诗。",
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.pencil,
            )
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(sessions, key = { "${it.id}-${it.poemId}" }) { s ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        text = "《${s.title.ifBlank { s.poemId }}》",
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.ink,
                    )
                    Text(
                        text = "${s.author.ifBlank { "佚名" }} · ${s.day}",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.pencil,
                    )
                }
                HorizontalDivider(color = tokens.hairline)
            }
        }
    }
}
