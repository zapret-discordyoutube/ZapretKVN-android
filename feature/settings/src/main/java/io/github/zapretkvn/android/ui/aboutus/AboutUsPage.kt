package io.github.zapretkvn.android.ui.aboutus

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
import io.github.zapretkvn.android.ui.settings.HintText
import io.github.zapretkvn.android.ui.settings.RowDivider
import io.github.zapretkvn.android.ui.settings.RowsCard
import io.github.zapretkvn.android.ui.settings.SettingsPageList
import io.github.zapretkvn.core.designsystem.SectionHeader
import io.github.zapretkvn.core.designsystem.SettingsRow

/** Официальные ссылки проекта. Других мест, где они записаны, в приложении нет. */
data class AboutUsLink(val title: String, val subtitle: String, val url: String, val icon: AboutUsIcon)

/**
 * Рекламная метка deep-link бота: источник kvnand + место community.
 * Место не менялось при переезде ссылок во вкладку «О нас»: метку ждёт статистика бота.
 */
const val BOT_COMMUNITY_URL = "https://t.me/zapretvpns_bot?start=kvnand_community"

/** Адрес зеркала-переадресатора: вики и Git открываются через него, пока основные домены закрыты. */
private const val MIRROR = "https://script.google.com/macros/s/AKfycbzcEPk67WW6fYC0vPVm397QyDFHd4m-SrfKDBnfvG8jAD-n_HXVv8AqQnEHZ6A42oIh/exec"

object AboutUsLinks {
    /** Главный VPN-бот, где покупают подписку: стоит над всем остальным. */
    val bot = AboutUsLink("Zapret VPN bot", "Главный VPN-бот: здесь можно купить подписку", BOT_COMMUNITY_URL, AboutUsIcon.Telegram)

    val channels: List<AboutUsLink> = listOf(
        AboutUsLink("Zapret KVN", "Telegram-канал", "https://t.me/bypassblock", AboutUsIcon.Telegram),
        AboutUsLink("Zapret VPN", "Telegram-канал: новости и обновления", "https://t.me/vpndpi", AboutUsIcon.Telegram),
        AboutUsLink("RuNet VPN YouTube Discord", "Telegram-канал", "https://t.me/runetvpnyoutubediscord", AboutUsIcon.Telegram),
        AboutUsLink("Zastogram", "Telegram-канал", "https://t.me/zastogram", AboutUsIcon.Telegram),
    )

    /** Главные ресурсы проекта и запасные списки адресов на случай блокировки основных. */
    val resources: List<AboutUsLink> = listOf(
        AboutUsLink("Вики", "Инструкции и ответы на вопросы", "$MIRROR?s=wiki", AboutUsIcon.Book),
        AboutUsLink("Git", "Исходный код и релизы", "$MIRROR?s=git", AboutUsIcon.Git),
        AboutUsLink("Резервный список адресов", "Документ Google", "https://docs.google.com/document/d/1v1TeRSBpXEJpy6qxNa3tVkyhgtoM_rj_T-jwphhfvYI/", AboutUsIcon.GoogleDocs),
        AboutUsLink("Ещё один резервный список", "GitHub Gist", "https://gist.github.com/fuckyourkn/ecb9b6a03f53bddcdd47023ebc12d4f9", AboutUsIcon.GitHub),
    )

    val all: List<AboutUsLink> = listOf(bot) + channels + resources
}

/** Вкладка «О нас»: бот с покупкой подписки сверху, под ним каналы и главные ресурсы. */
@Composable
fun AboutUsScreen(contentPadding: PaddingValues) {
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

    SettingsPageList(contentPadding, tag = "about-us-screen") {
        item(key = "intro", contentType = "text") {
            HintText(
                "Независимый клиент работает с любыми совместимыми профилями. " +
                    "Эти ссылки относятся только к проекту Zapret KVN.",
            )
        }
        item(key = "h-bot", contentType = "header") { SectionHeader("ПОДПИСКА") }
        item(key = "bot", contentType = "rows") {
            LinksCard(listOf(AboutUsLinks.bot), openLink)
        }
        item(key = "h-channels", contentType = "header") { SectionHeader("КАНАЛЫ") }
        item(key = "channels", contentType = "rows") {
            LinksCard(AboutUsLinks.channels, openLink)
        }
        item(key = "h-resources", contentType = "header") { SectionHeader("РЕСУРСЫ") }
        item(key = "resources", contentType = "rows") {
            LinksCard(AboutUsLinks.resources, openLink)
        }
        openError?.let { message ->
            item(key = "error", contentType = "text") {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun LinksCard(links: List<AboutUsLink>, onOpen: (String) -> Unit) {
    RowsCard {
        links.forEachIndexed { index, link ->
            if (index > 0) RowDivider()
            SettingsRow(
                title = link.title,
                subtitle = link.subtitle,
                icon = link.icon.vector,
                // Фирменный цвет у логотипа свой; без него (GitHub, книга) цвет берёт тема.
                iconTint = link.icon.brandColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onOpen(link.url) },
            )
        }
    }
}
