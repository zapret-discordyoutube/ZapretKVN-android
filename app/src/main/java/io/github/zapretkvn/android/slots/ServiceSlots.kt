package io.github.zapretkvn.android.slots

import io.github.zapretkvn.android.config.ForcedProxyDomains
import java.net.InetAddress

/**
 * Service slots (owner decision 2026-11-14, see docs/SERVICE_SLOTS.md): YouTube, Telegram, AI apps and "everything
 * else" each get their own server and their own switching logic. This file is the pure part: which slot a connection
 * belongs to ([ServiceSlotRouter]) and which server a slot uses ([SlotServerAssigner]). It has no Android types and
 * no clock, so the decisions are tested on plain values; the config builder and the runtime only execute them.
 *
 * Direct is not a slot. An app that is not selected for the VPN never reaches this code (it stays outside the TUN);
 * an app that is selected always lands in a slot, `default` when nothing else matches.
 */
internal object SlotIds {
    const val AI = "ai"
    const val YOUTUBE = "youtube"
    const val TELEGRAM = "telegram"
    const val DEFAULT = "default"
}

/**
 * One slot: what it matches and which servers it may use.
 *
 * [pool] holds server ids of the active profile; null means every server of the profile. [pinned] overrides the
 * automatic choice while that server is usable.
 */
internal data class SlotDefinition(
    val id: String,
    val title: String,
    val packages: Set<String> = emptySet(),
    val packagePrefixes: Set<String> = emptySet(),
    val domainSuffixes: Set<String> = emptySet(),
    val cidrs: Set<String> = emptySet(),
    val pool: List<String>? = null,
    val pinned: String? = null,
)

/** The slots that ship with the app. */
internal object SlotCatalog {
    val youtubePackages: Set<String> = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "app.revanced.android.youtube",
        "app.revanced.android.apps.youtube.music",
        "app.rvx.android.youtube",
        "app.rvx.android.apps.youtube.music",
        "com.vanced.android.youtube",
        "com.vanced.android.apps.youtube.music",
        "app.morphe.android.youtube",
        "app.morphe.androoid.youtube",
        "app.morphe.android.apps.youtube.music",
    )

    val telegramPackages: Set<String> = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.beta",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "org.zastogram.messenger",
        "org.zastogram.messenger.beta",
        "org.zastogram.messenger.web",
        "tw.nekomimi.nekogram",
        "tw.nekomimi.nekogram.beta",
        "nekox.messenger",
        "xyz.nextalone.nagram",
        "com.radolyn.ayugram",
        "com.exteragram.messenger",
        "it.belloworld.mercurygram",
        "it.belloworld.mercurygram.beta",
        "uz.unnarsx.cherrygram",
        "org.forkgram.messenger",
        "org.forkgram.classic",
        "org.vidogram.messenger",
        "org.monogram",
    )

    /** Builds of one fork share a prefix (`org.zastogram.messenger.dev` is not in the package list above). */
    val telegramPackagePrefixes: Set<String> = setOf("org.zastogram.messenger.")

    /** Telegram datacentre ranges: a fork nobody listed is still caught by where it connects. */
    val telegramCidrs: Set<String> = setOf(
        "91.108.4.0/22",
        "91.108.8.0/22",
        "91.108.12.0/22",
        "91.108.16.0/22",
        "91.108.20.0/22",
        "91.108.56.0/22",
        "149.154.160.0/20",
        "185.76.151.0/24",
        "2001:67c:4e8::/48",
        "2001:b28:f23d::/48",
        "2001:b28:f23f::/48",
        "2a0a:f280::/32",
    )

    val telegramDomains: Set<String> = setOf("telegram.org", "t.me", "telegram.me", "telesco.pe", "tdesktop.com", "telegra.ph")

    /** The Gemini assistant runs inside the Google app, so that app belongs to the AI slot (as in the app picker). */
    val aiPackages: Set<String> = setOf(
        "com.openai.chatgpt",
        "com.anthropic.claude",
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox",
        "ai.perplexity.app.android",
        "com.microsoft.copilot",
        "com.deepseek.chat",
        "ai.x.grok",
    )

    val aiDomains: Set<String> = setOf(
        "openai.com",
        "chatgpt.com",
        "oaistatic.com",
        "oaiusercontent.com",
        "anthropic.com",
        "claude.ai",
        "gemini.google.com",
        "generativelanguage.googleapis.com",
        "perplexity.ai",
        "deepseek.com",
        "x.ai",
        "grok.com",
        "copilot.microsoft.com",
    )

    /** Built-in slots in match order; `default` is last and matches whatever is left. */
    fun builtIn(): List<SlotDefinition> = listOf(
        SlotDefinition(SlotIds.AI, "AI", packages = aiPackages, domainSuffixes = aiDomains),
        SlotDefinition(SlotIds.YOUTUBE, "YouTube", packages = youtubePackages, domainSuffixes = ForcedProxyDomains.suffixes.toSet()),
        SlotDefinition(
            SlotIds.TELEGRAM,
            "Telegram",
            packages = telegramPackages,
            packagePrefixes = telegramPackagePrefixes,
            domainSuffixes = telegramDomains,
            cidrs = telegramCidrs,
        ),
        SlotDefinition(SlotIds.DEFAULT, "Everything else"),
    )
}

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

/** An IPv4 or IPv6 range written as `address/prefix`; literals only, so parsing never touches DNS. */
internal class Cidr private constructor(private val network: ByteArray, private val prefixBits: Int) {
    fun contains(address: ByteArray): Boolean {
        if (address.size != network.size) return false
        val fullBytes = prefixBits / 8
        for (i in 0 until fullBytes) if (address[i] != network[i]) return false
        val rest = prefixBits % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (address[fullBytes].toInt() and mask) == (network[fullBytes].toInt() and mask)
    }

    companion object {
        private val LITERAL = Regex("""^[0-9a-fA-F:.]+$""")

        fun literal(text: String): ByteArray? {
            val trimmed = text.trim().removePrefix("[").removeSuffix("]")
            if (trimmed.isEmpty() || !LITERAL.matches(trimmed)) return null
            if (!trimmed.contains(':') && !trimmed.contains('.')) return null
            return runCatching { InetAddress.getByName(trimmed).address }.getOrNull()
        }

        fun parse(text: String): Cidr? {
            val parts = text.split('/')
            if (parts.size != 2) return null
            val network = literal(parts[0]) ?: return null
            val bits = parts[1].toIntOrNull() ?: return null
            if (bits < 0 || bits > network.size * 8) return null
            return Cidr(network, bits)
        }
    }
}

/** A server of the active profile as the assigner sees it. */
internal data class SlotServer(val id: String, val usable: Boolean = true)

internal enum class SlotChoice {
    /** The pinned server is usable and used. */
    Pinned,

    /** The slot keeps the server it already had. */
    Kept,

    /** The app chose a server, preferring one no other slot uses. */
    Chosen,

    /** The pinned server is not usable, so the app chose another one until it is back. */
    PinUnavailable,

    /** No usable server in the pool. */
    None,
}

internal data class SlotAssignment(val slotId: String, val serverId: String?, val choice: SlotChoice)

/**
 * Picks a server for every slot: pin first, then the server the slot already has, then the usable server the fewest
 * other slots use (with a small pool servers are shared evenly rather than a slot left without one). Server order is
 * the profile's order, so the result is deterministic. Failure handling is the same call with the failed server
 * marked unusable: the slot moves, other slots keep what they have.
 */
internal object SlotServerAssigner {
    fun assign(
        slots: List<SlotDefinition>,
        servers: List<SlotServer>,
        previous: Map<String, String> = emptyMap(),
    ): List<SlotAssignment> {
        val usable = servers.filter { it.usable }.map { it.id }.toSet()
        val order = servers.map { it.id }
        val taken = mutableMapOf<String, Int>()
        val result = mutableListOf<SlotAssignment>()
        // Slots that keep their server are settled first, so a slot that must move does not take their server.
        val settled = mutableMapOf<String, SlotAssignment>()
        for (slot in slots) {
            val pool = poolOf(slot, order).filter { it in usable }
            val pin = slot.pinned
            when {
                pin != null && pin in pool -> settled[slot.id] = SlotAssignment(slot.id, pin, SlotChoice.Pinned)
                pin == null && previous[slot.id]?.let { it in pool } == true ->
                    settled[slot.id] = SlotAssignment(slot.id, previous.getValue(slot.id), SlotChoice.Kept)
            }
        }
        settled.values.forEach { it.serverId?.let { id -> taken.merge(id, 1, Int::plus) } }
        for (slot in slots) {
            val ready = settled[slot.id]
            if (ready != null) {
                result += ready
                continue
            }
            val pool = poolOf(slot, order).filter { it in usable }
            // Least used first, profile order breaks ties: servers are shared evenly when the pool is small.
            val pick = pool.minByOrNull { taken[it] ?: 0 }
            if (pick == null) {
                result += SlotAssignment(slot.id, null, SlotChoice.None)
                continue
            }
            taken.merge(pick, 1, Int::plus)
            val choice = if (slot.pinned != null) SlotChoice.PinUnavailable else SlotChoice.Chosen
            result += SlotAssignment(slot.id, pick, choice)
        }
        return result
    }

    private fun poolOf(slot: SlotDefinition, order: List<String>): List<String> {
        val pool = slot.pool ?: return order
        return order.filter { it in pool }
    }
}
