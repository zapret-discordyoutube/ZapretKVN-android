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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidBootstrapDnsResolver(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    suspend fun resolve(
        network: Network,
        hostname: String,
        noCacheLookup: Boolean = false,
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
    ): List<InetAddress> = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        val flags = if (noCacheLookup) DnsResolver.FLAG_NO_CACHE_LOOKUP else DnsResolver.FLAG_EMPTY
        DnsResolver.getInstance().query(
            network,
            hostname,
            flags,
            DIRECT_EXECUTOR,
            cancellation,
            object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (!continuation.isActive) return
                    val unique = answer.distinctBy(InetAddress::getHostAddress)
                    val failure = DnsResponseClassifier.classify(rcode, unique.size)
                    if (failure == null) {
                        continuation.resume(unique)
                    } else {
                        continuation.resumeWithException(
                            BootstrapFailureException(
                                failure,
                                technicalDetail = "rcode=$rcode,answers=${unique.size}",
                            ),
                        )
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (!continuation.isActive) return
                    val errno = (error.cause as? ErrnoException)?.errno
                    continuation.resumeWithException(
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
        continuation.invokeOnCancellation { cancellation.cancel() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 8_000L
        private val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}
