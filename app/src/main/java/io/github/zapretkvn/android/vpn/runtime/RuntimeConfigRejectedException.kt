package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.networkbootstrap.CodedFailure

/** Профиль отвергнут правилом сборки runtime-конфига с типизированным кодом (например CFG-120). */
internal class RuntimeConfigRejectedException(
    message: String,
    override val failureCode: String,
) : IllegalStateException(message), CodedFailure {
    override val userMessage: String = message
    override val technicalDetail: String? = null
}
