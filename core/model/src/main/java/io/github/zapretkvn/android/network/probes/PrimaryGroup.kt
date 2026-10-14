package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.vpn.RuntimeSelectorGroup

fun List<RuntimeSelectorGroup>.primaryGroup(): RuntimeSelectorGroup? =
    firstOrNull { group -> group.primary && group.items.any { it.tag == group.selected } }
        ?: firstOrNull { group ->
        group.selectable && group.items.any { it.tag == group.selected }
    } ?: firstOrNull { it.items.isNotEmpty() }
