package io.github.zapretkvn.android.clientreports

/**
 * Отчёты выключаются только для прогонов тестов (docs/CLIENT_REPORTS.md, «Test runs send nothing»): тестовый
 * раннер [PROPERTY] ставит до создания приложения, потому что тесты на устройстве подключаются к локальным
 * тестовым серверам и их отказы ушли бы в рабочий приёмник как отчёты о чужих серверах.
 */
internal object ClientReportSwitch {
    const val PROPERTY = "zapret.kvn.client_reports"

    fun enabled(value: String? = System.getProperty(PROPERTY)): Boolean =
        value?.trim()?.lowercase() !in setOf("off", "0", "false", "no")
}
