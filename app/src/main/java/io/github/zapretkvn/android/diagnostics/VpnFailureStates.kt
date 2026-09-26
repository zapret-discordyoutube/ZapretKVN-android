package io.github.zapretkvn.android.diagnostics

import io.github.zapretkvn.android.vpn.VpnConnectionState
import io.github.zapretkvn.networkbootstrap.CodedFailure

internal object VpnFailureStates {
    /**
     * Типизированный код ошибки приложения точнее текстового правила
     * runtime-errors.json: правила описывают сбои ядра, а в сообщении
     * health-check могут встретиться их слова («connection reset»,
     * «trust anchor»), и VPN-200 превратился бы в код Hysteria.
     */
    fun from(error: Throwable): VpnConnectionState.Error {
        val coded = generateSequence(error) { it.cause }
            .filterIsInstance<CodedFailure>()
            .firstOrNull()
        val message = RuntimeErrors.describe(error)
        return VpnConnectionState.Error(
            message = message,
            code = coded?.failureCode ?: RuntimeErrors.classify(message).orEmpty(),
            technicalDetail = coded?.technicalDetail?.let(SecretRedactor::redactInline),
        )
    }
}
