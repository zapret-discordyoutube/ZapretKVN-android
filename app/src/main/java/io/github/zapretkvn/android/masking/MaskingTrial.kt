package io.github.zapretkvn.android.masking

import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.model.MaskingOverride
import io.github.zapretkvn.masking.model.ServerShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A server of the running session "Подбор" can work on: its runtime tag, stored key and shape. */
data class TunableServer(val tag: String, val key: String, val shape: ServerShape)

/**
 * The in-memory side of "Подбор": the one candidate the next session start runs with, and the
 * servers the running session has. Never stored: a crash or a process kill in the middle of a
 * run leaves the saved masking exactly as it was.
 */
class MaskingTrial {
    private data class Active(val serverKey: String, val override: MaskingOverride, val title: String)

    @Volatile
    private var active: Active? = null
    private val servers = MutableStateFlow<Map<String, TunableServer>>(emptyMap())

    /** Runtime tag -> server, published by every session start. */
    val sessionServers: StateFlow<Map<String, TunableServer>> = servers.asStateFlow()

    fun begin(serverKey: String, override: MaskingOverride, title: String) {
        active = Active(serverKey, override, title)
    }

    fun end() {
        active = null
    }

    /** The settings a session starts with: the stored ones, with the trial's server replaced. */
    fun applyTo(stored: MaskingSettings): MaskingSettings =
        active?.let { stored.withTrial(it.serverKey, it.override) } ?: stored

    /** The runtime-log line of a trial session, null otherwise. */
    fun logLine(): String? = active?.let { "маскировка: подбор, вариант «${it.title}»" }

    internal fun publish(sessionServers: Map<String, TunableServer>) {
        servers.value = sessionServers
    }
}
