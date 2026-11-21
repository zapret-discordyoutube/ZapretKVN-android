package io.github.zapretkvn.android.clientreports

import io.github.zapretkvn.android.BuildConfig

/**
 * Отчёты шлёт только выпускная сборка (docs/CLIENT_REPORTS.md, «Test runs send nothing»): debug-сборка (эмулятор,
 * проверки на стенде) не шлёт ничего, а тестовый раннер ещё и ставит [PROPERTY] до создания приложения, потому что
 * тесты на устройстве подключаются к локальным тестовым серверам и их отказы ушли бы в рабочий приёмник.
 * 2026-11-20 тестовые прогоны Windows дважды прислали в рабочий приёмник ошибки приложения сборки разработчика.
 */
internal object ClientReportSwitch {
    const val PROPERTY = "zapret.kvn.client_reports"

    fun enabled(value: String? = System.getProperty(PROPERTY), debug: Boolean = BuildConfig.DEBUG): Boolean =
        !debug && value?.trim()?.lowercase() !in setOf("off", "0", "false", "no")
}
