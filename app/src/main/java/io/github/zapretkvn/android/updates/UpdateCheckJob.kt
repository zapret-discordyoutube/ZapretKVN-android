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
 * Фоновое задание через платформенный JobScheduler (без WorkManager, будильников и wakelock):
 * проверка обновлений приложения и обновление подписок. Это единственный планировщик приложения
 * (`scripts/verify-project.sh`), поэтому подписки обновляются тем же пробуждением, а не своим заданием.
 *
 * Период — настройка «Автообновление подписок» (6, 12 или 24 ч; «вручную» — 24 ч ради проверки
 * версии). Проверка обновлений приложения остаётся суточной при любом периоде: чаще её не делаем.
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
        val update = if (updateCheckDue(this)) {
            container.updateController.startBackground {
                container.uiSettingsStore.settings.first().updateChannel
            }?.also { markUpdateChecked(this) }
        } else {
            null
        }
        // Новые серверы подписок приходят без ручного «Обновить» (15.10.2026). Работающий VPN
        // не перезапускается: список применится при следующем подключении.
        val refresh = scope.launch {
            val hours = container.uiSettingsStore.settings.first().subscriptionAutoUpdateHours
            container.subscriptionAutoRefresher.refreshDue(hours)
        }
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
        private const val HOUR_MILLIS = 60L * 60 * 1000
        private const val DAY_HOURS = 24
        private const val PREFS = "background_job"
        private const val LAST_UPDATE_CHECK = "last_update_check"

        /** Проверка версии — не чаще раза в ~сутки, даже если подписки обновляются каждые 6 ч. */
        private const val UPDATE_CHECK_MIN_MILLIS = 20L * HOUR_MILLIS

        /**
         * Ставит задание с периодом [subscriptionHours] (0 — «вручную»: суточное ради проверки версии).
         * Уже стоящее задание с тем же периодом не трогается, иначе каждый старт сдвигал бы срок;
         * смена настройки перестраивает его.
         */
        fun schedule(context: Context, subscriptionHours: Int) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val hours = subscriptionHours.takeIf { it in 1 until DAY_HOURS } ?: DAY_HOURS
            val base = hours * HOUR_MILLIS
            // Интервал на 0–1/6 длиннее и окно запуска в последней четверти: без ровного ритма у всех устройств.
            val jitter = base / 6
            val pending = scheduler.getPendingJob(JOB_ID)
            if (pending != null && pending.intervalMillis in base..base + jitter) return
            val interval = base + Random.nextLong(jitter)
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(interval, base / 4)
                .build()
            runCatching { scheduler.schedule(info) }
        }

        private fun updateCheckDue(context: Context): Boolean {
            val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_UPDATE_CHECK, 0L)
            val age = System.currentTimeMillis() - last
            return age !in 0 until UPDATE_CHECK_MIN_MILLIS
        }

        private fun markUpdateChecked(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(LAST_UPDATE_CHECK, System.currentTimeMillis())
                .apply()
        }
    }
}
