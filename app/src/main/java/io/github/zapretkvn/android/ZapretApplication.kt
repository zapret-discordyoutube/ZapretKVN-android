package io.github.zapretkvn.android

import android.app.Application
import io.github.zapretkvn.android.diagnostics.AppCrashStore
import java.io.File

class ZapretApplication : Application() {
    private val crashStore: AppCrashStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppCrashStore(File(noBackupFilesDir, "diagnostics"))
    }

    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(applicationContext, crashStore)
    }

    override fun onCreate() {
        super.onCreate()
        crashStore.install()
        // DoH имён серверов стеком Chromium (BrowserDohTransport) выключен: при запуске
        // движка на эмуляторе случается SIGSEGV (c23/c24, недетерминированно). Вернуть
        // после разбора: DohTransports.preferred = BrowserDohTransport
    }
}
