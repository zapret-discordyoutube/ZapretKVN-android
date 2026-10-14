package io.github.zapretkvn.android.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.zapretkvn.core.designsystem.SettingsRow
import io.github.zapretkvn.core.designsystem.ZapretIcons

/** Официальные ссылки сообщества. Других мест, где они записаны, в приложении нет. */
data class CommunityLink(val title: String, val subtitle: String, val url: String)

object CommunityLinks {
    val all: List<CommunityLink> = listOf(
        CommunityLink("Zapret KVN", "Telegram-канал", "https://t.me/bypassblock"),
        CommunityLink("VPN Discord YouTube", "Telegram-сообщество", "https://t.me/vpndiscordyooutube"),
        CommunityLink("Zapret VPN bot", "Telegram-бот", "https://t.me/zapretvpns_bot"),
    )
}

@Composable
fun CommunityScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    var openError by remember { mutableStateOf<String?>(null) }
    val openLink: (String) -> Unit = { url ->
        openError = try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            null
        } catch (_: ActivityNotFoundException) {
            "Не найдено приложение для открытия ссылки."
        } catch (_: SecurityException) {
            "Android запретил открыть ссылку."
        }
    }

    SettingsPageList(contentPadding, tag = "settings-page-community") {
        item(key = "intro", contentType = "text") {
            HintText(
                "Независимый клиент работает с любыми совместимыми профилями. " +
                    "Эти ссылки относятся только к сообществу Zapret KVN.",
            )
        }
        item(key = "links", contentType = "rows") {
            RowsCard {
                CommunityLinks.all.forEachIndexed { index, link ->
                    if (index > 0) RowDivider()
                    SettingsRow(
                        title = link.title,
                        subtitle = link.subtitle,
                        icon = ZapretIcons.Chat,
                        onClick = { openLink(link.url) },
                    )
                }
            }
        }
        openError?.let { message ->
            item(key = "error", contentType = "text") {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
