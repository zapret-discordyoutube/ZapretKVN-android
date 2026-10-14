package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.config.ConfigAnalyzer
import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.config.SelectorGroup

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
