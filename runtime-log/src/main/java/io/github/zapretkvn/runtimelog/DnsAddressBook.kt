package io.github.zapretkvn.runtimelog

import java.util.Locale

/**
 * Книга «адрес → имя googlevideo», выученная из строк DNS самого ядра.
 *
 * Зачем. У потока в снимке ядра имени может не быть: плеер идёт по адресу, который ему отдал ответ, а sniff и
 * обратное сопоставление имени не нашли (по диагностике 0.5.39 поток `74.125.11.70` жил 51 с без имени, хотя в том же
 * журнале ответ DNS сообщал `rr1.sn-1giz7n7l.googlevideo.com A 74.125.11.70`). Без имени ни политика сброса, ни
 * история скоростей поток не узнавали. Книга заполняет имя по адресу.
 *
 * Помнятся только имена googlevideo: другие нужны не были, а память ограничена [maxEntries] (самые старые
 * вытесняются). Адрес мог перейти другой службе, поэтому запись живёт не дольше [lifetimeMillis].
 */
class DnsAddressBook(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 2_000,
    private val lifetimeMillis: Long = 60 * 60_000L,
) {
    private class Entry(val name: String, var seenAt: Long)

    private val byAddress = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    /** Разбирает строку лога ядра; нужные ответы DNS запоминаются, остальное игнорируется. */
    @Synchronized
    fun observe(line: String) {
        if (!line.contains("googlevideo")) return
        val match = ANSWER.find(line) ?: return
        val name = match.groupValues[1].trimEnd('.').lowercase(Locale.ROOT)
        if (!(name == "googlevideo.com" || name.endsWith(".googlevideo.com"))) return
        byAddress[normalize(match.groupValues[2])] = Entry(name, clock())
    }

    /** Имя по адресу вида `1.2.3.4:443` или `[2001:db8::1]:443`; `null`, если адрес не известен или запись устарела. */
    @Synchronized
    fun nameFor(destination: String): String? {
        val address = addressOf(destination) ?: return null
        val entry = byAddress[normalize(address)] ?: return null
        if (clock() - entry.seenAt > lifetimeMillis) {
            byAddress.remove(normalize(address))
            return null
        }
        return entry.name
    }

    /** Подставляет имя потоку без имени; поток с именем возвращается как есть. */
    fun enrich(flow: FlowSnap): FlowSnap =
        if (flow.domain.isNotBlank()) flow else nameFor(flow.destination)?.let { flow.copy(domain = it) } ?: flow

    @Synchronized
    fun size(): Int = byAddress.size

    private fun addressOf(destination: String): String? {
        val value = destination.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("[")) return value.substringAfter('[').substringBefore(']').ifEmpty { null }
        val colons = value.count { it == ':' }
        return when {
            colons == 1 -> value.substringBeforeLast(':')
            else -> value
        }
    }

    private fun normalize(address: String) = address.trim().lowercase(Locale.ROOT)

    private companion object {
        /** `dns: exchanged A rr1.sn-x.googlevideo.com. 193 IN A 74.125.11.70` (и AAAA). */
        val ANSWER = Regex("""dns: exchanged (?:A|AAAA) (\S+?)\.? \d+ IN (?:A|AAAA) ([0-9a-fA-F:.]+)""")
    }
}
