package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.diagnostics.RuntimeFailure
import io.github.zapretkvn.android.diagnostics.VpnErrorContext
import io.github.zapretkvn.android.diagnostics.VpnErrorExplainer
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import kotlinx.coroutines.CancellationException

/**
 * Транзакция выбора сервера при живом подключении — тот же контракт, что у
 * Windows (`server_selection.py`): выбранным (профиль и опубликованный выбор)
 * сервер становится только после того, как доказал, что обслуживает.
 *
 * Раньше ручной выбор закреплялся до проверки, а проверка только
 * предупреждала. Сервер, до которого из сети пользователя вообще не доходит
 * UDP (31.10.2026, Hysteria2 из МГТС), оставался выбранным, сохранённым и
 * обслуживающим: связь пропадала, хотя прежний сервер работал.
 *
 * Провал проверки разбирает [StartupFallbackPolicy.deadServerCode], тот же,
 * что при старте:
 * - сервер мёртв (через него не пришло ни ответа, или ядро назвало отказ
 *   именно этого сервера) — проверка бросает [DeadServerException],
 *   [ServerSwitcher] возвращает прежний сервер, профиль не меняется;
 * - причина не в сервере (режим DNS, потеря сети, неясно) — выбор
 *   пользователя остаётся, приходит предупреждение: разовая неудача проверки
 *   не должна «не нажимать» выбор.
 */
internal class SelectionTransaction<S>(
    private val outboundTag: String,
    private val verify: suspend (S, String) -> Unit,
    private val evidence: (S) -> RuntimeFailure?,
    private val plan: (S, String) -> FailoverPlan?,
) {
    /** Проверка не прошла, но не по вине сервера: выбор оставлен. */
    var warning: Throwable? = null
        private set

    /** Проверка кандидата для [ServerSwitcher]: бросает, только если сервер мёртв. */
    suspend fun check(session: S, candidateJson: String) {
        try {
            verify(session, candidateJson)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val target = plan(session, outboundTag)
            val dead = StartupFallbackPolicy.deadServerCode(error, evidence(session), target)
            if (dead != null) throw DeadServerException(dead, target?.currentType, error)
            warning = error
        }
    }
}

/** Выбранный сервер не обслуживает: прежний возвращён, причина — для сообщения. */
internal class DeadServerException(
    val code: HysteriaFailureCode,
    val protocol: String?,
    cause: Throwable,
) : IllegalStateException(cause.message, cause) {
    /** То же объяснение, что на карточке ошибки: для UDP — «UDP не доходит, выберите VLESS или Trojan». */
    fun explanation(): String =
        VpnErrorExplainer.explain(code.name, context = VpnErrorContext(protocol = protocol, hasOtherServers = true)).hint
}
