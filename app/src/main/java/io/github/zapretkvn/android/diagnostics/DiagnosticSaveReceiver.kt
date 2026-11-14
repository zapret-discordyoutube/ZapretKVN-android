package io.github.zapretkvn.android.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/** Принимает нажатие «Сохранить в Загрузки» из окна отправки диагностики и копирует архив, см. [DiagnosticDownloadsSaver]. */
class DiagnosticSaveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        thread(name = "diagnostic-save") {
            try {
                val source = File(File(appContext.cacheDir, DiagnosticExporter.DIRECTORY_NAME), DiagnosticExporter.FILE_NAME)
                val saved = DiagnosticDownloadsSaver.save(appContext, source)
                Log.i(TAG, if (saved != null) "diagnostic copied to Downloads: $saved" else "diagnostic copy to Downloads failed")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(
                        appContext,
                        saved?.let { "Сохранено в Загрузки: $it" } ?: "Не удалось сохранить в Загрузки",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ZapretDiagnosticSave"
    }
}
