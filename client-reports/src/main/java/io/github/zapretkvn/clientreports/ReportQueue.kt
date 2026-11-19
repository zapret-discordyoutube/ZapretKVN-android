package io.github.zapretkvn.clientreports

import java.io.File

/**
 * Очередь отчётов на устройстве: строка JSON на попытку, запись целиком через временный файл.
 * Отчёт живёт до приёма или до конца недели (дальше приёмник его не возьмёт); при переполнении
 * уходят самые старые. Потокобезопасна; вызывать с фонового потока.
 */
class ReportQueue(private val file: File, private val capacity: Int = DEFAULT_CAPACITY) {
    @Synchronized
    fun add(record: AttemptRecord) {
        if (!ClientReportContract.isValid(record)) return
        write((read() + record).takeLast(capacity))
    }

    /** Отчёты, которые ещё можно отправить, старые первыми; просроченные и испорченные удаляются. */
    @Synchronized
    fun pending(nowMillis: Long): List<AttemptRecord> {
        val all = read()
        val fresh = all.filter { ClientReportContract.isFresh(it, nowMillis) }
        if (fresh.size != all.size || file.exists() && fresh.isEmpty() && all.isEmpty()) write(fresh)
        return fresh
    }

    /** Убрать отправленные (или отклонённые) отчёты; новые, пришедшие во время отправки, остаются. */
    @Synchronized
    fun remove(sent: Collection<AttemptRecord>) {
        if (sent.isEmpty()) return
        val left = read().toMutableList()
        sent.forEach { left.remove(it) }
        write(left)
    }

    @Synchronized
    fun size(): Int = read().size

    private fun read(): List<AttemptRecord> {
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            .mapNotNull { line -> line.takeIf(String::isNotBlank)?.let(ClientReportJson::parseQueueLine) }
    }

    private fun write(records: List<AttemptRecord>) {
        if (records.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(records.joinToString(separator = "\n", postfix = "\n", transform = ClientReportJson::queueLine))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        /** Неделя частых переключений помещается; больше — уже не свидетельство «сейчас». */
        const val DEFAULT_CAPACITY = 500
    }
}

/**
 * Токен сети от `GET /v1/network`: приёмник подписал сеть, которую увидел у этой установки.
 * Он прикладывается к отчёту попытки, сделанной в той же сети, — и отчёт, отправленный позже
 * или через туннель, всё равно встаёт на свою сеть. Хранится вместе с ключом сети Android,
 * в которой получен; другая сеть — другой токен.
 */
data class NetworkToken(val value: String, val obtainedAtMillis: Long, val networkKey: String)

class NetworkTokenCache(private val file: File) {
    @Volatile
    private var cached: NetworkToken? = null

    @Volatile
    private var loaded = false

    /** Токен для попытки в сети [networkKey] сейчас, или пустая строка. */
    @Synchronized
    fun current(networkKey: String?, nowMillis: Long): String {
        val token = load() ?: return ""
        if (networkKey == null || token.networkKey != networkKey) return ""
        if (nowMillis - token.obtainedAtMillis !in 0 until USABLE_MILLIS) return ""
        return token.value
    }

    /** Нужен ли новый запрос: токена нет, он из другой сети или скоро истечёт. */
    @Synchronized
    fun needsRefresh(networkKey: String?, nowMillis: Long): Boolean {
        if (networkKey == null) return false
        val token = load() ?: return true
        return token.networkKey != networkKey || nowMillis - token.obtainedAtMillis !in 0 until REFRESH_AFTER_MILLIS
    }

    @Synchronized
    fun store(token: NetworkToken) {
        if (!ClientReportContract.isNetworkToken(token.value)) return
        cached = token
        loaded = true
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText("${token.obtainedAtMillis}\n${token.networkKey}\n${token.value}\n")
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun load(): NetworkToken? {
        if (loaded) return cached
        loaded = true
        cached = runCatching {
            val lines = file.readLines(Charsets.UTF_8)
            NetworkToken(lines[2], lines[0].toLong(), lines[1]).takeIf { ClientReportContract.isNetworkToken(it.value) }
        }.getOrNull()
        return cached
    }

    companion object {
        /** Приёмник верит токену сутки; к отчёту он идёт с запасом на очередь и часы. */
        const val USABLE_MILLIS = 22L * 60 * 60 * 1000

        /** Новый токен берётся заранее, чтобы следующая попытка не осталась без него. */
        const val REFRESH_AFTER_MILLIS = 12L * 60 * 60 * 1000
    }
}

/** Что делать с пакетом после ответа приёмника. */
enum class SendAction {
    /** Принят (202): удалить из очереди. */
    Remove,

    /** Отклонён контрактом (400, 413): повтор даст то же, удалить. */
    Drop,

    /** Лимит приёмника (429) или его сбой (5xx): оставить, до следующего повода больше не слать. */
    KeepAndStop,

    /** Ответа нет (сеть): оставить и попробовать другой путь. */
    TryOtherPath,
}

object ReportSendPolicy {
    /** [status] null — ответа не было (ошибка соединения или тайм-аут). */
    fun afterResponse(status: Int?): SendAction = when {
        status == null -> SendAction.TryOtherPath
        status == 202 || status == 200 -> SendAction.Remove
        status == 400 || status == 413 -> SendAction.Drop
        else -> SendAction.KeepAndStop
    }
}
