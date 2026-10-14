package io.github.zapretkvn.android.ui.settings

import android.os.Build
import io.github.zapretkvn.android.BuildConfig

/** Сведения о сборке, прочитанные один раз: экраны настроек получают их аргументом. */
internal val CurrentAppInfo: AppInfo = AppInfo(
    versionName = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE.toLong(),
    coreTag = BuildConfig.CORE_TAG,
    coreCommit = BuildConfig.CORE_COMMIT,
    hysteriaTag = BuildConfig.HYSTERIA_CORE_TAG,
    hysteriaCommit = BuildConfig.HYSTERIA_CORE_COMMIT,
    corePatch = BuildConfig.CORE_PATCH_SHA256,
    androidRelease = Build.VERSION.RELEASE.orEmpty(),
    sdkInt = Build.VERSION.SDK_INT,
)
