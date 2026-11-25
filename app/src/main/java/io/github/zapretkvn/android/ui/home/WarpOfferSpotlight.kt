package io.github.zapretkvn.android.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * «Включить вход через WARP» с карточки ошибки подключения (владелец 2026-11-26): главная просит страницу
 * «Дополнительно» подсветить переключатель, страница снимает просьбу, когда подсветка отработала или переключатель нажат.
 */
object WarpOfferSpotlight {
    private val requestedState = MutableStateFlow(false)
    val requested: StateFlow<Boolean> get() = requestedState

    fun request() {
        requestedState.value = true
    }

    fun clear() {
        requestedState.value = false
    }
}
