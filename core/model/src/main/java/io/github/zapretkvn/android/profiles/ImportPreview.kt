package io.github.zapretkvn.android.profiles

data class SplitRefreshSummary(
    val updated: Int,
    val added: Int,
    val removed: Int,
    val connectedProfileRemoved: Boolean,
)

/**
 * Что экран предпросмотра импорта знает о разобранном источнике. Внутренности
 * разбора (кандидат, готовый JSON, план обновления) остаются у реализации в :app.
 */
interface ImportPreview {
    val suggestedName: String
    val sourceDescription: String
    val serverCount: Int
    val serverLabels: List<String>
    val activityWarning: String?
    val appendTargets: List<ProfileMetadata>
    val importWarnings: List<String>
    val refreshProfileName: String?
    val activeRefresh: Boolean
    val selectionChanged: Boolean
    val splitRefreshSummary: SplitRefreshSummary?
    val isSingleManaged: Boolean
    val isRefresh: Boolean

    /** Сколько отдельных профилей получится, если разложить подписку по серверам. */
    val splittableServerCount: Int
    val splitSupported: Boolean
    val hasSubscriptionUrl: Boolean
}
