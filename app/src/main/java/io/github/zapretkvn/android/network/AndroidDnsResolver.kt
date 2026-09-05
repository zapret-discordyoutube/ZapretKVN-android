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
import io.github.zapretkvn.networkbootstrap.BootstrapFailureCode
import io.github.zapretkvn.networkbootstrap.BootstrapFailureException
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.Func
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

class BootstrapResolver(
    timeoutMillis: Long = AndroidBootstrapDnsResolver.DEFAULT_TIMEOUT_MILLIS,
) {
    private val delegate = AndroidBootstrapDnsResolver(timeoutMillis)

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
    private val networkProvider: () -> Network?,
) : LocalDNSTransport {
    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun lookup(context: ExchangeContext, network: String, domain: String) {
        val underlying = networkProvider() ?: error("Нет underlying network для Android DNS.")
        val completed = AtomicBoolean(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cancellation = CancellationSignal()
            val latch = CountDownLatch(1)
            context.onCancel(
                Func {
                    if (completed.compareAndSet(false, true)) {
                        cancellation.cancel()
                        latch.countDown()
                    }
                },
            )
            val type = if (network == "ip4") DnsResolver.TYPE_A else DnsResolver.TYPE_AAAA
            DnsResolver.getInstance().query(
                underlying,
                domain,
                type,
                DnsResolver.FLAG_NO_RETRY,
                DNS_EXECUTOR,
                cancellation,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (!completed.compareAndSet(false, true)) return
                        try {
                            if (rcode != 0) context.errorCode(rcode)
                            else context.success(answer.joinToString("\n") { it.hostAddress.orEmpty() })
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        if (!completed.compareAndSet(false, true)) return
                        try {
                            context.errnoCode((error.cause as? ErrnoException)?.errno ?: OsConstants.EIO)
                        } finally {
                            latch.countDown()
                        }
                    }
                },
            )
            awaitDnsCallback(latch, completed, cancellation, context)
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
        val cancellation = CancellationSignal()
        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        context.onCancel(
            Func {
                if (completed.compareAndSet(false, true)) {
                    cancellation.cancel()
                    latch.countDown()
                }
            },
        )
        DnsResolver.getInstance().rawQuery(
            underlying,
            message,
            DnsResolver.FLAG_NO_RETRY,
            DNS_EXECUTOR,
            cancellation,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (!completed.compareAndSet(false, true)) return
                    try {
                        if (rcode != 0) context.errorCode(rcode) else context.rawSuccess(answer)
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onError(error: DnsResolver.DnsException) {
                    if (!completed.compareAndSet(false, true)) return
                    try {
                        context.errnoCode((error.cause as? ErrnoException)?.errno ?: OsConstants.EIO)
                    } finally {
                        latch.countDown()
                    }
                }
            },
        )
        awaitDnsCallback(latch, completed, cancellation, context)
    }

    private fun awaitDnsCallback(
        latch: CountDownLatch,
        completed: AtomicBoolean,
        cancellation: CancellationSignal,
        context: ExchangeContext,
    ) {
        val completedInTime = try {
            latch.await(DNS_CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (completed.compareAndSet(false, true)) {
                cancellation.cancel()
                context.errnoCode(OsConstants.EINTR)
            }
            return
        }
        if (!completedInTime && completed.compareAndSet(false, true)) {
            cancellation.cancel()
            context.errnoCode(OsConstants.ETIMEDOUT)
        }
    }

    private companion object {
        val DNS_EXECUTOR = Dispatchers.IO.asExecutor()
        const val DNS_CALLBACK_TIMEOUT_SECONDS = 10L
    }
}
