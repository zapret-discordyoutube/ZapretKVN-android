package io.github.zapretkvn.android.importer

/**
 * Совместимость с панелями, которые считают устройства по заголовку `X-HWID`
 * (Remnawave и производные). Заголовки собираются здесь, чтобы unit-тесты
 * работали без Android SDK: всё платформенное приходит через [DeviceIdentity].
 */
enum class SubscriptionClientProfile(val id: String) {
    Zapret("zapret"),
    Happ("happ"),
    Incy("incy"),
    V2RayTun("v2raytun"),
    Custom("custom"),
    ;

    companion object {
        fun parse(value: String?): SubscriptionClientProfile {
            val normalized = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == normalized } ?: Custom
        }
    }
}
