package io.github.zapretkvn.android.diagnostics

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Копия диагностического архива в «Загрузки»: действие в окне отправки (Android 14+, «Сохранить в Загрузки»).
 *
 * Зачем: релизная сборка не отдаёт приватный каталог (`run-as` закрыт), а передача через мессенджер требует, чтобы человек
 * пересылал файл вручную каждый раз. Файл с известным именем лежит в `/sdcard/Download`, его забирает `adb pull`
 * (владелец 2026-11-14: «пересылать файл мне как-то всё равно надо»). Делает копию только по явному нажатию в окне
 * отправки, сам архив тот же, что уходит в мессенджер (домены и IP в нём, экран экспорта об этом говорит).
 */
object DiagnosticDownloadsSaver {
    const val NAME_PREFIX = "zapret-kvn-diagnostic-"

    /** Имя файла: префикс, время UTC и расширение. Время в имени, чтобы повторное сохранение не ломало прежнее. */
    fun fileName(nowMillis: Long): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return NAME_PREFIX + format.format(Date(nowMillis)) + ".zip"
    }

    /** Копирует [source] в «Загрузки» через MediaStore (разрешений не нужно). Возвращает имя файла или `null`. */
    fun save(context: Context, source: File, nowMillis: Long = System.currentTimeMillis()): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !source.isFile) return null
        val name = fileName(nowMillis)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            name
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
