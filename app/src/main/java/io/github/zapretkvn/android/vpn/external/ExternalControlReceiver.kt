package io.github.zapretkvn.android.vpn.external

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.zapretkvn.android.AppContainer
import io.github.zapretkvn.android.ZapretApplication
import io.github.zapretkvn.android.ui.ExternalCommand
import io.github.zapretkvn.android.ui.ExternalControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Внешнее управление VPN из других приложений (Tasker и подобные). Приёмник отключён в
 * манифесте и включается только пока владелец держит переключатель в настройках
 * ([ExternalControlComponent]); каждый вызов проверяется ещё и по секрету. Чужие и
 * неверные вызовы молча игнорируются: ответа, по которому можно угадать секрет, нет.
 *
 * Запуск из фона разрешён Android, пока у приложения есть согласие на VPN
 * (`OP_ACTIVATE_VPN`); без согласия диалог из приёмника показать нельзя, поэтому
 * запуск просто не происходит.
 */
class ExternalControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in ExternalControl.ACTIONS) return
        val token = intent.getStringExtra(ExternalControl.EXTRA_TOKEN)
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                handle(appContext, (appContext as ZapretApplication).container, action, token)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, container: AppContainer, action: String, token: String?) {
        val settings = container.uiSettingsStore.settings.first()
        if (!ExternalControl.accepts(settings.externalControl, token)) return
        val controller = container.vpnController
        when (ExternalControl.commandFor(action, controller.state.value)) {
            ExternalCommand.Stop -> controller.stop()
            ExternalCommand.Resume -> controller.resumePaused()
            ExternalCommand.Start -> startActiveProfile(context, container, settings.activeProfileId)
            ExternalCommand.Nothing -> Unit
        }
    }

    private suspend fun startActiveProfile(context: Context, container: AppContainer, activeProfileId: String?) {
        val profileId = withContext(Dispatchers.IO) {
            val profiles = container.profileStore.initialize()
            activeProfileId?.takeIf { id -> profiles.any { it.id == id } }
        } ?: return
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        // Нет согласия на VPN или права на уведомления: диалог отсюда не показать, не стартуем.
        if (needsNotificationPermission || container.vpnController.permissionIntent() != null) return
        container.vpnController.start(profileId)
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}

/**
 * Включает и выключает сам приёмник. Выключенное состояние — значение манифеста
 * (`android:enabled="false"`), поэтому «по умолчанию» и «выключено» это одно и то же.
 */
object ExternalControlComponent {
    fun apply(context: Context, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, ExternalControlReceiver::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }
}
