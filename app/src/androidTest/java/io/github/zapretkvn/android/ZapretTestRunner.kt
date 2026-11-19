package io.github.zapretkvn.android

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.github.zapretkvn.android.clientreports.ClientReportSwitch

/**
 * Раннер тестов на устройстве. Тесты подключаются к локальным тестовым серверам, поэтому отчёты о подключениях
 * выключаются до создания приложения: [onCreate] раннера идёт раньше Application.onCreate, а AppContainer
 * создаётся лениво (docs/CLIENT_REPORTS.md, «Test runs send nothing»).
 */
class ZapretTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        System.setProperty(ClientReportSwitch.PROPERTY, "off")
        super.onCreate(arguments)
    }
}
