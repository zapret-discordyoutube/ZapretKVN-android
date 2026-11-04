package io.github.zapretkvn.android.ui

import io.github.zapretkvn.android.vpn.VpnConnectionState
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Внешнее управление VPN (Tasker и подобные). Выключено, пока владелец не включил:
 * приёмник в манифесте отключён, поэтому снаружи его не видно и не вызвать.
 */
data class ExternalControlSettings(
    val enabled: Boolean = false,
    /** Секрет, который нужен в каждом вызове; пуст, пока управление ни разу не включали. */
    val token: String = "",
)

/** Что приёмник должен сделать с VPN в ответ на вызов. */
enum class ExternalCommand { Start, Stop, Resume, Nothing }

object ExternalControl {
    const val ACTION_START = "io.github.zapretkvn.android.control.START"
    const val ACTION_STOP = "io.github.zapretkvn.android.control.STOP"
    const val ACTION_TOGGLE = "io.github.zapretkvn.android.control.TOGGLE"
    const val EXTRA_TOKEN = "token"

    val ACTIONS = setOf(ACTION_START, ACTION_STOP, ACTION_TOGGLE)

    private const val TOKEN_BYTES = 24

    /** Новый случайный секрет: 24 байта из [SecureRandom], 48 шестнадцатеричных знаков. */
    fun newToken(random: SecureRandom = SecureRandom()): String =
        ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * Вызов принимается, только если управление включено, секрет задан и совпал.
     * Сравнение не зависит от того, в каком знаке расходятся строки.
     */
    fun accepts(settings: ExternalControlSettings, supplied: String?): Boolean {
        if (!settings.enabled || settings.token.isEmpty() || supplied.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            settings.token.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8),
        )
    }

    /** Те же правила, что у плитки быстрых настроек: идёт запуск или работа — стоп, пауза — возобновить. */
    fun commandFor(action: String, state: VpnConnectionState): ExternalCommand = when (action) {
        ACTION_START -> when (state) {
            VpnConnectionState.Stopped, is VpnConnectionState.Error -> ExternalCommand.Start
            is VpnConnectionState.Paused -> ExternalCommand.Resume
            else -> ExternalCommand.Nothing
        }
        ACTION_STOP -> when (state) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Starting,
            is VpnConnectionState.Reconnecting,
            is VpnConnectionState.Paused,
            -> ExternalCommand.Stop
            else -> ExternalCommand.Nothing
        }
        ACTION_TOGGLE -> when (state) {
            is VpnConnectionState.Connected,
            is VpnConnectionState.Starting,
            is VpnConnectionState.Reconnecting,
            -> ExternalCommand.Stop
            is VpnConnectionState.Paused -> ExternalCommand.Resume
            is VpnConnectionState.Stopping -> ExternalCommand.Nothing
            VpnConnectionState.Stopped, is VpnConnectionState.Error -> ExternalCommand.Start
        }
        else -> ExternalCommand.Nothing
    }
}
