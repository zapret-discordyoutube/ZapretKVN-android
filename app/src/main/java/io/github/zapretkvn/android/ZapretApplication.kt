package io.github.zapretkvn.android

import android.app.Application
import android.os.Build
import io.github.zapretkvn.android.diagnostics.RuntimeLog
import io.github.zapretkvn.android.diagnostics.AppCrashStore
import io.github.zapretkvn.android.network.BrowserDohTransport
import io.github.zapretkvn.networkbootstrap.DohTransports
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
        RuntimeLog.install(
            File(noBackupFilesDir, "runtime-log"),
            "приложение ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}, " +
                "${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}",
        )
        // DoH имён серверов — стеком Chromium (как браузер), GET ?dns=. Ядро загружается
        // только при первом запросе; без Cronet транспорт сам уступит прежнему пути.
        DohTransports.preferred = BrowserDohTransport
    }
}
