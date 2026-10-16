package io.github.zapretkvn.networkbootstrap

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidBootstrapDnsResolver(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val trusted: TrustedDohResolver? = TrustedDohResolver(),
    /** > 0: системный запрос повторяется один раз, если ответа нет за это время (см. [HedgedQuery]). */
    private val hedgeAfterMillis: Long = 0,
) {
    /**
     * Адрес VPN-сервера: сначала доверенный DoH по IP, системный резолвер — только
     * если DoH недоступен. Открытый запрос с именем своего сервера провайдер видит
     * и может связать с последующим подключением, поэтому он не уходит, пока есть
     * доверенный ответ. Подмену DNS проверяет [DecoyDnsCheck] на чужом домене.
     */
    suspend fun resolve(
        network: Network,
        hostname: String,
        noCacheLookup: Boolean = false,
    ): List<InetAddress> {
        val verdict = if (trusted == null) {
            TrustedDnsOutcome.Unavailable
        } else {
            withTimeoutOrNull(TRUSTED_WINDOW_MILLIS) { trusted.resolve(network, hostname) }
                ?: TrustedDnsOutcome.Unavailable
        }
        return when (verdict) {
            is TrustedDnsOutcome.Addresses -> verdict.value
            // Все доверенные резолверы подтвердили, что имени нет: системному
            // ответу здесь верить нечему.
            TrustedDnsOutcome.NameMissing -> throw BootstrapFailureException(
                BootstrapFailureCode.DnsNameNotFound,
                technicalDetail = "trusted_doh_nxdomain",
            )
            TrustedDnsOutcome.Unavailable -> resolveSystem(network, hostname, noCacheLookup)
        }
    }

    /** Только системный резолвер — для [DecoyDnsCheck] и проб внутри VPN-сети. */
    suspend fun resolveSystemOnly(
        network: Network,
        hostname: String,
        noCacheLookup: Boolean = false,
    ): List<InetAddress> = resolveSystem(network, hostname, noCacheLookup)

    private suspend fun resolveSystem(
        network: Network,
        hostname: String,
        noCacheLookup: Boolean,
    ): List<InetAddress> {
        require(hostname.isNotBlank()) { "Hostname must not be blank." }
        return try {
            withTimeoutOrNull(timeoutMillis) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolveModern(network, hostname, noCacheLookup)
                } else {
                    resolveLegacy(network, hostname)
                }
            } ?: throw BootstrapFailureException(
                BootstrapFailureCode.DnsTimeout,
                technicalDetail = "timeout_ms=$timeoutMillis",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BootstrapFailureException) {
            throw failure
        } catch (unknown: UnknownHostException) {
            throw BootstrapFailureException(
                BootstrapFailureCode.DnsNameNotFound,
                technicalDetail = "legacy_unknown_host",
                cause = unknown,
            )
        } catch (error: Throwable) {
            throw BootstrapFailureException(
                BootstrapFailureCode.DnsSystem,
                technicalDetail = error.javaClass.simpleName.take(80),
                cause = error,
            )
        }
    }

    private suspend fun resolveLegacy(network: Network, hostname: String): List<InetAddress> =
        withContext(Dispatchers.IO) {
            network.getAllByName(hostname).distinctBy(InetAddress::getHostAddress)
        }.ifEmpty {
            throw BootstrapFailureException(BootstrapFailureCode.DnsEmptyAnswer)
        }

    @SuppressLint("NewApi")
    private suspend fun resolveModern(
        network: Network,
        hostname: String,
        noCacheLookup: Boolean,
    ): List<InetAddress> {
        val flags = if (noCacheLookup) DnsResolver.FLAG_NO_CACHE_LOOKUP else DnsResolver.FLAG_EMPTY
        return HedgedQuery.run<List<InetAddress>>(hedgeAfterMillis) { attempt ->
            val cancellation = CancellationSignal()
            DnsResolver.getInstance().query(
                network,
                hostname,
                flags,
                DIRECT_EXECUTOR,
                cancellation,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        val unique = answer.distinctBy(InetAddress::getHostAddress)
                        val failure = DnsResponseClassifier.classify(rcode, unique.size)
                        if (failure == null) {
                            attempt.succeed(unique)
                        } else {
                            attempt.fail(
                                BootstrapFailureException(
                                    failure,
                                    technicalDetail = "rcode=$rcode,answers=${unique.size}",
                                ),
                            )
                        }
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        val errno = (error.cause as? ErrnoException)?.errno
                        attempt.fail(
                            BootstrapFailureException(
                                BootstrapFailureCode.DnsSystem,
                                technicalDetail = listOfNotNull(
                                    "dns_error=${error.code}",
                                    errno?.let { "errno=$it" },
                                ).joinToString(","),
                                cause = error,
                            ),
                        )
                    }
                },
            )
            val cancel: () -> Unit = { cancellation.cancel() }
            cancel
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 8_000L
        // Окно ожидания доверенного ответа. Там, где зарубежные адреса
        // недоступны совсем, подключение задержится не больше чем на него.
        const val TRUSTED_WINDOW_MILLIS = 3_000L
        private val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}
