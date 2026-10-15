package io.github.zapretkvn.android.updates

import android.content.Intent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Проверка, загрузка и передача в установщик обновлений приложения.
 *
 * Работает без окон: состояние [state] читают плашка на Главной и страница настроек. Проверка идёт при
 * запуске приложения и раз в сутки из фонового задания (его создаёт приложение, не этот класс);
 * в канале Stable обновление скачивается само. Установка запускается только после полной проверки
 * SHA-256 и подписи, а без разрешения системы — одним нажатием пользователя.
 *
 * @param updatesRoot каталог кэша под `.part` и готовые APK; из него контроллер ничего не берёт, кроме своих файлов.
 */
class UpdateController(
    private val updatesRoot: File,
    private val currentVersionName: String,
    private val currentVersionCode: Long,
    private val clientFactory: (UpdateRoute) -> UpdateHttpClient,
    private val sourceFactory: (UpdateHttpClient) -> UpdateReleaseSource,
    private val verifier: ApkUpdateVerifier,
    private val vpnFallback: UpdateVpnFallback? = null,
    private val installer: UpdateInstaller = UpdateInstaller { _, _, _, _ ->
        throw UpdateException("Установщик не настроен.")
    },
    private val environment: UpdateEnvironment = UpdateEnvironment(),
    private val notifier: UpdateNotifier? = null,
    private val prefs: UpdatePrefs = InMemoryUpdatePrefs(),
    private val downloader: ApkDownloader = ApkDownloader(environment.installedApk),
    hedgeMillis: Long = UpdateRouter.DEFAULT_HEDGE_MILLIS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val router = UpdateRouter(clientFactory, vpnFallback, environment.vpnActive, prefs, hedgeMillis)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val mutableDismissed = MutableStateFlow(prefs.bannerDismissedVersionCode)

    /** versionCode, для которого плашка на Главной скрыта пользователем. */
    val bannerDismissedVersionCode: StateFlow<Long> = mutableDismissed.asStateFlow()

    @Volatile
    private var operation: Job? = null

    @Volatile
    private var backgroundOperation = false
    @Volatile
    private var readyFile: File? = null

    @Volatile
    private var pendingConfirmation: Intent? = null
    private val confirmationLaunchRequested = AtomicBoolean(false)
    private val automaticCheckStarted = AtomicBoolean(false)
    private val keepPartialOnCancel = AtomicBoolean(false)

    /** Версия, которую разрешила служба очереди, и когда (мс): для отчёта «дошла за столько-то». */
    @Volatile
    private var serverGrant: Pair<String, Long>? = null

    /** Каким путём (`parts` — дельта, `setup` — APK целиком) и сколько байт скачано в этот раз. */
    @Volatile
    private var lastDownload: Pair<String, Long>? = null

    /** Последняя техническая причина сбоя: для журнала и диагностики, пользователю не показывается. */
    @Volatile
    var lastTechnicalDetail: String? = null
        private set

    init {
        cleanupAtStart()
        reportFinishedInstall()
    }

    /** Ручная проверка из настроек. */
    fun check(channel: UpdateChannel) {
        backgroundOperation = false
        replaceOperation { runCheck(channel, automatic = false, background = false) }
    }

    /** Проверка при запуске: не чаще раза за процесс; в Stable ещё и скачивает само. */
    fun checkOnce(channel: UpdateChannel) {
        if (!automaticCheckStarted.compareAndSet(false, true)) return
        if (operation?.isActive == true) return
        backgroundOperation = false
        replaceOperation { runCheck(channel, automatic = true, background = false) }
    }

    /**
     * Фоновая проверка из задания JobScheduler. Возвращает задачу или null, если уже что-то выполняется.
     * Канал читается внутри задачи, потому что настройки лежат в DataStore.
     */
    fun startBackground(channelProvider: suspend () -> UpdateChannel): Job? {
        if (operation?.isActive == true) return null
        backgroundOperation = true
        keepPartialOnCancel.set(false)
        val job = scope.launch { runCheck(channelProvider(), automatic = true, background = true) }
        operation = job
        return job
    }

    /**
     * Служба очереди разрешила обновиться до [version] (слушатель [ReleaseWaitClient]): та же фоновая
     * проверка и загрузка, что из суточного задания, только сразу. Null — уже что-то выполняется.
     */
    fun onServerGrant(version: String, now: Long = System.currentTimeMillis()): Job? {
        serverGrant = version to now
        return startBackground { UpdateChannel.Stable }
    }

    /** Что сказать службе очереди об исходе прошлого обновления (один раз); пусто — нечего. */
    fun pendingServerReport(): Map<String, String> = decodeReport(prefs.pendingReport)

    /** Служба получила отчёт: второй раз его не шлём. */
    fun serverReportDelivered(report: Map<String, String>) {
        if (report.isNotEmpty() && decodeReport(prefs.pendingReport) == report) prefs.pendingReport = null
    }

    /** Система остановила фоновое задание: прерываем работу, недокачанный файл оставляем для докачки. */
    fun stopBackground() {
        val job = operation ?: return
        if (!backgroundOperation || !job.isActive) return
        keepPartialOnCancel.set(true)
        job.cancel()
        // Остановленная проверка не должна навсегда оставить в интерфейсе «Проверяем…».
        job.invokeOnCompletion {
            if (operation === job && mutableState.value is UpdateState.Checking) mutableState.value = UpdateState.Idle
        }
    }

    /** Скачивание по нажатию пользователя: ограничения мобильной сети не действуют. */
    fun download() {
        val candidate = when (val current = mutableState.value) {
            is UpdateState.Available -> current.candidate
            is UpdateState.Failure -> current.candidate
            else -> null
        } ?: return
        backgroundOperation = false
        replaceOperation { runDownload(candidate, lease = null, manual = true, background = false) }
    }

    fun cancelAndDelete() {
        val previous = operation
        keepPartialOnCancel.set(false)
        operation = scope.launch {
            previous?.cancelAndJoin()
            cleanupFiles()
            resetInstallRequest()
            mutableState.value = UpdateState.Idle
        }
    }

    /**
     * Передаёт готовый APK системному установщику. [userInitiated] — пользователь нажал кнопку:
     * если Android попросит подтверждение, оно откроется сразу (см. [consumeConfirmationLaunchRequest]).
     */
    fun install(userInitiated: Boolean = true) {
        val ready = mutableState.value as? UpdateState.Ready ?: return
        if (ready.needsConfirmation) {
            if (userInitiated) confirmationLaunchRequested.set(true)
            return
        }
        val file = readyFile?.takeIf(File::isFile)
        if (file == null) {
            mutableState.value = UpdateState.Failure("Загруженный файл больше недоступен. Скачайте обновление заново.", ready.candidate)
            return
        }
        backgroundOperation = false
        replaceOperation { runInstall(ready.candidate, file, userInitiated) }
    }

    /** Intent подтверждения установки, пока Android ждёт нажатия; запускать только по действию пользователя. */
    fun confirmationIntent(): Intent? = pendingConfirmation

    /** true один раз, если подтверждение установки нужно открыть сразу: пользователь уже нажал «Установить». */
    fun consumeConfirmationLaunchRequest(): Boolean =
        (mutableState.value as? UpdateState.Ready)?.needsConfirmation == true &&
            pendingConfirmation != null &&
            confirmationLaunchRequested.compareAndSet(true, false)

    /** Итог установки от системного установщика (приёмник в приложении). */
    fun onInstallResult(result: UpdateInstallResult) {
        val current = mutableState.value
        val candidate = when (current) {
            is UpdateState.Installing -> current.candidate
            is UpdateState.Ready -> current.candidate
            is UpdateState.Failure -> current.candidate
            else -> null
        }
        when (result) {
            UpdateInstallResult.Success -> {
                prefs.installingVersionCode = 0
                resetInstallRequest()
                mutableState.value = UpdateState.Idle
            }
            is UpdateInstallResult.NeedsConfirmation -> {
                pendingConfirmation = result.intent
                if (candidate != null) mutableState.value = UpdateState.Ready(candidate, needsConfirmation = true)
                val name = candidate?.metadata?.versionName ?: prefs.installingVersionName
                val shownNow = confirmationLaunchRequested.get() && environment.appInForeground()
                if (name != null && !shownNow) notifier?.confirmationNeeded(name, result.intent)
            }
            is UpdateInstallResult.Failed -> {
                val name = candidate?.metadata?.versionName ?: prefs.installingVersionName
                // Отказ самого пользователя — не поломка версии: службе о нём не сообщаем.
                val why = installFailureCode(result.detail)
                if (name != null && why != WHY_ABORTED) noteFailure(name, why) else clearAttempt()
                prefs.installingVersionCode = 0
                resetInstallRequest()
                lastTechnicalDetail = result.detail
                mutableState.value = UpdateState.Failure(result.message, candidate, result.detail)
            }
        }
    }

    /** Пользователь скрыл плашку для этой версии. */
    fun dismissBanner(versionCode: Long) {
        prefs.bannerDismissedVersionCode = versionCode
        mutableDismissed.value = versionCode
    }

    fun cleanupStaleFiles() {
        cleanupAtStart()
    }

    // ---- операции ----

    private fun replaceOperation(block: suspend () -> Unit) {
        operation?.cancel()
        keepPartialOnCancel.set(false)
        operation = scope.launch { block() }
    }

    private suspend fun runCheck(channel: UpdateChannel, automatic: Boolean, background: Boolean) {
        val previous = mutableState.value.takeUnless { it is UpdateState.Checking || it is UpdateState.Downloading }
            ?: UpdateState.Idle
        // Пока система ставит обновление или ждёт подтверждения, новая проверка только мешает.
        if (previous is UpdateState.Installing || previous is UpdateState.Ready && previous.needsConfirmation) return
        mutableState.value = UpdateState.Checking(channel.name)
        var lease: UpdateRouter.Lease<UpdateCandidate>? = null
        try {
            lease = router.race(allowStartingVpn = !background) { client -> sourceFactory(client).latest(channel) }
            coroutineContext.ensureActive()
            val candidate = lease.value
            if (candidate.metadata.versionCode <= currentVersionCode) {
                cleanupFiles()
                mutableState.value = UpdateState.UpToDate(candidate.release.tag, currentVersionName)
                return
            }
            if (adoptDownloaded(candidate)) {
                afterReady(candidate, automatic)
                return
            }
            if (automatic && channel == UpdateChannel.Stable) {
                // .part прерванной фоновой загрузки этой же версии пригодится для докачки.
                cleanupFiles(keep = setOf("${candidate.metadata.apkFile}.part"))
                runDownload(candidate, lease, manual = false, background = background)
            } else {
                cleanupFiles()
                mutableState.value = UpdateState.Available(candidate)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            lastTechnicalDetail = technicalDetail(error)
            // Недоступный сервер не отменяет уже скачанное: готовое обновление остаётся готовым.
            val restore = previous.takeIf { it is UpdateState.Ready }
            mutableState.value = when {
                restore != null -> restore
                automatic -> previous
                else -> UpdateState.Failure(userMessage(error, CHECK_FAILED), null, lastTechnicalDetail)
            }
        } finally {
            lease?.close()
        }
    }

    private suspend fun runDownload(
        candidate: UpdateCandidate,
        lease: UpdateRouter.Lease<UpdateCandidate>?,
        manual: Boolean,
        background: Boolean,
    ) {
        val partial = File(updatesRoot, "${candidate.metadata.apkFile}.part")
        val complete = File(updatesRoot, candidate.metadata.apkFile)
        try {
            updatesRoot.mkdirs()
            val outcome = downloadRouted(candidate, lease, partial, manual, background)
            coroutineContext.ensureActive()
            if (outcome == DownloadOutcome.Deferred) {
                // Мобильная сеть и большой объём: ждём Wi-Fi, пользователю остаётся кнопка «Скачать».
                mutableState.value = UpdateState.Available(candidate)
                return
            }
            if (complete.exists()) complete.delete()
            if (!partial.renameTo(complete)) throw UpdateException("Не удалось завершить временный APK.")
            verifier.verify(complete, candidate.metadata)
            coroutineContext.ensureActive()
            readyFile = complete
            afterReady(candidate, automatic = !manual)
        } catch (cancelled: CancellationException) {
            if (keepPartialOnCancel.get()) {
                // Система остановила фоновое задание: .part пригодится для докачки в этом процессе.
                complete.delete()
                mutableState.value = UpdateState.Available(candidate)
            } else {
                cleanupFiles()
            }
            throw cancelled
        } catch (error: Throwable) {
            cleanupFiles()
            lastTechnicalDetail = technicalDetail(error)
            val routeProblem = error !is UpdateException || error.retryViaVpn
            mutableState.value = if (manual || !routeProblem) {
                UpdateState.Failure(userMessage(error, DOWNLOAD_FAILED), candidate, lastTechnicalDetail)
            } else {
                // Автоматическая загрузка не дошла: тихо возвращаемся к «доступно», повторим позже.
                UpdateState.Available(candidate)
            }
        }
    }

    private enum class DownloadOutcome { Completed, Deferred }

    private suspend fun downloadRouted(
        candidate: UpdateCandidate,
        lease: UpdateRouter.Lease<UpdateCandidate>?,
        partial: File,
        manual: Boolean,
        background: Boolean,
    ): DownloadOutcome {
        var failure: UpdateException? = null
        if (lease != null) {
            try {
                return downloadVia(lease.client, candidate, partial, manual)
            } catch (error: UpdateException) {
                if (!error.retryViaVpn) throw error
                failure = error
            }
        }
        val order = router.downloadOrder(lease?.route ?: prefs.lastRoute, allowStartingVpn = !background)
            .filter { it != lease?.route }
        if (order.isEmpty()) throw failure ?: UpdateRoutesFailedException(emptyList())
        val routed = router.sequential(order) { client -> downloadVia(client, candidate, partial, manual) }
        try {
            return routed.value
        } finally {
            routed.close()
        }
    }

    private suspend fun downloadVia(
        client: UpdateHttpClient,
        candidate: UpdateCandidate,
        partial: File,
        manual: Boolean,
    ): DownloadOutcome {
        val metadata = candidate.metadata
        val url = candidate.apkAsset.downloadUrl
        val plan = downloader.plan(client, url, metadata.apkSize, partial)
        if (!manual && !UpdateAutoDownloadPolicy.allows(environment.isMetered(), plan.networkBytes)) {
            return DownloadOutcome.Deferred
        }
        var lastPublishedAt = 0L
        var networkBytes = plan.networkBytes
        publishDownloading(candidate, 0, plan.networkBytes, plan.savedBytes)
        downloader.download(
            client = client,
            url = url,
            expectedSize = metadata.apkSize,
            expectedSha256 = metadata.apkSha256,
            plan = plan,
            partial = partial,
            progress = { downloaded, total, saved ->
                networkBytes = total
                val now = System.nanoTime()
                if (downloaded >= total || now - lastPublishedAt >= PROGRESS_INTERVAL_NANOS) {
                    lastPublishedAt = now
                    publishDownloading(candidate, downloaded, total, saved)
                }
            },
        )
        // Дельта может откатиться к APK целиком: путь считаем по тому, сколько скачано на самом деле.
        lastDownload = (if (networkBytes < metadata.apkSize) VIA_DELTA else VIA_FULL) to networkBytes
        return DownloadOutcome.Completed
    }

    private fun publishDownloading(candidate: UpdateCandidate, downloaded: Long, total: Long, saved: Long) {
        mutableState.value = UpdateState.Downloading(candidate, downloaded.coerceAtMost(total), total, saved)
    }

    /** APK уже лежит в кэше от прошлого запуска: сверяем SHA-256 и подпись заново и не качаем второй раз. */
    private fun adoptDownloaded(candidate: UpdateCandidate): Boolean {
        val file = File(updatesRoot, candidate.metadata.apkFile)
        if (!file.isFile || file.length() != candidate.metadata.apkSize) return false
        return try {
            if (ApkDownloader.sha256(file) != candidate.metadata.apkSha256) return false
            verifier.verify(file, candidate.metadata)
            readyFile = file
            true
        } catch (_: UpdateException) {
            false
        }
    }

    private suspend fun afterReady(candidate: UpdateCandidate, automatic: Boolean) {
        mutableState.value = UpdateState.Ready(candidate)
        if (!automatic) return
        val file = readyFile
        // Одна тихая попытка на версию: если она не довела до установки, дальше только по нажатию пользователя.
        val silentAllowed = file != null && environment.canInstallUnattended() &&
            prefs.silentAttemptVersionCode != candidate.metadata.versionCode
        if (silentAllowed && file != null) {
            prefs.silentAttemptVersionCode = candidate.metadata.versionCode
            runInstall(candidate, file, userInitiated = false)
        } else if (!environment.appInForeground()) {
            notifier?.updateReady(candidate.metadata.versionName)
        }
    }

    private suspend fun runInstall(candidate: UpdateCandidate, file: File, userInitiated: Boolean) {
        mutableState.value = UpdateState.Installing(candidate)
        try {
            // Последний барьер перед установкой: те же проверки, что после загрузки, на файле как он лежит сейчас.
            if (ApkDownloader.sha256(file) != candidate.metadata.apkSha256) {
                throw UpdateException("SHA-256 APK не совпадает с опубликованным.")
            }
            coroutineContext.ensureActive()
            verifier.verify(file, candidate.metadata)
            coroutineContext.ensureActive()
            prefs.installingVersionCode = candidate.metadata.versionCode
            prefs.installingVersionName = candidate.metadata.versionName
            rememberAttempt(candidate.metadata.versionName)
            confirmationLaunchRequested.set(userInitiated)
            // Тихо — только фоновая установка: приложение на экране Android без подтверждения не обновляет.
            installer.install(file, candidate.metadata.apkSha256, candidate, silent = !userInitiated)
            // Файл передан системе (у сессии своя копия): в кэше он больше не нужен.
            cleanupFiles()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            cleanupFiles()
            if (prefs.installingVersionCode == candidate.metadata.versionCode) {
                noteFailure(candidate.metadata.versionName, WHY_HANDOFF)
            }
            prefs.installingVersionCode = 0
            lastTechnicalDetail = technicalDetail(error)
            mutableState.value = UpdateState.Failure(userMessage(error, INSTALL_FAILED), candidate, lastTechnicalDetail)
        }
    }

    // ---- файлы и служебное ----

    /** Удаляет файлы обновления из кэша; [keep] — имена, которые нужно оставить (докачиваемый `.part`). */
    private fun cleanupFiles(keep: Set<String> = emptySet()) {
        if (keep.isEmpty()) readyFile = null
        updatesRoot.listFiles()?.forEach { file -> if (file.isFile && file.name !in keep) file.delete() }
        updatesRoot.delete()
    }

    private fun resetInstallRequest() {
        pendingConfirmation = null
        confirmationLaunchRequested.set(false)
    }

    /**
     * При запуске: `.part` и посторонние файлы удаляются всегда. Готовый APK более новой версии остаётся
     * до установки или до замены более новой версией; его SHA-256 и подпись сверяются заново при проверке.
     */
    private fun cleanupAtStart() {
        val files = updatesRoot.listFiles() ?: return
        files.forEach { file ->
            val version = file.takeIf { it.isFile && it.name.endsWith(".apk") }?.let(environment.archiveVersionCode)
            if (version == null || version <= currentVersionCode) file.delete()
        }
        updatesRoot.delete()
    }

    /** Приложение запущено после установки, переданной системе: сообщаем о новой версии один раз. */
    private fun reportFinishedInstall() {
        val code = prefs.installingVersionCode
        if (code <= 0 || code > currentVersionCode) return
        prefs.installingVersionCode = 0
        prefs.installingVersionName = null
        noteArrived()
        notifier?.installed(currentVersionName)
    }

    // ---- отчёт службе очереди ----

    /** Перед передачей в установщик: запоминаем, с какой версии, когда разрешили и как скачали. */
    private fun rememberAttempt(version: String) {
        val grant = serverGrant?.takeIf { it.first == version }
        val download = lastDownload
        prefs.attemptFromVersion = currentVersionName
        prefs.attemptGrantedAtMillis = grant?.second ?: 0
        prefs.attemptVia = download?.first
        prefs.attemptDownloadedKib = download?.second?.let { (it + 1023) / 1024 } ?: 0
    }

    /** Новая версия запустилась: готовим «дошла» — с какой версии, за сколько секунд, каким путём. */
    private fun noteArrived(now: Long = System.currentTimeMillis()) {
        val from = prefs.attemptFromVersion ?: return
        val report = linkedMapOf("prev" to from)
        val grantedAt = prefs.attemptGrantedAtMillis
        if (grantedAt in 1..now) report["took"] = ((now - grantedAt) / 1000).toString()
        prefs.attemptVia?.let { report["via"] = it }
        prefs.attemptDownloadedKib.takeIf { it > 0 }?.let { report["dl"] = it.toString() }
        prefs.pendingReport = encodeReport(report)
        clearAttempt()
    }

    /** Установка не удалась: служба остановит раздачу версии, если таких отчётов много. */
    private fun noteFailure(version: String, why: String) {
        prefs.pendingReport = encodeReport(linkedMapOf("fail" to version, "why" to why))
        clearAttempt()
    }

    private fun clearAttempt() {
        prefs.attemptFromVersion = null
        prefs.attemptGrantedAtMillis = 0
        prefs.attemptVia = null
        prefs.attemptDownloadedKib = 0
    }

    private fun userMessage(error: Throwable, fallback: String): String =
        if (error is UpdateException && !error.retryViaVpn && error !is UpdateRoutesFailedException) {
            error.message ?: fallback
        } else {
            fallback
        }

    private fun technicalDetail(error: Throwable): String = when (error) {
        is UpdateRoutesFailedException -> error.detail
        else -> listOfNotNull(error.javaClass.simpleName, error.message).joinToString(": ")
    }

    internal companion object {
        const val VIA_DELTA = "parts"
        const val VIA_FULL = "setup"
        const val WHY_HANDOFF = "handoff"
        const val WHY_ABORTED = "abort"

        /** Код причины для службы по ответу установщика Android (`status=N: …`). */
        fun installFailureCode(detail: String?): String {
            val status = detail?.substringAfter("status=", "")?.takeWhile { it.isDigit() || it == '-' }?.toIntOrNull()
            return when (status) {
                3 -> WHY_ABORTED
                2 -> "blocked"
                5 -> "conflict"
                7 -> "incompat"
                4 -> "invalid"
                6 -> "storage"
                else -> "other"
            }
        }

        fun encodeReport(report: Map<String, String>): String =
            report.entries.joinToString("&") { (key, value) -> "$key=$value" }

        fun decodeReport(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                val value = pair.substringAfter('=', "")
                if (key.isEmpty() || value.isEmpty()) null else key to value
            }.toMap(LinkedHashMap())
        }

        const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        const val CHECK_FAILED = "Не удалось проверить обновления. Проверим позже."
        const val DOWNLOAD_FAILED = "Не удалось загрузить обновление. Попробуем позже."
        const val INSTALL_FAILED = "Не удалось установить обновление."
    }
}
