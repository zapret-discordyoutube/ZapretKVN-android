package io.github.zapretkvn.android.updates

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import io.github.zapretkvn.android.ZapretApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Суточное фоновое задание через платформенный JobScheduler (без WorkManager, будильников и wakelock):
 * проверка обновлений приложения и обновление подписок. Это единственный планировщик приложения
 * (`scripts/verify-project.sh`), поэтому подписки обновляются тем же пробуждением, а не своим заданием.
 *
 * Задание не сохраняется между перезагрузками, потому что RECEIVE_BOOT_COMPLETED приложению не нужен
 * и не запрашивается: оно заново ставится при любом запуске процесса приложения (см. [schedule]).
 */
class UpdateCheckJobService : JobService() {
    @Volatile
    private var stoppedBySystem = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var subscriptionRefresh: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        stoppedBySystem = false
        val container = (application as ZapretApplication).container
        val update = container.updateController.startBackground {
            container.uiSettingsStore.settings.first().updateChannel
        }
        // Новые серверы подписок приходят без ручного «Обновить» (15.10.2026). Работающий VPN
        // не перезапускается: список применится при следующем подключении.
        val refresh = scope.launch { container.subscriptionAutoRefresher.refreshAll() }
        subscriptionRefresh = refresh
        scope.launch {
            listOfNotNull(update, refresh).joinAll()
            // Повтор не нужен: задание суточное, а неудачу следующий проход исправит сам.
            if (!stoppedBySystem) jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stoppedBySystem = true
        (application as ZapretApplication).container.updateController.stopBackground()
        subscriptionRefresh?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 4100
        private const val BASE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
        private const val JITTER_MILLIS = 4L * 60 * 60 * 1000
        private const val FLEX_MILLIS = 6L * 60 * 60 * 1000

        /** Ставит суточное задание, если его ещё нет: повторный вызов не сдвигает период. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            // Интервал 24–28 ч и окно запуска в его последние 6 ч: время суток у разных устройств разное.
            val interval = BASE_INTERVAL_MILLIS + Random.nextLong(JITTER_MILLIS)
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(interval, FLEX_MILLIS)
                .build()
            runCatching { scheduler.schedule(info) }
        }
    }
}
