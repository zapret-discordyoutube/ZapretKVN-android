package io.github.zapretkvn.android.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Установка через сессию `PackageInstaller` вместо открытия системного окна с APK.
 *
 * Фоновая установка (`silent`) на Android 12+ просит `USER_ACTION_NOT_REQUIRED`: если приложение уже ставило
 * себя (установщик по записи — мы) и остальные условия системы выполнены, обновление встаёт без вопросов.
 * Иначе система вернёт `STATUS_PENDING_USER_ACTION`, и приложение предложит одно нажатие. Установка по
 * нажатию пользователя всегда просит подтверждение: приложение на экране система тихо не обновляет, а
 * тихая сессия при этом может надолго зависнуть (наблюдалось на эмуляторе API 37).
 * Владение обновлениями (`setRequestUpdateOwnership`) для уже установленного приложения ничего не
 * делает и требует отдельного разрешения, поэтому не запрашивается.
 */
class PackageInstallerUpdateInstaller(context: Context) : UpdateInstaller {
    private val appContext = context.applicationContext

    override fun install(file: File, expectedSha256: String, candidate: UpdateCandidate, silent: Boolean) {
        val packageInstaller = appContext.packageManager.packageInstaller
        try {
            SharedUpdatePrefs(appContext).installingSessionId = -1
            abandonOwnSessions(packageInstaller)
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(appContext.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        if (silent) {
                            PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        } else {
                            PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                        },
                    )
                }
            }
            val sessionId = packageInstaller.createSession(params)
            // Ещё до commit: итог брошенной предыдущей сессии не должен сойти за итог этой.
            SharedUpdatePrefs(appContext).installingSessionId = sessionId
            val session = packageInstaller.openSession(sessionId)
            var committed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    session.openWrite(SESSION_ENTRY, 0, file.length()).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        session.fsync(output)
                    }
                }
                // Сверка по тем байтам, что реально ушли в сессию: расхождение — сессия не коммитится.
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expectedSha256) {
                    throw UpdateException("SHA-256 APK не совпадает с опубликованным.")
                }
                session.commit(statusIntentSender(sessionId))
                committed = true
            } finally {
                session.close()
                if (!committed) {
                    SharedUpdatePrefs(appContext).installingSessionId = -1
                    runCatching { packageInstaller.abandonSession(sessionId) }
                }
            }
        } catch (error: UpdateException) {
            throw error
        } catch (error: IOException) {
            throw UpdateException("Не удалось передать обновление системному установщику.", error)
        } catch (error: SecurityException) {
            throw UpdateException("Android запретил установку обновления.", error)
        } catch (error: RuntimeException) {
            throw UpdateException("Не удалось открыть сессию установки.", error)
        }
    }

    private fun abandonOwnSessions(packageInstaller: PackageInstaller) {
        for (info in packageInstaller.mySessions) {
            runCatching { packageInstaller.abandonSession(info.sessionId) }
        }
    }

    private fun statusIntentSender(sessionId: Int) = PendingIntent.getBroadcast(
        appContext,
        sessionId,
        Intent(appContext, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
        // Система дописывает в Intent статус и intent подтверждения, поэтому PendingIntent изменяемый.
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
    ).intentSender

    companion object {
        const val ACTION_INSTALL_STATUS = "io.github.zapretkvn.android.action.UPDATE_INSTALL_STATUS"
        private const val SESSION_ENTRY = "base.apk"
        private const val BUFFER_BYTES = 256 * 1024
    }
}
