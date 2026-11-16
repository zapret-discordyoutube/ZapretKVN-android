package io.github.zapretkvn.networkbootstrap

import java.io.File
import java.util.concurrent.Executors

/**
 * Addresses the app's DNS transport answered for VPN server names, remembered across sessions (docs/SERVICE_SLOTS.md,
 * "one machine is one server"). One provider machine often comes as several entries with their own host names (VLESS
 * gRPC, VLESS WS, Hysteria2 ...; 2026-11-16 e2e on vpnbot nodes: five entries, one address), and the service slots must
 * not count them as separate servers. A session reads this memory once when it plans its slots, so a later answer
 * never changes a running plan. Only server names are recorded ([ServerNameRegistry]); the file stays on the device.
 */
object ServerAddressMemory {
    private const val MAX_HOSTS = 512
    private const val MAX_ADDRESSES = 8

    private val lock = Any()
    private val byHost = LinkedHashMap<String, Set<String>>(64, 0.75f, true)
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "server-address-memory").apply { isDaemon = true }
    }

    /** Loads the remembered answers and keeps [target] up to date from now on (call once per process). */
    fun attach(target: File) {
        val lines = runCatching { if (target.isFile) target.readLines() else emptyList() }.getOrDefault(emptyList())
        synchronized(lock) {
            file = target
            for (line in lines) {
                val host = normalize(line.substringBefore('\t')) ?: continue
                val addresses = line.substringAfter('\t', "").split(',').map(String::trim).filter(String::isNotEmpty)
                if (addresses.isNotEmpty()) byHost[host] = addresses.take(MAX_ADDRESSES).toSet()
            }
            trim()
        }
    }

    /**
     * Remembers the answer for a server name. A and AAAA come as separate answers, so an answer replaces only the
     * families it carries. The file is written off the caller's (core DNS) thread.
     */
    fun record(host: String?, addresses: Collection<String>) {
        val name = normalize(host) ?: return
        val fresh = addresses.map(String::trim).filter(String::isNotEmpty).toSet()
        if (fresh.isEmpty()) return
        val families = fresh.map(::isV6).toSet()
        val snapshot: List<String>
        val target: File
        synchronized(lock) {
            val kept = byHost[name].orEmpty().filter { isV6(it) !in families }
            val merged = (kept + fresh).sorted().take(MAX_ADDRESSES).toSet()
            if (byHost[name] == merged) return
            byHost[name] = merged
            trim()
            target = file ?: return
            snapshot = byHost.map { (key, value) -> key + "\t" + value.joinToString(",") }
        }
        writer.execute {
            runCatching {
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(snapshot.joinToString("\n", postfix = "\n"))
                if (!temp.renameTo(target)) {
                    target.writeText(temp.readText())
                    temp.delete()
                }
            }
        }
    }

    fun addressesOf(host: String?): Set<String> {
        val name = normalize(host) ?: return emptySet()
        return synchronized(lock) { byHost[name].orEmpty() }
    }

    /** Tests only: forget everything and stop writing. */
    fun resetForTest() {
        synchronized(lock) {
            byHost.clear()
            file = null
        }
    }

    private fun trim() {
        val extra = byHost.size - MAX_HOSTS
        if (extra > 0) byHost.keys.take(extra).forEach(byHost::remove)
    }

    private fun isV6(address: String): Boolean = ':' in address

    private fun normalize(value: String?): String? =
        value?.trim()?.trimEnd('.')?.lowercase()?.ifEmpty { null }
}
