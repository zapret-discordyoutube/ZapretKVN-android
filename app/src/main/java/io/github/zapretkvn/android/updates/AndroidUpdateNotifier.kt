package io.github.zapretkvn.android.updates

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.R

/**
 * Тихие уведомления об обновлении: канал «Обновления» с низкой важностью, без звука и вибрации.
 * Без разрешения на уведомления ничего не показывается — остаётся плашка в приложении.
 */
class AndroidUpdateNotifier(context: Context) : UpdateNotifier {
    private val appContext = context.applicationContext

    override fun updateReady(versionName: String) {
        val open = Intent(appContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_INSTALL_UPDATE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        post(
            id = NOTIFICATION_READY,
            text = "Обновление $versionName готово — нажмите, чтобы установить",
            tap = PendingIntent.getActivity(appContext, NOTIFICATION_READY, open, IMMUTABLE_UPDATE),
        )
    }

    override fun confirmationNeeded(versionName: String, intent: Intent) {
        val confirmation = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        post(
            id = NOTIFICATION_READY,
            text = "Обновление $versionName готово — нажмите, чтобы установить",
            tap = PendingIntent.getActivity(appContext, NOTIFICATION_READY, confirmation, IMMUTABLE_UPDATE),
        )
    }

    override fun installed(versionName: String) {
        val open = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        cancelReady()
        post(
            id = NOTIFICATION_INSTALLED,
            text = "Zapret KVN обновлён до $versionName",
            tap = PendingIntent.getActivity(appContext, NOTIFICATION_INSTALLED, open, IMMUTABLE_UPDATE),
            timeoutMillis = INSTALLED_TIMEOUT_MILLIS,
        )
    }

    private fun cancelReady() {
        appContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_READY)
    }

    private fun post(id: Int, text: String, tap: PendingIntent, timeoutMillis: Long = 0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Обновления", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle("Zapret KVN")
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .apply { if (timeoutMillis > 0) setTimeoutAfter(timeoutMillis) }
            .build()
        runCatching { manager.notify(id, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_READY = 4101
        const val NOTIFICATION_INSTALLED = 4102
        const val INSTALLED_TIMEOUT_MILLIS = 6L * 60 * 60 * 1000
        const val IMMUTABLE_UPDATE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
