package io.github.zapretkvn.android.ui

import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup
import io.github.zapretkvn.core.uimodel.CountryGuess
import io.github.zapretkvn.core.uimodel.ScreenshotPrivacy
import io.github.zapretkvn.core.uimodel.ServerName
import io.github.zapretkvn.core.uimodel.inferCountries

/**
 * Сервер, через который сейчас идёт трафик, в том виде, в каком его показывает вкладка
 * «Серверы»: флаг и имя из подписки («🇱🇻 Hiprig · VLESS WS · TLS»). Для уведомления VPN.
 *
 * Страна без флага в имени — та же догадка, что на экране ([inferCountries] по всем
 * серверам групп и таблице «хост → страна»), чтобы уведомление и список не расходились.
 */
object CurrentServerLabel {
    /** null — групп нет (свой JSON без селектора) или выбор ещё неизвестен. */
    fun of(
        groups: List<RuntimeSelectorGroup>,
        hostCountries: Map<String, String>,
        hideAddresses: Boolean,
    ): String? {
        val tag = currentTag(groups) ?: return null
        val items = groups.flatMap { it.items }.distinctBy { it.tag }
        val parsed = ServerName.parse(tag)
        val flag = parsed.flag ?: inferCountries(items.map { it.tag to it.endpoint }, hostCountries)[tag]
            ?.let(CountryGuess::flagOf)
        val masked = ScreenshotPrivacy.serverLabel(tag, hideAddresses)
        val title = if (masked != tag) masked else parsed.title
        return listOfNotNull(flag, title).joinToString(" ")
    }

    /**
     * Выбор основной группы; если выбрана вложенная группа («Авто»), берётся уже её выбор.
     * Глубина ограничена: зацикленный JSON не должен вешать уведомление.
     */
    fun currentTag(groups: List<RuntimeSelectorGroup>): String? {
        val byTag = groups.associateBy { it.tag }
        var group = groups.firstOrNull { it.primary } ?: groups.firstOrNull() ?: return null
        repeat(MAX_NESTING) {
            val selected = group.selected.takeIf(String::isNotBlank) ?: return null
            group = byTag[selected]?.takeIf { it !== group } ?: return selected
        }
        return null
    }

    private const val MAX_NESTING = 4
}
