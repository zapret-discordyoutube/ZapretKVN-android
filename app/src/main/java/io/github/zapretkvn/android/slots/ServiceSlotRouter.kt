package io.github.zapretkvn.android.slots

/**
 * Decides the slot of one connection. Order (docs/SERVICE_SLOTS.md): an app that belongs to a slot by package wins,
 * then the domain, then the destination address; whatever matches nothing goes to [SlotIds.DEFAULT]. Inside one level
 * the slot listed first wins, so user-defined slots placed before the built-in ones can take a package over.
 */
internal class ServiceSlotRouter(private val slots: List<SlotDefinition>) {
    private val parsedCidrs: Map<String, List<Cidr>> = slots.associate { slot ->
        slot.id to slot.cidrs.mapNotNull(Cidr::parse)
    }

    fun slotFor(packageName: String?, host: String?, ip: String?): String {
        if (!packageName.isNullOrEmpty()) {
            slots.firstOrNull { slot ->
                packageName in slot.packages || slot.packagePrefixes.any(packageName::startsWith)
            }?.let { return it.id }
        }
        val name = host?.trimEnd('.')?.lowercase()
        if (!name.isNullOrEmpty()) {
            slots.firstOrNull { slot -> slot.domainSuffixes.any { name == it || name.endsWith(".$it") } }?.let { return it.id }
        }
        val address = ip?.let(Cidr::literal)
        if (address != null) {
            slots.firstOrNull { slot -> parsedCidrs.getValue(slot.id).any { it.contains(address) } }?.let { return it.id }
        }
        return SlotIds.DEFAULT
    }
}
