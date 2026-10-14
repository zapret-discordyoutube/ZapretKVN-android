package io.github.zapretkvn.android.ui.settings

import android.os.Build
import io.github.zapretkvn.android.BuildConfig

/**
 * Сведения о сборке, прочитанные один раз. Экраны берут их отсюда и не
 * обращаются к `BuildConfig` и `Build.VERSION` из composable.
 */
internal object AppInfo {
    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Long = BuildConfig.VERSION_CODE.toLong()
    val coreTag: String = BuildConfig.CORE_TAG
    val coreCommit: String = BuildConfig.CORE_COMMIT
    val hysteriaTag: String = BuildConfig.HYSTERIA_CORE_TAG
    val hysteriaCommit: String = BuildConfig.HYSTERIA_CORE_COMMIT
    val corePatch: String = BuildConfig.CORE_PATCH_SHA256
    val androidRelease: String = Build.VERSION.RELEASE.orEmpty()
    val sdkInt: Int = Build.VERSION.SDK_INT
}
