package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.android.diagnostics.AppCrashRecord
import io.github.zapretkvn.android.diagnostics.AppProcessExitRecord
import io.github.zapretkvn.clientreports.AppError
import io.github.zapretkvn.clientreports.AppErrors
import io.github.zapretkvn.clientreports.SubscriptionFact
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The app's own failures as app errors (vpnbot «app_error live» 2026-11-20): codes and frames only. Nothing here reads
 * a message, a URL or an address; a subscription failure is named by its HTTP class or its transport error.
 */
internal object AppErrorFacts {
    /** The newest uncaught crash of an earlier run: `crash:<Exception>`, its frames innermost first. */
    fun crash(record: AppCrashRecord?): Pair<Long, AppError>? {
        record ?: return null
        val code = AppErrors.code("crash", record.exceptionType) ?: return null
        val frames = record.stack.mapNotNull { AppErrors.frame(it.className + "." + it.methodName, it.lineNumber) }
            .take(AppErrors.MAX_FRAMES)
        return record.occurredAtEpochMillis to AppError(AppErrors.STEP_CRASH, code, frames)
    }

    /** A native crash or an ANR of an earlier run (a Java crash comes from [crash] with its frames). */
    fun exit(record: AppProcessExitRecord?): Pair<Long, AppError>? {
        record ?: return null
        val reason = record.reason.takeIf { it == "native_crash" || it == "anr" } ?: return null
        return record.occurredAtEpochMillis to AppError(AppErrors.STEP_CRASH, "crash:$reason")
    }

    /** A start that failed before any server was chosen: the app's own failure code (`VPN-120`, `DNS-110` …). */
    fun start(code: String): AppError? = AppErrors.code(if (code == "LOCAL_BIND_COLLISION") "port" else "start", if (code == "LOCAL_BIND_COLLISION") "bind" else code)
        ?.let { AppError(AppErrors.STEP_START, it) }

    /** The VPN permission: `vpn_denied` (the dialog was refused) or `vpn_revoked` (another VPN took it). */
    fun permission(value: String): AppError? = AppErrors.code("perm", value)?.let { AppError(AppErrors.STEP_PERMISSION, it) }

    /** An update that failed: `update:<code>` from [io.github.zapretkvn.android.updates]. */
    fun update(value: String): AppError? = AppErrors.code("update", value)?.let { AppError(AppErrors.STEP_UPDATE, it) }

    /**
     * A subscription that could not be fetched: [status] the HTTP status when the server answered, else the transport
     * error. [ours] — the link answered with our service's header before; [route] — the way of the last try.
     */
    fun subscription(status: Int?, error: Throwable?, ours: Boolean, route: String): AppError? {
        val value = when {
            status != null && status in 400..499 -> "http_4xx"
            status != null && status in 500..599 -> "http_5xx"
            status != null -> "http_other"
            else -> transport(error)
        }
        val code = AppErrors.code("sub", value) ?: return null
        return AppError(AppErrors.STEP_SUBSCRIPTION, code, subscription = SubscriptionFact(ours, route.takeIf { it in AppErrors.ROUTES } ?: "direct"))
    }

    internal fun transport(error: Throwable?): String {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }
        return when {
            chain.any { it is SocketTimeoutException } || "ETIMEDOUT" in text -> "timeout"
            chain.any { it is UnknownHostException } -> "dns"
            chain.any { it is SSLException } -> "tls"
            chain.any { it is ConnectException || it is NoRouteToHostException } || "ECONNREFUSED" in text -> "refused"
            "ECONNRESET" in text || text.contains("reset", ignoreCase = true) -> "reset"
            chain.any { it is IOException } -> "unreachable"
            else -> "other"
        }
    }
}
