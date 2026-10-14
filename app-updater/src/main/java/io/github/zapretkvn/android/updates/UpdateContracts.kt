package io.github.zapretkvn.android.updates

import android.content.Intent
import java.io.File

/**
 * Передаёт проверенный APK системному установщику (в приложении это сессия `PackageInstaller`).
 * Вызов только отдаёт файл и коммитит сессию; итог приходит позже в [UpdateController.onInstallResult].
 * Реализация обязана сама сверить SHA-256 тех байт, что уходят в сессию, и не коммитить при расхождении.
 */
fun interface UpdateInstaller {
    /**
     * @param silent попросить систему не спрашивать пользователя (Android 12+, если условия системы выполнены).
     *   Только для фоновой установки: на экране приложение так не обновляется, поэтому по нажатию — `false`.
     */
    @Throws(UpdateException::class)
    fun install(file: File, expectedSha256: String, candidate: UpdateCandidate, silent: Boolean)
}

/** Итог установки, который системный установщик присылает широковещательным сообщением. */
sealed interface UpdateInstallResult {
    data object Success : UpdateInstallResult

    /** Android просит подтверждение: [intent] запускается только по нажатию пользователя. */
    data class NeedsConfirmation(val intent: Intent) : UpdateInstallResult
    data class Failed(val message: String, val detail: String? = null) : UpdateInstallResult
}

/** Тихие уведомления updater. Реализация обязана сама проверить разрешение на уведомления. */
interface UpdateNotifier {
    /** APK проверен и ждёт установки; приложение в фоне. */
    fun updateReady(versionName: String)

    /** Android запросил подтверждение; [intent] запускается нажатием на уведомление. */
    fun confirmationNeeded(versionName: String, intent: Intent)

    /** После установки приложение запущено уже в новой версии. */
    fun installed(versionName: String)
}

/** Сведения об устройстве и приложении, от которых зависит политика updater. Всё читается лениво. */
class UpdateEnvironment(
    /** VPN приложения сейчас подключён. */
    val vpnActive: () -> Boolean = { false },

    /** Сеть с лимитом трафика (мобильная). */
    val isMetered: () -> Boolean = { false },

    /** Можно ли поставить обновление без участия пользователя (и не оборвать его VPN-сессию). */
    val canInstallUnattended: () -> Boolean = { false },

    /** Открыт ли интерфейс приложения прямо сейчас. */
    val appInForeground: () -> Boolean = { true },

    /** Установленный APK: основа для дельта-загрузки. */
    val installedApk: File? = null,

    /** versionCode APK-файла в кэше; null, если определить нельзя. Нужен для уборки устаревших файлов. */
    val archiveVersionCode: (File) -> Long? = { null },
)

object UpdateAutoDownloadPolicy {
    /** На мобильной сети автоматически качается только небольшое обновление. */
    const val METERED_LIMIT_BYTES = 25L * 1024 * 1024

    fun allows(metered: Boolean, plannedBytes: Long): Boolean = !metered || plannedBytes <= METERED_LIMIT_BYTES
}
