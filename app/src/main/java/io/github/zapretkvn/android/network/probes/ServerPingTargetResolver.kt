package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.config.OutboundDescription
import io.github.zapretkvn.android.config.SelectorGroup
import io.github.zapretkvn.android.vpn.RuntimeOutboundItem
import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup

internal fun List<RuntimeSelectorGroup>.primaryGroup(): RuntimeSelectorGroup? =
    firstOrNull { group -> group.primary && group.items.any { it.tag == group.selected } }
        ?: firstOrNull { group ->
        group.selectable && group.items.any { it.tag == group.selected }
    } ?: firstOrNull { it.items.isNotEmpty() }

internal class ServerPingTargetResolver(
    private val descriptions: Map<String, OutboundDescription>,
    initialGroups: List<SelectorGroup>,
    private val primaryGroupTag: String? = null,
) {
    private val initialSelections = initialGroups.associate { group ->
        group.tag to (group.default ?: group.outbounds.firstOrNull()).orEmpty()
    }

    fun selected(runtimeGroups: List<RuntimeSelectorGroup>): ServerPingTarget? {
        val selections = selections(runtimeGroups)
        val rootTag = runtimeGroups.firstOrNull { it.tag == primaryGroupTag }?.tag
            ?: runtimeGroups.primaryGroup()?.tag
            ?: primaryGroupTag
            ?: initialSelections.keys.firstOrNull()
        val selectedTag = rootTag?.let(selections::get)
        if (selectedTag != null) return resolve(selectedTag, selections)
        return descriptions.values.singleOrNull { it.serverHost != null }?.toTarget()
    }

    fun group(
        groupTag: String,
        runtimeGroups: List<RuntimeSelectorGroup>,
    ): List<ServerPingTarget> {
        val members = runtimeGroups.firstOrNull { it.tag == groupTag }
            ?.items
            ?.map(RuntimeOutboundItem::tag)
            .orEmpty()
        return members.mapNotNull { descriptions[it]?.toTarget() }
            .distinctBy(ServerPingTarget::outboundTag)
    }

    private fun selections(runtimeGroups: List<RuntimeSelectorGroup>): Map<String, String> =
        initialSelections + runtimeGroups.associate { it.tag to it.selected }

    private fun resolve(
        startTag: String,
        selections: Map<String, String>,
    ): ServerPingTarget? {
        var tag = startTag
        val visited = mutableSetOf<String>()
        while (visited.add(tag)) {
            descriptions[tag]?.toTarget()?.let { return it }
            tag = selections[tag] ?: return null
        }
        return null
    }

    private fun OutboundDescription.toTarget(): ServerPingTarget? = serverHost
        ?.takeIf(String::isNotBlank)
        ?.let { ServerPingTarget(outboundTag = tag, hostname = it) }
}
