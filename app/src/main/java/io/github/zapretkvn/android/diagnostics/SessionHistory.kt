package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.android.vpn.sanitizeDiagnosticText

/** Ключевое событие сессии; повторы одного и того же текста схлопываются в [repeat]. */
internal data class SessionKeyEvent(
    val atEpochMillis: Long,
    val lastAtEpochMillis: Long,
    val text: String,
    val repeat: Int = 1,
    /** Ошибки, предупреждения и решения автоматики вытесняются из кольца последними. */
    val important: Boolean = true,
)

/** Снимок записи для экспорта. Адресов серверов здесь нет по построению. */
internal data class SessionRecordSnapshot(
    val generation: Long,
    val trigger: String,
    val profileRef: String?,
    val serverTag: String?,
    val serverRef: String?,
    val protocol: String?,
    val startedAtEpochMillis: Long,
    val connectedAtEpochMillis: Long?,
    val endedAtEpochMillis: Long?,
    val endReason: String?,
    val uplinkBytes: Long,
    val downlinkBytes: Long,
    val peakDownlinkBytesPerSecond: Long,
    val lastDownlinkAtEpochMillis: Long?,
    /** Секунд от последнего роста downlink до [SessionHistory.snapshot]; null, если роста не было. */
    val secondsSinceLastDownlink: Long?,
    val lastSampleAtEpochMillis: Long?,
    val secondsSinceLastSample: Long?,
    val sampleCount: Int,
    val events: List<SessionKeyEvent>,
    val eventsDropped: Int,
) {
    val active: Boolean get() = endedAtEpochMillis == null
}

/**
 * Последние [MAX_SESSIONS] сессий в памяти процесса: когда начались и кончились,
 * чем кончились, сколько трафика прошло и когда в последний раз рос downlink,
 * плюс кольцо из [MAX_EVENTS] ключевых событий каждой. На диск ничего не пишется
 * (`privacy.runtime_log_persisted=false`), а в тексты событий адреса серверов
 * попадают только уже замаскированными.
 *
 * Открывается при начале попытки подключения, закрывается на Stopped / Error /
 * Reconnecting / Paused / Stopping, при остановке или при открытии следующей
 * сессии. Потокобезопасен: события приходят из потоков libbox и из актора.
 */
internal class SessionHistory(
    private val maxSessions: Int = MAX_SESSIONS,
    private val maxEvents: Int = MAX_EVENTS,
    private val epochClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private class Record(
        val generation: Long,
        val trigger: String,
        val startedAtEpochMillis: Long,
    ) {
        var profileRef: String? = null
        var serverTag: String? = null
        var serverRef: String? = null
        var protocol: String? = null
        var connectedAtEpochMillis: Long? = null
        var endedAtEpochMillis: Long? = null
        var endReason: String? = null
        var uplink = 0L
        var downlink = 0L
        var peakDownlinkPerSecond = 0L
        var lastDownlinkAtEpochMillis: Long? = null
        var lastDownlinkElapsed: Long? = null
        var lastSampleAtEpochMillis: Long? = null
        var lastSampleElapsed: Long? = null
        var sampleCount = 0
        val events = ArrayList<SessionKeyEvent>()
        val eventKeys = ArrayList<String>()
        var eventsDropped = 0
    }

    private val lock = Any()
    private val records = ArrayList<Record>()

    /** Начало попытки подключения: прежняя открытая сессия закрывается по триггеру новой. */
    fun open(generation: Long, trigger: String, profileRef: String?) {
        synchronized(lock) {
            if (records.lastOrNull()?.generation == generation) return
            closeActiveLocked(endReasonForReplacement(trigger))
            val record = Record(generation, trigger.take(40), epochClock())
            record.profileRef = profileRef
            records += record
            trimLocked()
        }
    }

    /** Цель сессии: тег сервера и протокол. Тег маскируется как любой текст отчёта. */
    fun updateTarget(generation: Long, target: DiagnosticTargetContext) {
        synchronized(lock) {
            val record = recordLocked(generation) ?: return
            target.profileRef?.let { record.profileRef = it }
            target.outboundTag?.let { record.serverTag = safeText(it, 80) }
            target.endpoint?.let { record.serverRef = safeText(it, 80) }
            target.protocol?.let { record.protocol = safeText(it, 24) }
        }
    }

    fun markConnected(generation: Long, connectedAtEpochMillis: Long) {
        synchronized(lock) {
            val record = recordLocked(generation) ?: return
            if (record.connectedAtEpochMillis == null) record.connectedAtEpochMillis = connectedAtEpochMillis
        }
    }

    /** Отсчёт счётчиков ядра. Счётчики накопительные; откат (новое ядро) учитывается как обнуление. */
    fun recordTraffic(
        generation: Long,
        elapsedRealtimeMillis: Long,
        uplinkTotal: Long,
        downlinkTotal: Long,
    ) {
        synchronized(lock) {
            val record = records.lastOrNull { it.generation == generation && it.endedAtEpochMillis == null }
                ?: return
            val now = epochClock()
            val previousElapsed = record.lastSampleElapsed
            val previousDown = record.downlink
            val up = uplinkTotal.coerceAtLeast(0L)
            val down = downlinkTotal.coerceAtLeast(0L)
            if (previousElapsed != null && down >= previousDown) {
                val dt = elapsedRealtimeMillis - previousElapsed
                if (dt in 1..MAX_PEAK_GAP_MILLIS) {
                    val perSecond = (down - previousDown) * 1000L / dt
                    if (perSecond > record.peakDownlinkPerSecond) record.peakDownlinkPerSecond = perSecond
                }
            }
            if (down > previousDown || (previousElapsed == null && down > 0L)) {
                record.lastDownlinkAtEpochMillis = now
                record.lastDownlinkElapsed = elapsedRealtimeMillis
            }
            record.uplink = up
            record.downlink = down
            record.lastSampleAtEpochMillis = now
            record.lastSampleElapsed = elapsedRealtimeMillis
            record.sampleCount += 1
        }
    }

    /**
     * Публичная точка для детектора и автоматики: событие в кольцо сессии
     * [generation] (если такой нет - в самую свежую). Текст маскируется.
     */
    fun recordKeyEvent(generation: Long, text: String, important: Boolean = true) {
        val safe = safeText(text, MAX_EVENT_CHARS)
        if (safe.isEmpty()) return
        synchronized(lock) {
            val record = recordLocked(generation) ?: records.lastOrNull() ?: return
            addEventLocked(record, safe, important)
        }
    }

    /** Строка core-лога: в кольцо попадают только важные (уровень <= WARN и маркеры обрыва). */
    fun recordCoreLine(generation: Long, level: Int, message: String) {
        if (!isKeyCoreLine(level, message)) return
        val label = when (level) {
            0 -> "PANIC"
            1 -> "FATAL"
            2 -> "ERROR"
            3 -> "WARN"
            4 -> "NOTICE"
            else -> "INFO"
        }
        recordKeyEvent(generation, "core $label: $message", important = level <= 3)
    }

    /** Состояние VPN: закрывает сессию при Stopped / Error / Reconnecting / Paused / Stopping. */
    fun onState(generation: Long, state: VpnConnectionState) {
        synchronized(lock) {
            when (state) {
                is VpnConnectionState.Connected -> {
                    recordLocked(generation)?.let {
                        if (it.connectedAtEpochMillis == null) it.connectedAtEpochMillis = state.connectedAtEpochMillis
                    }
                }
                is VpnConnectionState.Error ->
                    closeOrRefineLocked("error:${state.code.ifBlank { "unknown" }}")
                is VpnConnectionState.Reconnecting ->
                    closeActiveLocked("error:${state.code.ifBlank { "unknown" }}")
                is VpnConnectionState.Paused -> closeActiveLocked("paused")
                is VpnConnectionState.Stopping -> closeActiveLocked("stopped")
                VpnConnectionState.Stopped -> closeActiveLocked("stopped")
                is VpnConnectionState.Starting -> Unit
            }
        }
    }

    /** Остановка по команде: причина известна раньше, чем придёт Stopping/Stopped. */
    fun onStopRequested(trigger: String) {
        synchronized(lock) {
            closeActiveLocked(if (trigger == "user_stop") "user_stop" else "stop:${trigger.take(40)}")
        }
    }

    /** Все сессии, от старой к новой. */
    fun snapshot(): List<SessionRecordSnapshot> {
        val elapsedNow = elapsedClock()
        return synchronized(lock) { records.map { it.toSnapshot(elapsedNow) } }
    }

    /** Открытая сессия или null. */
    fun current(): SessionRecordSnapshot? = snapshot().lastOrNull { it.active }

    // ---- внутреннее ----

    private fun recordLocked(generation: Long): Record? =
        records.lastOrNull { it.generation == generation }

    private fun closeActiveLocked(reason: String) {
        val record = records.lastOrNull() ?: return
        if (record.endedAtEpochMillis != null) return
        record.endedAtEpochMillis = epochClock()
        record.endReason = reason
    }

    /** Error уточняет причину после остановки по политике: код ошибки полезнее, чем `stop:policy_stop`. */
    private fun closeOrRefineLocked(reason: String) {
        val record = records.lastOrNull() ?: return
        if (record.endedAtEpochMillis == null) {
            closeActiveLocked(reason)
        } else if (record.endReason?.startsWith("stop:") == true || record.endReason == "stopped") {
            record.endReason = reason
        }
    }

    private fun addEventLocked(record: Record, text: String, important: Boolean) {
        val now = epochClock()
        val key = normalizeKey(text)
        val existing = record.eventKeys.indexOf(key)
        if (existing >= 0) {
            val event = record.events.removeAt(existing)
            record.eventKeys.removeAt(existing)
            record.events += event.copy(
                lastAtEpochMillis = now,
                repeat = event.repeat + 1,
                important = event.important || important,
            )
            record.eventKeys += key
            return
        }
        if (record.events.size >= maxEvents) {
            val victim = record.events.indexOfFirst { !it.important }.takeIf { it >= 0 } ?: 0
            record.events.removeAt(victim)
            record.eventKeys.removeAt(victim)
            record.eventsDropped += 1
        }
        record.events += SessionKeyEvent(now, now, text, 1, important)
        record.eventKeys += key
    }

    private fun trimLocked() {
        while (records.size > maxSessions) {
            // Повторные неудачные попытки не должны вытеснять сессию, которая была подключена.
            val newestIndex = records.lastIndex
            val victim = records.indices
                .firstOrNull { it != newestIndex && records[it].connectedAtEpochMillis == null && records[it].endedAtEpochMillis != null }
                ?: 0
            records.removeAt(victim)
        }
    }

    private fun Record.toSnapshot(elapsedNow: Long) = SessionRecordSnapshot(
        generation = generation,
        trigger = trigger,
        profileRef = profileRef,
        serverTag = serverTag,
        serverRef = serverRef,
        protocol = protocol,
        startedAtEpochMillis = startedAtEpochMillis,
        connectedAtEpochMillis = connectedAtEpochMillis,
        endedAtEpochMillis = endedAtEpochMillis,
        endReason = endReason,
        uplinkBytes = uplink,
        downlinkBytes = downlink,
        peakDownlinkBytesPerSecond = peakDownlinkPerSecond,
        lastDownlinkAtEpochMillis = lastDownlinkAtEpochMillis,
        secondsSinceLastDownlink = lastDownlinkElapsed?.let { ((elapsedNow - it) / 1000L).coerceAtLeast(0L) },
        lastSampleAtEpochMillis = lastSampleAtEpochMillis,
        secondsSinceLastSample = lastSampleElapsed?.let { ((elapsedNow - it) / 1000L).coerceAtLeast(0L) },
        sampleCount = sampleCount,
        events = events.toList(),
        eventsDropped = eventsDropped,
    )

    companion object {
        const val MAX_SESSIONS = 5
        const val MAX_EVENTS = 24
        const val MAX_EVENT_CHARS = 200
        private const val MAX_PEAK_GAP_MILLIS = 30_000L

        /** Строки ядра, которые стоит помнить, даже когда экран диагностики закрыт. */
        private val KEY_MARKERS = listOf(
            "reconnect",
            "closed",
            "dial",
            "handshake",
            "quic",
            "no recent network activity",
            "i/o timeout",
            "exchange failed",
        )

        fun isKeyCoreLine(level: Int, message: String): Boolean = when {
            level <= 3 -> true
            level > 5 -> false
            else -> {
                val lower = message.lowercase()
                KEY_MARKERS.any(lower::contains)
            }
        }

        /** Причина конца сессии, когда её заменяет попытка с триггером [trigger]. */
        fun endReasonForReplacement(trigger: String): String = when {
            trigger == "network_change" -> "network_change"
            trigger.startsWith("server_") -> "server_switch"
            else -> "restart:${trigger.take(40)}"
        }

        private val DIGITS = Regex("[0-9]+")

        /** Ключ схлопывания: числа, порты и счётчики не делают строки «разными». */
        private fun normalizeKey(text: String): String = text.lowercase().replace(DIGITS, "#").take(120)

        private fun safeText(text: String, maxChars: Int): String =
            sanitizeDiagnosticText(text, maxChars).take(maxChars)
    }
}
