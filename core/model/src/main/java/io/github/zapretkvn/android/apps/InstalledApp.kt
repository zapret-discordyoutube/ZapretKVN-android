package io.github.zapretkvn.android.apps

data class InstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val enabled: Boolean,
    val suggestion: String? = null,
)
