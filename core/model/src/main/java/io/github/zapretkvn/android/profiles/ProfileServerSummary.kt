package io.github.zapretkvn.android.profiles

/** Тег управляемого selector приложения: общий для сборщика профилей и интерфейса. */
const val MANAGED_SELECTOR_TAG_NAME = "zapret-proxy"

/** Сервер внутри sing-box selector: то, что пользователь выбирает в GUI. */
data class ProfileServerOption(
    val tag: String,
    val type: String,
    val endpoint: String?,
) {
    val subtitle: String
        get() = listOfNotNull(
            type.uppercase().takeIf(String::isNotBlank),
            endpoint,
        ).joinToString(" · ")
}

/** Группа selector профиля вместе с текущим `default`. */
data class ProfileServerGroup(
    val tag: String,
    val selected: String?,
    val options: List<ProfileServerOption>,
)

data class ProfileServerSummary(
    val groups: List<ProfileServerGroup> = emptyList(),
) {
    val serverCount: Int get() = groups.sumOf { it.options.size }

    /** Переключать есть что только тогда, когда в группе больше одного сервера. */
    val switchable: Boolean get() = groups.any { it.options.size > 1 }

    val primaryGroup: ProfileServerGroup?
        get() = groups.firstOrNull { it.tag == MANAGED_SELECTOR_TAG_NAME }
            ?: groups.firstOrNull { it.options.size > 1 }
            ?: groups.firstOrNull()

    val selectedLabel: String?
        get() = primaryGroup?.let { group ->
            group.selected?.takeIf(String::isNotBlank) ?: group.options.firstOrNull()?.tag
        }
}
