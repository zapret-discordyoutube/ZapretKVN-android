package io.github.zapretkvn.android.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.zapretkvn.android.MainActivity
import io.github.zapretkvn.android.R
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.platform.VpnSystemPolicyDetector
import io.github.zapretkvn.android.vpn.runtime.ForegroundState
import io.github.zapretkvn.android.vpn.runtime.RuntimeHost
import io.github.zapretkvn.android.vpn.runtime.VpnRuntime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android-оболочка VPN: переводит Intent-команды в [VpnRuntime], держит
 * foreground-уведомление и отдаёт системе TUN. Всё состояние подключения —
 * в рантайме.
 */
class ZapretVpnService : VpnService() {
    private val foregroundActive = AtomicBoolean(false)
    private val container by lazy { (application as ZapretApplication).container }
    private lateinit var runtime: VpnRuntime
    private val host = object : RuntimeHost {
        override fun showForeground(state: ForegroundState, detail: String?) =
            this@ZapretVpnService.showForeground(state, detail)

        override fun finishForeground() = this@ZapretVpnService.finishForeground()

        override fun stopSelfResult(startId: Int) {
            this@ZapretVpnService.stopSelfResult(startId)
        }

        override fun stopSelf() = this@ZapretVpnService.stopSelf()
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> runtime.onScreenInteractive(true)
                Intent.ACTION_SCREEN_OFF -> runtime.onScreenInteractive(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runtime = VpnRuntime(this, container, host)
        runtime.onScreenInteractive(isDefaultDisplayOn())
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Каждая команда приходит через startForegroundService: foreground обязателен сразу.
        if (!foregroundActive.get()) showForeground(ForegroundState.Preparing)
        when (intent?.action) {
            ACTION_START -> runtime.start(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                startId = startId,
                updaterRouting = intent.getBooleanExtra(EXTRA_UPDATER_ROUTING, false),
            )
            ACTION_SELECT -> runtime.select(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                groupTag = intent.getStringExtra(EXTRA_GROUP_TAG).orEmpty(),
                outboundTag = intent.getStringExtra(EXTRA_OUTBOUND_TAG).orEmpty(),
                startId = startId,
            )
            ACTION_RESTART -> runtime.restart(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                reason = intent.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "Перезапуск VPN" },
                startId = startId,
                updaterRouting = intent.takeIf { it.hasExtra(EXTRA_UPDATER_ROUTING) }
                    ?.getBooleanExtra(EXTRA_UPDATER_ROUTING, false),
            )
            ACTION_CLEAR_DNS_CACHE -> runtime.clearDnsCache(startId)
            ACTION_PING_GROUP -> runtime.pingGroup(
                profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty(),
                groupTag = intent.getStringExtra(EXTRA_GROUP_TAG).orEmpty(),
                startId = startId,
            )
            ACTION_STOP -> runtime.stop(startId, null)
            ACTION_RESUME -> runtime.resume(startId)
            else -> {
                val policy = VpnSystemPolicyDetector.detect(this)
                runtime.stop(startId, policy.blockingMessage, policy)
            }
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        runtime.stop(startId = 0, errorMessage = "Разрешение Android VPN отозвано.", trigger = "permission_revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenStateReceiver) }
        val finalState = runtime.destroy()
        finishForeground()
        finalState?.let { container.vpnController.publish(container.vpnController.currentGeneration(), it) }
        super.onDestroy()
    }

    private fun isDefaultDisplayOn(): Boolean =
        getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.state
            ?.let { it == Display.STATE_ON } ?: true

    /** [detail] — пояснение к состоянию до следующего обновления уведомления. */
    private fun showForeground(state: ForegroundState, detail: String? = null) {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = PendingIntent.getService(
            this,
            2,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle("Zapret KVN")
            .setContentText(detail?.let { "${state.text} · $it" } ?: state.text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Открыть", openIntent)
        if (state == ForegroundState.Connected) {
            (container.vpnController.state.value as? VpnConnectionState.Connected)?.let { connected ->
                val restartAction = PendingIntent.getService(
                    this,
                    3,
                    restartIntent(this, connected.profileId, "Перезапуск из уведомления"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, "Перезапустить", restartAction)
            }
        } else if (state == ForegroundState.Paused) {
            val resumeAction = PendingIntent.getService(
                this,
                4,
                resumeIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, "Подключить сейчас", resumeAction)
        }
        val notification = builder.addAction(0, "Остановить", stopAction).build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        foregroundActive.set(true)
    }

    private fun finishForeground() {
        if (!foregroundActive.compareAndSet(true, false)) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Состояние VPN и действие остановки"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val ACTION_START = "io.github.zapretkvn.android.vpn.START"
        private const val ACTION_STOP = "io.github.zapretkvn.android.vpn.STOP"
        private const val ACTION_RESUME = "io.github.zapretkvn.android.vpn.RESUME"
        private const val ACTION_SELECT = "io.github.zapretkvn.android.vpn.SELECT"
        private const val ACTION_RESTART = "io.github.zapretkvn.android.vpn.RESTART"
        private const val ACTION_CLEAR_DNS_CACHE = "io.github.zapretkvn.android.vpn.CLEAR_DNS_CACHE"
        private const val ACTION_PING_GROUP = "io.github.zapretkvn.android.vpn.PING_GROUP"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val EXTRA_GROUP_TAG = "group_tag"
        private const val EXTRA_OUTBOUND_TAG = "outbound_tag"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_UPDATER_ROUTING = "updater_routing"
        private const val NOTIFICATION_CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(context: Context, profileId: String, updaterRouting: Boolean = false): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_UPDATER_ROUTING, updaterRouting)

        fun stopIntent(context: Context): Intent =
            Intent(context, ZapretVpnService::class.java).setAction(ACTION_STOP)

        fun resumeIntent(context: Context): Intent =
            Intent(context, ZapretVpnService::class.java).setAction(ACTION_RESUME)

        fun selectIntent(context: Context, profileId: String, groupTag: String, outboundTag: String): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_SELECT)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_GROUP_TAG, groupTag)
                .putExtra(EXTRA_OUTBOUND_TAG, outboundTag)

        fun restartIntent(
            context: Context,
            profileId: String,
            reason: String,
            updaterRouting: Boolean? = null,
        ): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_RESTART)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_REASON, reason)
                .apply { updaterRouting?.let { putExtra(EXTRA_UPDATER_ROUTING, it) } }

        fun clearDnsCacheIntent(context: Context): Intent =
            Intent(context, ZapretVpnService::class.java).setAction(ACTION_CLEAR_DNS_CACHE)

        fun pingGroupIntent(context: Context, profileId: String, groupTag: String): Intent =
            Intent(context, ZapretVpnService::class.java)
                .setAction(ACTION_PING_GROUP)
                .putExtra(EXTRA_PROFILE_ID, profileId)
                .putExtra(EXTRA_GROUP_TAG, groupTag)
    }
}
