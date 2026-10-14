package io.github.zapretkvn.android.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import io.github.zapretkvn.android.ZapretApplication

/** Принимает итог сессии установки: тихий успех, просьбу подтвердить или отказ. Не экспортируется. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PackageInstallerUpdateInstaller.ACTION_INSTALL_STATUS) return
        // Итог чужой или уже брошенной сессии (в том числе нашей прошлой) не относится к текущей установке.
        val prefs = SharedUpdatePrefs(context)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId == -1 || sessionId != prefs.installingSessionId) return
        val controller = (context.applicationContext as ZapretApplication).container.updateController
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val systemMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val result = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateInstallResult.Success
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) {
                    UpdateInstallResult.Failed(
                        "Android не показал подтверждение установки.",
                        "status=$status: $systemMessage",
                    )
                } else {
                    UpdateInstallResult.NeedsConfirmation(confirmation)
                }
            }
            else -> UpdateInstallResult.Failed(
                installFailureMessage(status),
                "status=$status: $systemMessage",
            )
        }
        if (result !is UpdateInstallResult.NeedsConfirmation) prefs.installingSessionId = -1
        controller.onInstallResult(result)
    }

    internal companion object {
        fun installFailureMessage(status: Int): String = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Установка отменена."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Установка заблокирована настройками Android или администратором."
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android не принял обновление: подпись или версия не подходят."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Обновление несовместимо с этим устройством."
            PackageInstaller.STATUS_FAILURE_INVALID -> "Android отклонил файл обновления."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места для установки обновления."
            else -> "Не удалось установить обновление."
        }
    }
}
