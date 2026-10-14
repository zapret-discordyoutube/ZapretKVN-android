package io.github.zapretkvn.android.ui.settings

/**
 * Сведения о сборке. Экраны получают их аргументом и не обращаются к `BuildConfig`
 * и `Build.VERSION`: значения читает :app (см. `appInfoFromBuild`).
 */
data class AppInfo(
    val versionName: String,
    val versionCode: Long,
    val coreTag: String,
    val coreCommit: String,
    val hysteriaTag: String,
    val hysteriaCommit: String,
    val corePatch: String,
    val androidRelease: String,
    val sdkInt: Int,
)
