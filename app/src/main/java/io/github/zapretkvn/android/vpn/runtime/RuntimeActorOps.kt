package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.diagnostics.SecretRedactor
import io.github.zapretkvn.android.diagnostics.ServerAddressRedactor
import io.github.zapretkvn.android.vpn.VpnController

internal enum class RuntimePhase { Idle, Starting, Running, Recovering, Paused, Stopping }

/**
 * Что координаторы политик могут потребовать от актора [VpnRuntime]. Все вызовы
 * выполняются только внутри актора (из обработчика сообщения или из блока,
 * поставленного через `postToActor`), поэтому состояние рантайма здесь не
 * нуждается в блокировках.
 */
internal interface RuntimeActorOps {
    var phase: RuntimePhase

    /** Идёт остановка, начатая пользователем или политикой: новые попытки не нужны. */
    val stopInProgress: Boolean

    /** Перезапуск тем же путём, что и команда `Restart` в очереди актора. */
    fun restart(
        profileId: String,
        reason: String,
        startId: Int,
        updaterRouting: Boolean?,
        expectedGeneration: Long? = null,
        noCacheLookup: Boolean = false,
        resetRecovery: Boolean = true,
    )
}

/** Решения автоматики — в журнал диагностики, с замаскированными адресами. */
internal class RuntimeTimeline(private val controller: VpnController) {
    fun note(message: String) {
        controller.publishDiagnosticInfo(ServerAddressRedactor.redact(SecretRedactor.redactInline("Путь: $message")))
    }

    fun server(tag: String): String = ServerAddressRedactor.redact(SecretRedactor.redactInline(tag))
}
