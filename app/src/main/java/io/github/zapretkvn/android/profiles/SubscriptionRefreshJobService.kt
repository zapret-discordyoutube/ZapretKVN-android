package io.github.zapretkvn.android.profiles

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
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Обновление подписок раз в 12–14 часов через платформенный JobScheduler — так же, как
 * суточная проверка обновлений приложения: без WorkManager, будильников и wakelock.
 * Задание ставится при каждом старте процесса и не переживает перезагрузку.
 */
class SubscriptionRefreshJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val refresher = (application as ZapretApplication).container.subscriptionAutoRefresher
        running = scope.launch {
            refresher.refreshAll()
        }.also { job ->
            job.invokeOnCompletion { cause ->
                // Повтор не нужен: следующий проход через 12 часов подберёт пропущенное.
                if (cause == null) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 4101
        private const val BASE_INTERVAL_MILLIS = 12L * 60 * 60 * 1000
        private const val JITTER_MILLIS = 2L * 60 * 60 * 1000
        private const val FLEX_MILLIS = 3L * 60 * 60 * 1000

        /** Ставит задание, если его ещё нет: повторный вызов не сдвигает период. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val interval = BASE_INTERVAL_MILLIS + Random.nextLong(JITTER_MILLIS)
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, SubscriptionRefreshJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(interval, FLEX_MILLIS)
                .build()
            runCatching { scheduler.schedule(info) }
        }
    }
}
