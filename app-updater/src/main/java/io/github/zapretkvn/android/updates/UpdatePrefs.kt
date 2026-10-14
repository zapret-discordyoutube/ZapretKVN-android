package io.github.zapretkvn.android.updates

import android.content.Context
import android.content.SharedPreferences

/**
 * Память маршрута и несколько отметок updater в собственном файле `updater_prefs`.
 * Ничего, кроме номеров версий и имени маршрута, здесь не хранится.
 */
interface UpdatePrefs {
    var lastRoute: UpdateRoute?

    /** Версия (versionCode), для которой пользователь скрыл плашку на Главной. */
    var bannerDismissedVersionCode: Long

    /** versionCode, установка которого передана системе; 0 — ничего не ожидается. */
    var installingVersionCode: Long
    var installingVersionName: String?

    /**
     * Сессия `PackageInstaller`, итог которой ждёт приложение; -1 — никакой. Итоги чужих и брошенных
     * сессий (например, нашей же прошлой, ожидавшей подтверждения) игнорируются.
     */
    var installingSessionId: Int

    /**
     * versionCode, для которого уже была тихая попытка установки. Если после неё версия не появилась,
     * система держит сессию (бывает, пока приложение «в использовании»): повторять тихо не нужно,
     * пользователю надо показать «готово — нажмите, чтобы установить».
     */
    var silentAttemptVersionCode: Long
}

class InMemoryUpdatePrefs : UpdatePrefs {
    @Volatile
    override var lastRoute: UpdateRoute? = null

    @Volatile
    override var bannerDismissedVersionCode: Long = 0

    @Volatile
    override var installingVersionCode: Long = 0

    @Volatile
    override var installingVersionName: String? = null

    @Volatile
    override var installingSessionId: Int = -1

    @Volatile
    override var silentAttemptVersionCode: Long = 0
}

class SharedUpdatePrefs(context: Context) : UpdatePrefs {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override var lastRoute: UpdateRoute?
        get() = prefs.getString(KEY_ROUTE, null)?.let { name -> UpdateRoute.entries.firstOrNull { it.name == name } }
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_ROUTE) else putString(KEY_ROUTE, value.name) }.apply()
        }

    override var bannerDismissedVersionCode: Long
        get() = prefs.getLong(KEY_DISMISSED, 0)
        set(value) {
            prefs.edit().putLong(KEY_DISMISSED, value).apply()
        }

    override var installingVersionCode: Long
        get() = prefs.getLong(KEY_INSTALLING_CODE, 0)
        set(value) {
            prefs.edit().putLong(KEY_INSTALLING_CODE, value).apply()
        }

    override var installingVersionName: String?
        get() = prefs.getString(KEY_INSTALLING_NAME, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_INSTALLING_NAME) else putString(KEY_INSTALLING_NAME, value)
            }.apply()
        }

    override var installingSessionId: Int
        get() = prefs.getInt(KEY_INSTALLING_SESSION, -1)
        set(value) {
            prefs.edit().putInt(KEY_INSTALLING_SESSION, value).apply()
        }

    override var silentAttemptVersionCode: Long
        get() = prefs.getLong(KEY_SILENT_ATTEMPT, 0)
        set(value) {
            prefs.edit().putLong(KEY_SILENT_ATTEMPT, value).apply()
        }

    private companion object {
        const val FILE = "updater_prefs"
        const val KEY_SILENT_ATTEMPT = "silent_attempt_version_code"
        const val KEY_INSTALLING_SESSION = "installing_session_id"
        const val KEY_ROUTE = "last_route"
        const val KEY_DISMISSED = "banner_dismissed_version_code"
        const val KEY_INSTALLING_CODE = "installing_version_code"
        const val KEY_INSTALLING_NAME = "installing_version_name"
    }
}
