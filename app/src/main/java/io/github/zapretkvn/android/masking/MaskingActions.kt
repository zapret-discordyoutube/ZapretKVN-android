package io.github.zapretkvn.android.masking

import io.github.zapretkvn.masking.merge.MaskingSettings
import io.github.zapretkvn.masking.model.Masking
import io.github.zapretkvn.masking.model.MaskingOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Writes from the "Маскировка" screens. Masking changes the runtime copies of the servers, so a
 * connected VPN restarts with a reason shown in diagnostics, like the other tunnel settings.
 */
class MaskingActions(
    private val store: MaskingSettingsStore,
    private val scope: CoroutineScope,
    private val restartIfConnected: (reason: String) -> Unit,
) {
    val settings: Flow<MaskingSettings> get() = store.settings

    fun setEnabled(enabled: Boolean) = write { it.copy(enabled = enabled) }

    fun setGlobal(masking: Masking) = write { it.copy(global = masking) }

    /** [MaskingOverride.Inherit] removes the server's own choice. */
    fun setOverride(serverKey: String, override: MaskingOverride) = write { settings ->
        val overrides = if (override == MaskingOverride.Inherit) settings.overrides - serverKey else settings.overrides + (serverKey to override)
        settings.copy(overrides = overrides)
    }

    private fun write(transform: (MaskingSettings) -> MaskingSettings) {
        scope.launch {
            store.update(transform)
            restartIfConnected("Смена маскировки")
        }
    }

    companion object {
        fun summary(settings: MaskingSettings): String {
            if (!settings.enabled) return "Выключена"
            val parts = buildList {
                settings.global.wireguard?.takeIf { it.enabled }?.let { add("WireGuard") }
                settings.global.xray?.takeIf { it.enabled }?.let { xray ->
                    if (xray.mux?.enabled == true) add("mux")
                    if (xray.xmux != null) add("xmux")
                    if (xray.fragment?.enabled == true) add("фрагментация")
                    if (xray.desync?.enabled == true) add("пакеты-обманки")
                }
                settings.global.singbox?.takeIf { it.enabled }?.let { add("фрагментация TLS") }
            }
            return if (parts.isEmpty()) "Включена, ничего не выбрано" else "Включена: ${parts.joinToString(", ")}"
        }
    }
}
