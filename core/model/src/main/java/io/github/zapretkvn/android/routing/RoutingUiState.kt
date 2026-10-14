package io.github.zapretkvn.android.routing

data class RoutingUiState(
    val activeProfileId: String? = null,
    val activeProfileName: String? = null,
    val inspection: RoutingInspection? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val managedDiff: String? = null,
)
