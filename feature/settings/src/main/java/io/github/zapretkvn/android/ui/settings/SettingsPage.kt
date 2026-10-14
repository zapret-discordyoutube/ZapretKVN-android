package io.github.zapretkvn.android.ui.settings

/**
 * Вложенные страницы настроек. `id` — то, что лежит в стеке навигации
 * (`Route.SettingsPage(page)`), поэтому менять его нельзя без миграции стека.
 */
enum class SettingsPage(val id: String, val title: String) {
    Dns("dns", "Сеть и DNS"),
    Automation("automation", "Автоматизация VPN"),
    Services("services", "Проверка сервисов"),
    Hiding("hiding", "Скрытие VPN"),
    Diagnostics("diagnostics", "Диагностика"),
    Updates("updates", "Обновления"),
    Community("community", "Сообщество"),
    About("about", "О приложении"),
    Advanced("advanced", "Дополнительно"),
    ;

    companion object {
        fun fromId(id: String): SettingsPage? = entries.firstOrNull { it.id == id }
    }
}
