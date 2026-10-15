package io.github.zapretkvn.android.network.probes

import io.github.zapretkvn.android.vpn.LatencyFailure
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.LatencySample

/** Последний результат одной пробы (Relay HTTPS через ядро). */
data class PersistedProbeResult(
    /** null — проба не удалась, см. [failure]. */
    val millis: Int?,
    val failure: LatencyFailure?,
    val measuredAtEpochMillis: Long,
    /** Последний удачный замер до неудачи — чтобы показать «Нет ответа · было 42 мс». */
    val previousMillis: Int? = null,
    val previousAtEpochMillis: Long? = null,
) {
    val failed: Boolean get() = millis == null

    fun toState(): LatencyProbeState {
        val previous = if (previousMillis != null && previousAtEpochMillis != null) {
            LatencySample(previousMillis, previousAtEpochMillis, networkIdentity = null)
        } else {
            null
        }
        return if (millis != null) {
            LatencyProbeState.Success(LatencySample(millis, measuredAtEpochMillis, networkIdentity = null))
        } else {
            LatencyProbeState.Failed(
                reason = failure ?: LatencyFailure.Failed,
                previous = previous,
                failedAtEpochMillis = measuredAtEpochMillis,
            )
        }
    }
}

/**
 * Пинг сервера, переживающий перезапуск приложения.
 *
 * [fingerprint] — хэш типа и адреса сервера (сам адрес не хранится): если
 * подписка сохранила тег, но сервер за ним поменялся, старый пинг выбрасывается.
 */
data class PersistedServerLatency(
    val fingerprint: String?,
    val relay: PersistedProbeResult? = null,
) {
    /** Что показать в списке серверов без подключения. */
    fun displayState(): LatencyProbeState? = relay?.toState()
}

/** profileId → tag → последний пинг. */
typealias ServerLatencyEntries = Map<String, Map<String, PersistedServerLatency>>
