package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.config.SelectorGroup

/** Сервер внутри sing-box selector: то, что пользователь выбирает в GUI. */
data class ProfileServerOption(
    val tag: String,
    val type: String,
    val endpoint: String?,
) {
    val subtitle: String
        get() = listOfNotNull(
            type.uppercase().takeIf(String::isNotBlank),
            endpoint,
        ).joinToString(" · ")
}

/** Группа selector профиля вместе с текущим `default`. */
data class ProfileServerGroup(
    val tag: String,
    val selected: String?,
    val options: List<ProfileServerOption>,
)

data class ProfileServerSummary(
    val groups: List<ProfileServerGroup> = emptyList(),
) {
    val serverCount: Int get() = groups.sumOf { it.options.size }

    /** Переключать есть что только тогда, когда в группе больше одного сервера. */
    val switchable: Boolean get() = groups.any { it.options.size > 1 }

    val primaryGroup: ProfileServerGroup?
        get() = groups.firstOrNull { it.tag == ConfigAnalyzer.MANAGED_SELECTOR_TAG }
            ?: groups.firstOrNull { it.options.size > 1 }
            ?: groups.firstOrNull()

    val selectedLabel: String?
        get() = primaryGroup?.let { group ->
            group.selected?.takeIf(String::isNotBlank) ?: group.options.firstOrNull()?.tag
        }
}

/**
 * Читает список серверов профиля прямо из сохранённого sing-box JSON,
 * чтобы GUI мог переключать сервер и без запущенного ядра.
 */
object ProfileServerCatalog {
    fun summarize(rawJson: String): ProfileServerSummary = runCatching {
        val groups = ConfigAnalyzer.selectorGroups(rawJson)
        if (groups.isEmpty()) return@runCatching ProfileServerSummary()
        val descriptions = ConfigAnalyzer.outboundDescriptions(rawJson)
        ProfileServerSummary(
            groups.mapNotNull { group -> group.toProfileGroup(descriptions) },
        )
    }.getOrDefault(ProfileServerSummary())

    private fun SelectorGroup.toProfileGroup(
        descriptions: Map<String, OutboundDescription>,
    ): ProfileServerGroup? {
        val options = outbounds.map { member ->
            val description = descriptions[member]
            ProfileServerOption(
                tag = member,
                type = description?.type.orEmpty(),
                endpoint = description?.endpoint,
            )
        }
        if (options.isEmpty()) return null
        return ProfileServerGroup(
            tag = tag,
            selected = default?.takeIf { selected -> options.any { it.tag == selected } }
                ?: options.first().tag,
            options = options,
        )
    }
}
