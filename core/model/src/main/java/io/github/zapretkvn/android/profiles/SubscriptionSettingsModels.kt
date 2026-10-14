package io.github.zapretkvn.android.profiles

import io.github.zapretkvn.android.importer.SubscriptionClientProfile

/** Настройки идентификации подписки, редактируемые пользователем. */
data class SubscriptionIdentityInput(
    val clientProfile: SubscriptionClientProfile? = null,
    val sendHwid: Boolean = false,
    val hwid: String = "",
)

data class SubscriptionSettingsState(
    val profileId: String,
    val profileName: String,
    val url: String,
    val clientProfile: SubscriptionClientProfile,
    val sendHwid: Boolean,
    val hwid: String,
    val installationHwid: String,
)

/** Верхняя граница разложения подписки: дальше список профилей перестаёт быть управляемым. */
const val MAX_SPLIT_PROFILES = 200
