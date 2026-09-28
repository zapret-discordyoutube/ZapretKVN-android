package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.config.DnsMode
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.network.probes.VpnDnsHealthException
import io.github.zapretkvn.networkbootstrap.CodedFailure

internal object AutomaticDnsFallbackPolicy {
    /**
     * При strict Private DNS автоматический режим сужается до «DNS Android»:
     * это единственный кандидат, уважающий системный DoT. Профильный DNS и
     * managed DNS узла подменяли бы выбранный пользователем резолвер, а fail-close
     * заставлял пользователя чинить настройки вручную.
     */
    fun candidates(
        configuredMode: DnsMode,
        hasProfileDns: Boolean,
        strictPrivateDns: Boolean = false,
    ): List<DnsMode> = when {
        configuredMode != DnsMode.Automatic -> listOf(configuredMode)
        strictPrivateDns -> listOf(DnsMode.Android)
        else -> buildList {
            if (hasProfileDns) add(DnsMode.FromJson)
            add(DnsMode.Secure)
            add(DnsMode.Android)
        }
    }

    fun label(mode: DnsMode): String = when (mode) {
        DnsMode.FromJson -> "DNS профиля"
        DnsMode.Android -> "DNS Android"
        DnsMode.Secure -> "DNS узла"
        DnsMode.Automatic -> "автоматический DNS"
    }

    /**
     * Переходит к следующему кандидату только после отказа DNS. Отказ ищется по
     * всей цепочке причин: запуск оборачивает ошибку в свидетельство ядра
     * (RuntimeStartupFailure), и прямой `catch` по типу его не видел —
     * автоматический fallback молча не срабатывал.
     */
    suspend fun <T> run(
        candidates: List<DnsMode>,
        onFallback: (from: DnsMode, to: DnsMode, failure: VpnDnsHealthException) -> Unit,
        attempt: suspend (DnsMode) -> T,
    ): T {
        require(candidates.isNotEmpty())
        for ((index, candidate) in candidates.withIndex()) {
            try {
                return attempt(candidate)
            } catch (error: Throwable) {
                val dnsFailure = error.causes().filterIsInstance<VpnDnsHealthException>().firstOrNull()
                if (dnsFailure == null || index == candidates.lastIndex) throw error
                onFallback(candidate, candidates[index + 1], dnsFailure)
            }
        }
        error("DNS fallback завершился без результата.")
    }
}

internal fun Throwable.causes(): Sequence<Throwable> {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    return generateSequence(this) { it.cause }.takeWhile(seen::add)
}

/** Ошибка несёт собственный код поддержки: текстовая классификация его не заменяет. */
internal fun Throwable.hasTypedFailureCode(): Boolean = causes().any { it is CodedFailure }

/**
 * Отдельный код нужен восстановлению: авторизация в Wi-Fi — действие
 * пользователя, но как только Android снимет флаг captive portal, попытку
 * можно повторить автоматически.
 */
internal class CaptivePortalException : IllegalStateException(
    "Интернет требует авторизации в Wi-Fi.",
), CodedFailure {
    override val failureCode = "NET-110"
    override val userMessage = checkNotNull(message)
    override val technicalDetail = "captive_portal=true"
}

internal class StrictPrivateDnsException(message: String) : IllegalStateException(message), CodedFailure {
    override val failureCode = "DNS-110"
    override val userMessage = message
    override val technicalDetail = "private_dns=strict"
}

internal class ConnectionStartupTimeoutException(timeoutMillis: Long) : Exception(
    "Подключение не завершилось за ${timeoutMillis / 1_000} секунд. " +
        "VPN полностью остановлен; повторите после стабилизации сети.",
), CodedFailure {
    override val failureCode = "VPN-120"
    override val userMessage = checkNotNull(message)
    override val technicalDetail = "timeout_ms=$timeoutMillis"
}

internal class RuntimeErrorObserverUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException(cause?.message ?: "CommandLog did not connect", cause), CodedFailure {
    override val failureCode = HysteriaFailureCode.LOCAL_CONTROL_PLANE_UNAVAILABLE.name
    override val userMessage = checkNotNull(message)
    override val technicalDetail = "runtime_failure=$failureCode"
}

/** Команда селектора не прошла в ядро: состояние ядра неизвестно. */
internal class RuntimeSwitchException(cause: Throwable) : Exception(cause.message, cause)
