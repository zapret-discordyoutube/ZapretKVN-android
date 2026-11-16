package io.github.zapretkvn.android.masking

import io.github.zapretkvn.android.network.VpnNetworkProvider
import io.github.zapretkvn.android.network.probes.LaneRun
import io.github.zapretkvn.android.network.probes.ProbeLane
import io.github.zapretkvn.android.network.probes.VpnThroughputProbe
import io.github.zapretkvn.android.network.probes.runPreemptible
import io.github.zapretkvn.masking.tester.CandidateTester
import io.github.zapretkvn.masking.tester.FreezeDetector
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One "Подбор" transfer through the connected tunnel: [CandidateTester.TRANSFER_BYTES] of the
 * same neutral test file the speed check reads ([VpnThroughputProbe.DOWNLOAD_URL]), a fresh
 * connection each time. The byte count is sampled every [SAMPLE_MS] while the transfer runs, so
 * a blocked read still shows up as a stall at its offset; [FreezeDetector] decides what it means.
 *
 * What is measured is the inner stream; the per-connection freeze stops the tunnel's outer
 * connection and with it the inner bytes, at an offset a few percent below the outer one
 * (tunnel overhead). Holds the shared probe lane, so it never overlaps a server or service check.
 */
internal class MaskingTransferProbe(
    private val vpnNetworks: VpnNetworkProvider,
    private val lane: ProbeLane,
) {
    data class Outcome(val transfer: FreezeDetector.Transfer, val firstByteMs: Long?)

    suspend fun run(): Outcome =
        when (val run = lane.runPreemptible(ProbeLane.Owner.Throughput, LANE_WAIT_MILLIS, ::transfer)) {
            is LaneRun.Done -> run.value
            LaneRun.Preempted, LaneRun.Timeout -> failed()
        }

    private suspend fun transfer(): Outcome {
        val network = withTimeoutOrNull(NETWORK_WAIT_MILLIS) { vpnNetworks.awaitActive() } ?: return failed()
        if (runCatching { vpnNetworks.requireActive(network) }.isFailure) return failed()
        return withContext(Dispatchers.IO) {
            val opened = System.nanoTime()
            val connection = try {
                network.openConnection(URL(VpnThroughputProbe.DOWNLOAD_URL)) as HttpsURLConnection
            } catch (_: IOException) {
                return@withContext failed()
            }
            val recorder = TransferRecorder(opened)
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                connection.readTimeout = READ_TIMEOUT_MILLIS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Connection", "close")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Range", "bytes=0-${CandidateTester.TRANSFER_BYTES - 1}")
                coroutineScope {
                    val sampler = launch {
                        while (isActive) {
                            recorder.sample()
                            if (recorder.elapsedMs() >= MAX_TRANSFER_MILLIS) {
                                recorder.overrun()
                                connection.disconnect()
                                break
                            }
                            delay(SAMPLE_MS)
                        }
                    }
                    try {
                        runInterruptible {
                            if (connection.responseCode !in OK_CODES) {
                                recorder.finish(FreezeDetector.End.Error)
                            } else {
                                connection.inputStream.use(recorder::read)
                            }
                        }
                    } finally {
                        sampler.cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                recorder.finish(TransferRecorder.endOf(error))
            } finally {
                connection.disconnect()
            }
            recorder.outcome()
        }
    }

    private fun failed() = Outcome(FreezeDetector.Transfer(emptyList(), FreezeDetector.End.Error, CandidateTester.TRANSFER_BYTES), null)

    internal companion object {
        const val SAMPLE_MS = 500L

        /** Longer than [FreezeDetector.STALL_MS]: a read that gives up earlier could not show the freeze. */
        const val READ_TIMEOUT_MILLIS = 12_000
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val MAX_TRANSFER_MILLIS = 40_000L
        const val NETWORK_WAIT_MILLIS = 5_000L
        const val LANE_WAIT_MILLIS = 15_000L
        val OK_CODES = setOf(200, 206)
    }
}

/**
 * Samples and the end of one transfer. Thread-safe: the reader thread adds bytes, the sampler
 * coroutine adds samples. Kept apart from the network so the mapping is unit-tested.
 */
internal class TransferRecorder(private val startedNanos: Long, private val clockNanos: () -> Long = System::nanoTime) {
    private val bytes = AtomicLong(0)
    private val samples = ArrayList<FreezeDetector.Sample>()
    private val lock = Any()

    @Volatile
    private var end: FreezeDetector.End? = null

    @Volatile
    private var firstByteMs: Long? = null

    @Volatile
    private var overran = false

    fun elapsedMs(): Long = (clockNanos() - startedNanos) / 1_000_000

    fun sample() = synchronized(lock) { samples += FreezeDetector.Sample(elapsedMs(), bytes.get()) }

    fun overrun() {
        overran = true
    }

    /** Reads up to [CandidateTester.TRANSFER_BYTES]; the end is recorded here or by [finish]. */
    fun read(input: InputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (bytes.get() < CandidateTester.TRANSFER_BYTES) {
            val wanted = minOf(buffer.size.toLong(), CandidateTester.TRANSFER_BYTES - bytes.get()).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) {
                finish(FreezeDetector.End.Reset)
                return
            }
            if (read > 0 && firstByteMs == null) firstByteMs = elapsedMs()
            bytes.addAndGet(read.toLong())
        }
        finish(FreezeDetector.End.Completed)
    }

    fun finish(value: FreezeDetector.End) {
        if (end == null) end = value
    }

    fun outcome(): MaskingTransferProbe.Outcome {
        sample()
        val finalEnd = when {
            overran && end != FreezeDetector.End.Completed -> FreezeDetector.End.Timeout
            else -> end ?: FreezeDetector.End.Error
        }
        val recorded = synchronized(lock) { samples.toList() }
        return MaskingTransferProbe.Outcome(
            FreezeDetector.Transfer(recorded, finalEnd, CandidateTester.TRANSFER_BYTES),
            firstByteMs,
        )
    }

    companion object {
        const val BUFFER_BYTES = 16 * 1024

        /** A read timeout is the stall itself; a reset or an early close is FIN/RST (inconclusive). */
        fun endOf(error: IOException): FreezeDetector.End = when {
            error is SocketTimeoutException -> FreezeDetector.End.Timeout
            error is SocketException && error.message.orEmpty().contains("reset", ignoreCase = true) -> FreezeDetector.End.Reset
            error.message.orEmpty().contains("unexpected end of stream", ignoreCase = true) -> FreezeDetector.End.Reset
            else -> FreezeDetector.End.Error
        }
    }
}
