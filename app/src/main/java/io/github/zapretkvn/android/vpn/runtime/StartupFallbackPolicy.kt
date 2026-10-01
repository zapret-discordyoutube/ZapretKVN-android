package io.github.zapretkvn.android.vpn.runtime

import io.github.zapretkvn.android.diagnostics.RuntimeFailure
import io.github.zapretkvn.android.diagnostics.ServerAddressRedactor
import io.github.zapretkvn.android.engines.hysteria.HysteriaFailureCode
import io.github.zapretkvn.android.network.VpnNetworkLostException
import io.github.zapretkvn.android.network.probes.VpnDnsHealthException
import io.github.zapretkvn.android.network.probes.VpnHealthTimeoutException
import io.github.zapretkvn.networkbootstrap.CodedFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Решает, означает ли провал стартовой проверки, что выбранный сервер мёртв.
 *
 * Стартовая проверка DNS/HTTPS идёт через уже работающее ядро и выбранный
 * сервер — это та же проба, которой [PathSupervisor] подтверждает отказ в
 * работающей сессии. Поэтому её провал обрабатывается тем же способом: командой
 * селектора на кандидата, без перезапуска ядра и без угадывания по тексту.
 *
 * - Трафик не прошёл (VPN-200 или дедлайн проверки): сервер считается мёртвым.
 * - DNS через VPN не ответил: это может быть и режим DNS, поэтому сервер
 *   виноват, только если ядро само назвало его отказ; иначе решает
 *   [AutomaticDnsFallbackPolicy].
 * - Hysteria с отказом TLS/авторизации/pin не заменяется: как и в работающей
 *   сессии, это терминальная ошибка ключа, а не недоступность сервера.
 * - Потеря VPN-сети Android и остальные коды — не про сервер.
 */
internal object StartupFallbackPolicy {
    private const val TRAFFIC_PROBE_CODE = "VPN-200"

    /** @return код отказа выбранного сервера или null, если отказ не про сервер. */
    fun deadServerCode(error: Throwable, evidence: RuntimeFailure?, plan: FailoverPlan?): HysteriaFailureCode? {
        if (plan == null) return null
        val chain = error.causes().toList()
        if (chain.any { it is VpnNetworkLostException }) return null
        // Свидетельство хранит цель замаскированной: тег с адресом сервера
        // иначе не совпал бы, и отказ ключа сошёл бы за недоступность.
        val targets = listOf(plan.currentId, plan.groupTag).flatMap { listOf(it, ServerAddressRedactor.redact(it)) }
        val coreCode = evidence
            ?.takeIf { it.targetId in targets }
            ?.let { named -> HysteriaFailureCode.entries.firstOrNull { it.name == named.code } }
        if (plan.currentType == PathSupervisor.HYSTERIA_TYPE && coreCode in PathSupervisor.SECURITY_FAILURES) {
            return null
        }
        val switchable = coreCode?.takeIf { it in PathSupervisor.SWITCHABLE_FAILURES }
        return when {
            chain.any { it is VpnDnsHealthException } -> switchable
            chain.any { it is VpnHealthTimeoutException || (it as? CodedFailure)?.failureCode == TRAFFIC_PROBE_CODE } ->
                switchable ?: HysteriaFailureCode.TARGET_NETWORK_TIMEOUT
            else -> null
        }
    }

    /** Кандидат проверяется, только если на полную проверку хватает бюджета перебора. */
    fun fitsBudget(remainingMillis: Long, verifyMillis: Long): Boolean =
        remainingMillis >= verifyMillis + SWITCH_OVERHEAD_MILLIS

    /** Бюджет перебора: столько полных проверок кандидатов помещается в один запуск. */
    fun budgetMillis(verifyMillis: Long): Long = MAX_FULL_CHECKS * (verifyMillis + SWITCH_OVERHEAD_MILLIS)

    /** Проверка конфигурации, команда селектора, откат и сохранение профиля вокруг одной проверки. */
    const val SWITCH_OVERHEAD_MILLIS = 3_000L

    private const val MAX_FULL_CHECKS = 2
}

/**
 * Продлеваемый бюджет запуска.
 *
 * Основной бюджет ограничивает зависание шагов запуска. Перебор резервных
 * серверов идёт [apart]: под собственным потолком и без списания с основного
 * бюджета — иначе мёртвый сервер съедал бы время и у кандидата, и у следующего
 * DNS-режима, а причиной ошибки становился бы общий тайм-аут.
 */
internal class StartupBudget(private val now: () -> Long, baseMillis: Long) {
    @Volatile
    private var deadline = now() + baseMillis

    fun remainingMillis(): Long = deadline - now()

    suspend fun <T> apart(capMillis: Long, block: suspend () -> T): T {
        val left = remainingMillis()
        deadline = now() + capMillis
        try {
            return block()
        } finally {
            deadline = now() + left
        }
    }

    /** @return null, если бюджет исчерпан: [block] отменён и завершил очистку. */
    suspend fun <T> run(block: suspend () -> T): T? = coroutineScope {
        val job = async { runCatching { block() } }
        while (true) {
            val remaining = remainingMillis()
            if (remaining <= 0) {
                job.cancel(CancellationException("Бюджет запуска исчерпан."))
                return@coroutineScope null
            }
            // Срок двигается в обе стороны, поэтому ожидание идёт короткими отрезками.
            if (withTimeoutOrNull(remaining.coerceAtMost(WATCH_SLICE_MILLIS)) { job.join() } != null) break
        }
        job.await().getOrThrow()
    }

    private companion object {
        const val WATCH_SLICE_MILLIS = 1_000L
    }
}
