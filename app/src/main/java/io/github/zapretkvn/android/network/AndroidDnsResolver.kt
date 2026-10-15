package io.github.zapretkvn.android.network

import android.annotation.TargetApi
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import android.system.OsConstants
import io.github.zapretkvn.android.diagnostics.VpnTestHooks
import io.github.zapretkvn.networkbootstrap.AndroidBootstrapDnsResolver
import io.github.zapretkvn.networkbootstrap.DnsResponseClassifier
import io.github.zapretkvn.networkbootstrap.DnsWire
import io.github.zapretkvn.networkbootstrap.HedgedQuery
import io.github.zapretkvn.networkbootstrap.ServerNameRegistry
import io.github.zapretkvn.networkbootstrap.TrustedDnsOutcome
import io.github.zapretkvn.networkbootstrap.TrustedDohResolver
import io.github.zapretkvn.networkbootstrap.BootstrapFailureCode
import io.github.zapretkvn.networkbootstrap.BootstrapFailureException
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.Func
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.asExecutor

/**
 * [trustedDoh] = false — только системный резолв на переданной сети. Нужен пробам
 * внутри VPN-сети: DoH по IP ходил бы через туннель, что медленно и запрещено
 * политикой узла. Доверенный DoH остаётся для bootstrap на физической сети.
 * [hedgeAfterMillis] > 0 — один повтор запроса при потере пакета ([HedgedQuery]).
 */
class BootstrapResolver(
    timeoutMillis: Long = AndroidBootstrapDnsResolver.DEFAULT_TIMEOUT_MILLIS,
    trustedDoh: Boolean = true,
    hedgeAfterMillis: Long = 0,
) {
    private val delegate = AndroidBootstrapDnsResolver(
        timeoutMillis = timeoutMillis,
        trusted = if (trustedDoh) TrustedDohResolver() else null,
        hedgeAfterMillis = hedgeAfterMillis,
    )

    suspend fun resolve(
        network: Network,
        hostname: String,
        noCacheLookup: Boolean = false,
    ): List<InetAddress> {
        require(hostname.isNotBlank()) { "Пустое имя сервера." }
        if (VpnTestHooks.consumeBootstrapResolutionFailure()) {
            throw BootstrapFailureException(
                BootstrapFailureCode.DnsSystem,
                technicalDetail = "fault_injection",
            )
        }
        return delegate.resolve(network, hostname, noCacheLookup)
    }
}

/** Implements the exact LocalDNSTransport ABI of the pinned libbox AAR. */
internal class AndroidLocalDnsTransport(
    private val trusted: TrustedDohResolver = TrustedDohResolver(),
    private val networkProvider: () -> Network?,
) : LocalDNSTransport {
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun lookup(context: ExchangeContext, network: String, domain: String) {
        val underlying = networkProvider() ?: error("Нет underlying network для Android DNS.")
        if (ServerNameRegistry.contains(domain)) {
            // Имя VPN-сервера: сначала доверенный путь, системный — запасной.
            val outcome = runCatching { runBlocking { trusted.resolve(underlying, domain) } }.getOrNull()
            if (outcome is TrustedDnsOutcome.Addresses) {
                val family = outcome.value.filter { address ->
                    (network == "ip4" && address is Inet4Address) || (network == "ip6" && address is Inet6Address)
                }
                context.success(family.joinToString("\n") { it.hostAddress.orEmpty() })
                return
            }
            if (outcome is TrustedDnsOutcome.NameMissing) {
                context.errorCode(DnsResponseClassifier.RCODE_NAME_ERROR)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (network == "ip4") DnsResolver.TYPE_A else DnsResolver.TYPE_AAAA
            val outcome = hedgedBlocking<List<InetAddress>>(context) { attempt ->
                val cancellation = CancellationSignal()
                DnsResolver.getInstance().query(
                    underlying,
                    domain,
                    type,
                    DnsResolver.FLAG_NO_RETRY,
                    DNS_EXECUTOR,
                    cancellation,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) =
                            attempt.succeed(DnsAnswer(answer, rcode))

                        override fun onError(error: DnsResolver.DnsException) = attempt.fail(error)
                    },
                )
                val cancel: () -> Unit = { cancellation.cancel() }
                cancel
            } ?: return
            reportOutcome(context, outcome) { answer ->
                context.success(answer.joinToString("\n") { it.hostAddress.orEmpty() })
            }
        } else {
            val addresses = underlying.getAllByName(domain).filter { address ->
                (network == "ip4" && address is Inet4Address) ||
                    (network == "ip6" && address is Inet6Address)
            }
            context.success(addresses.joinToString("\n") { it.hostAddress.orEmpty() })
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    override fun exchange(context: ExchangeContext, message: ByteArray) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val underlying = networkProvider() ?: error("Нет underlying network для Android DNS.")
        if (ServerNameRegistry.contains(DnsWire.questionName(message))) {
            // Серверы группы ядро разрешает этим транспортом. Их имена идут
            // сначала доверенным путём: системный резолвер на заблокированное
            // имя отвечает «домена нет». Обычные прямые домены сюда не попадают.
            val answer = runCatching { trusted.exchangeRaw(underlying, message) }.getOrNull()
            if (answer != null) {
                val rcode = DnsWire.rcode(answer) ?: DnsResponseClassifier.RCODE_SERVER_FAILURE
                if (rcode == DnsResponseClassifier.RCODE_SUCCESS) context.rawSuccess(answer) else context.errorCode(rcode)
                return
            }
        }
        val outcome = hedgedBlocking<ByteArray>(context) { attempt ->
            val cancellation = CancellationSignal()
            DnsResolver.getInstance().rawQuery(
                underlying,
                message,
                DnsResolver.FLAG_NO_RETRY,
                DNS_EXECUTOR,
                cancellation,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) = attempt.succeed(DnsAnswer(answer, rcode))

                    override fun onError(error: DnsResolver.DnsException) = attempt.fail(error)
                },
            )
            val cancel: () -> Unit = { cancellation.cancel() }
            cancel
        } ?: return
        reportOutcome(context, outcome) { answer -> context.rawSuccess(answer) }
    }

    private class DnsAnswer<T>(val value: T, val rcode: Int)

    /**
     * Блокирует поток вызова ядра до первого ответа с одним подстраховочным
     * повтором через [HEDGE_AFTER_MILLIS] (потеря единственного UDP-пакета
     * иначе стоит ~5 с тайм-аута netd). null — запрос отменён ядром или поток
     * прерван; тогда [ExchangeContext] уже не трогаем, как и раньше.
     * Итог: ответ, либо исключение (ошибка DnsResolver или тайм-аут).
     */
    @TargetApi(Build.VERSION_CODES.Q)
    private fun <T> hedgedBlocking(
        context: ExchangeContext,
        start: (HedgedQuery.Attempt<DnsAnswer<T>>) -> (() -> Unit),
    ): Result<DnsAnswer<T>>? {
        val job = Job()
        context.onCancel(Func { job.cancel() })
        return try {
            runBlocking(job) {
                val answer = withTimeoutOrNull(DNS_CALLBACK_TIMEOUT_MILLIS) {
                    HedgedQuery.run(HEDGE_AFTER_MILLIS, start)
                } ?: return@runBlocking Result.failure(TimeoutException("dns_timeout"))
                Result.success(answer)
            }
        } catch (_: CancellationException) {
            null
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun <T> reportOutcome(
        context: ExchangeContext,
        outcome: Result<DnsAnswer<T>>,
        onSuccess: (T) -> Unit,
    ) {
        val answer = outcome.getOrNull()
        if (answer != null) {
            if (answer.rcode != 0) context.errorCode(answer.rcode) else onSuccess(answer.value)
            return
        }
        when (val error = outcome.exceptionOrNull()) {
            is TimeoutException -> context.errnoCode(OsConstants.ETIMEDOUT)
            is InterruptedException -> context.errnoCode(OsConstants.EINTR)
            is DnsResolver.DnsException ->
                context.errnoCode((error.cause as? ErrnoException)?.errno ?: OsConstants.EIO)
            else -> context.errnoCode(OsConstants.EIO)
        }
    }

    private companion object {
        val DNS_EXECUTOR = Dispatchers.IO.asExecutor()
        const val DNS_CALLBACK_TIMEOUT_MILLIS = 10_000L

        /** Типичный ответ укладывается в 100-600 мс; повтор нужен только при потере пакета. */
        const val HEDGE_AFTER_MILLIS = 800L
    }
}
