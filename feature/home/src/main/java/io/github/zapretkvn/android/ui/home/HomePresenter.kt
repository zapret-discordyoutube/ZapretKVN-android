package io.github.zapretkvn.android.ui.home

import io.github.zapretkvn.android.apps.AppScopeMode
import io.github.zapretkvn.android.vpn.LatencyProbeState
import io.github.zapretkvn.android.vpn.PathStatus
import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.core.designsystem.Tone
import io.github.zapretkvn.core.uimodel.LatencyBand
import io.github.zapretkvn.core.uimodel.countLabel
import io.github.zapretkvn.core.uimodel.formatPing

/** Что сейчас главное на экране: от этого зависят заголовок, кнопка и карточки. */
enum class HomeStage {
    NoProfile,
    NeedsApps,
    Ready,
    Starting,
    Connected,
    Paused,
    Reconnecting,
    Stopping,
    Error,
}

/** Главное действие героя-кнопки. */
enum class HomeAction(val label: String) {
    AddProfile("Добавить профиль"),
    SelectApps("Выбрать приложения"),
    SelectDirectApps("Выбрать приложения напрямую"),
    Connect("Подключить"),
    Stop("Остановить"),
    Disconnect("Отключить"),
    ResumeNow("Подключить сейчас"),
    Restart("Перезапустить"),
    None(""),
}

data class HomeHeadline(val title: String, val hint: String?)

data class HomeInput(
    val vpnState: VpnConnectionState,
    val hasProfile: Boolean,
    val selectedAppCount: Int,
    val blockedAppCount: Int,
    val scopeMode: AppScopeMode,
    /** Короткое имя текущего сервера без флага и протокола, если известно. */
    val serverTitle: String?,
    /** Состояние пути поверх «Подключено»: проверка, замена сервера или «нет связи». */
    val pathStatus: PathStatus = PathStatus.Ok,
)

/** Состояние пути действует только поверх «Подключено». */
fun HomeInput.effectivePath(): PathStatus =
    if (vpnState is VpnConnectionState.Connected) pathStatus else PathStatus.Ok

/** Подпись и заголовок «Нет связи с сервером» показываются только поверх состояния «Подключено». */
fun HomeInput.isLinkLost(): Boolean = effectivePath() == PathStatus.Lost

const val LINK_LOST_LABEL = "Нет связи с сервером"
const val LINK_LOST_CHANGE_SERVER = "Сменить сервер"
const val PATH_CHECKING_LABEL = "Проверяем связь"
const val PATH_SWITCHING_LABEL = "Переключение сервера"

fun homeStage(input: HomeInput): HomeStage {
    val state = input.vpnState
    val idle = state == VpnConnectionState.Stopped || state is VpnConnectionState.Error
    val noApps = input.selectedAppCount == 0 &&
        (input.scopeMode == AppScopeMode.Exclude || input.blockedAppCount == 0)
    return when {
        !input.hasProfile && idle -> HomeStage.NoProfile
        noApps && idle -> HomeStage.NeedsApps
        else -> when (state) {
            VpnConnectionState.Stopped -> HomeStage.Ready
            is VpnConnectionState.Starting -> HomeStage.Starting
            is VpnConnectionState.Connected -> HomeStage.Connected
            is VpnConnectionState.Paused -> HomeStage.Paused
            is VpnConnectionState.Reconnecting -> HomeStage.Reconnecting
            is VpnConnectionState.Stopping -> HomeStage.Stopping
            is VpnConnectionState.Error -> HomeStage.Error
        }
    }
}

/** Короткий статус для чипа. Эти слова ищут тесты и TalkBack — менять осторожно. */
fun statusLabel(state: VpnConnectionState, path: PathStatus = PathStatus.Ok): String = when {
    state !is VpnConnectionState.Connected -> baseStatusLabel(state)
    path == PathStatus.Lost -> LINK_LOST_LABEL
    path == PathStatus.Switching -> PATH_SWITCHING_LABEL
    path == PathStatus.Checking -> PATH_CHECKING_LABEL
    else -> baseStatusLabel(state)
}

private fun baseStatusLabel(state: VpnConnectionState): String = when (state) {
    VpnConnectionState.Stopped -> "VPN выключен"
    is VpnConnectionState.Starting -> "Подключение"
    is VpnConnectionState.Connected -> "VPN подключён"
    is VpnConnectionState.Paused -> "VPN на паузе"
    is VpnConnectionState.Stopping -> "Отключение"
    is VpnConnectionState.Reconnecting -> "Переподключение"
    is VpnConnectionState.Error -> "Ошибка VPN"
}

fun statusTone(state: VpnConnectionState, path: PathStatus = PathStatus.Ok): Tone = when {
    state !is VpnConnectionState.Connected -> baseStatusTone(state)
    path == PathStatus.Lost -> Tone.Warning
    path == PathStatus.Switching || path == PathStatus.Checking -> Tone.Neutral
    else -> baseStatusTone(state)
}

private fun baseStatusTone(state: VpnConnectionState): Tone = when (state) {
    is VpnConnectionState.Connected -> Tone.Good
    is VpnConnectionState.Error -> Tone.Bad
    is VpnConnectionState.Starting,
    is VpnConnectionState.Reconnecting,
    is VpnConnectionState.Stopping,
    -> Tone.Accent
    is VpnConnectionState.Paused -> Tone.Warning
    VpnConnectionState.Stopped -> Tone.Neutral
}

/**
 * Заголовок говорит, что происходит или что делать дальше, а не повторяет
 * статус. Детерминирован от состояния: одинаковое состояние — одинаковый текст.
 */
fun homeHeadline(stage: HomeStage, input: HomeInput): HomeHeadline {
    val server = input.serverTitle?.takeIf(String::isNotBlank)
    return when (stage) {
        HomeStage.NoProfile -> HomeHeadline(
            "Начнём с профиля",
            "Добавьте подписку, файл или ссылку от вашего VPN — серверы появятся сами.",
        )
        HomeStage.NeedsApps -> if (input.scopeMode == AppScopeMode.Include) {
            HomeHeadline(
                "Выберите, что пускать через VPN",
                "Выберите хотя бы одно приложение для VPN.",
            )
        } else {
            HomeHeadline(
                "Выберите, что пускать напрямую",
                "Выберите хотя бы одно приложение для прямого доступа вне VPN.",
            )
        }
        HomeStage.Ready -> HomeHeadline(
            "Готово к подключению",
            server?.let { "Сервер: $it. Нажмите, чтобы обойти блокировки." }
                ?: "Нажмите, чтобы обойти блокировки.",
        )
        HomeStage.Starting -> HomeHeadline(
            "Подключаемся…",
            (input.vpnState as? VpnConnectionState.Starting)?.message,
        )
        HomeStage.Connected -> when (input.effectivePath()) {
            PathStatus.Lost -> HomeHeadline(
                LINK_LOST_LABEL,
                "VPN подключён, но данные не идут. Перезапустите VPN или смените сервер.",
            )
            PathStatus.Switching -> HomeHeadline(
                "Переключаемся на другой сервер",
                "Проверяем новый сервер; соединения приложений могут ненадолго прерваться.",
            )
            PathStatus.Checking -> HomeHeadline(
                "Проверяем связь",
                "Похоже, данные перестали идти — проверяем сервер.",
            )
            PathStatus.Ok -> HomeHeadline(
                "Всё работает",
                buildString {
                    append(server?.let { "Вы в сети через $it. " } ?: "Вы в сети через VPN. ")
                    append(appsSentence(input))
                },
            )
        }
        HomeStage.Paused -> HomeHeadline(
            "VPN на паузе",
            (input.vpnState as? VpnConnectionState.Paused)?.message,
        )
        HomeStage.Reconnecting -> {
            val state = input.vpnState as VpnConnectionState.Reconnecting
            HomeHeadline(
                "Восстанавливаем связь",
                "${state.message} · попытка ${state.attempt} из ${state.maxAttempts}",
            )
        }
        HomeStage.Stopping -> HomeHeadline("Отключаемся…", "Закрываем соединение.")
        HomeStage.Error -> HomeHeadline("Не получилось подключиться", null)
    }
}

private fun appsSentence(input: HomeInput): String = when (input.scopeMode) {
    AppScopeMode.Include -> if (input.selectedAppCount == 0) {
        "Приложения для VPN не выбраны."
    } else {
        "Через VPN: ${countLabel(input.selectedAppCount, "приложение", "приложения", "приложений")}."
    }
    AppScopeMode.Exclude -> "Через VPN идут все приложения, кроме выбранных."
}

/** Главное и второстепенное действие для текущего этапа. */
fun homeActions(stage: HomeStage, scopeMode: AppScopeMode): Pair<HomeAction, HomeAction> =
    when (stage) {
        HomeStage.NoProfile -> HomeAction.AddProfile to HomeAction.None
        HomeStage.NeedsApps -> if (scopeMode == AppScopeMode.Include) {
            HomeAction.SelectApps to HomeAction.None
        } else {
            HomeAction.SelectDirectApps to HomeAction.None
        }
        HomeStage.Ready, HomeStage.Error -> HomeAction.Connect to HomeAction.None
        HomeStage.Starting, HomeStage.Reconnecting -> HomeAction.Stop to HomeAction.None
        HomeStage.Stopping -> HomeAction.Stop to HomeAction.None
        HomeStage.Paused -> HomeAction.ResumeNow to HomeAction.Stop
        HomeStage.Connected -> HomeAction.Disconnect to HomeAction.Restart
    }

/** Подпись под карточкой приложений: «Через VPN: 12 · заблокировано: 3». */
fun appsSummary(scopeMode: AppScopeMode, selected: Int, blocked: Int): String =
    if (scopeMode == AppScopeMode.Include) {
        "Через VPN: $selected · заблокировано: $blocked"
    } else {
        "Напрямую вне VPN: $selected · заблокировано: $blocked"
    }

/** Задержка числом и словом: «84 мс · быстро». Цвет только усиливает слово. */
data class LatencyBadgeUi(val text: String, val tone: Tone)

fun latencyBadge(state: LatencyProbeState?): LatencyBadgeUi? = when (state) {
    null, LatencyProbeState.NotTested, is LatencyProbeState.Unsupported -> null
    is LatencyProbeState.Running -> LatencyBadgeUi("проверяем…", Tone.Neutral)
    is LatencyProbeState.Success -> {
        val band = LatencyBand.of(state.sample.millis)
        LatencyBadgeUi("${formatPing(state.sample.millis.toLong())} · ${band.word}", band.tone())
    }
    is LatencyProbeState.Stale -> LatencyBadgeUi("${formatPing(state.sample.millis.toLong())} · давно", Tone.Neutral)
    is LatencyProbeState.Failed -> LatencyBadgeUi("не отвечает", Tone.Bad)
}

fun LatencyBand.tone(): Tone = when (this) {
    LatencyBand.Fast -> Tone.Good
    LatencyBand.Normal -> Tone.Warning
    LatencyBand.Slow -> Tone.Bad
}

/** Тип группы человеческими словами; Selector/URLTest в интерфейс не попадают. */
fun groupModeLabel(type: String): String? = when (type.lowercase()) {
    "urltest" -> "Авто · самый быстрый"
    "fallback" -> "Резервные"
    "selector" -> "Ручной выбор"
    else -> null
}
