package com.wingman.core.ui.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wingman.core.api.ApiResult
import com.wingman.core.ui.LoadableState
import com.wingman.core.ui.UiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal ViewModel around [LoadableState]: exposes `state: StateFlow<UiState<T>>`, loads on
 * creation, and offers [retry] / [refresh]. Feature ViewModels can extend it or compose the pure
 * holders ([LoadableState], [com.wingman.core.ui.CursorPager], [com.wingman.core.ui.ActionRunner])
 * directly with `viewModelScope`.
 */
open class LoadableViewModel<T>(
    isEmpty: (T) -> Boolean = { false },
    loader: suspend () -> ApiResult<T>,
) : ViewModel() {
    private val holder = LoadableState(viewModelScope, isEmpty, loader)
    val state: StateFlow<UiState<T>> = holder.state

    init {
        holder.load()
    }

    fun retry() = holder.load()
    fun refresh() = holder.refresh()
}

/** Creates/retrieves a [ViewModel] with a manual-DI factory (no Hilt). [key] scopes per argument, e.g. a conversation ID. */
@Composable
inline fun <reified VM : ViewModel> wingmanViewModel(key: String? = null, noinline create: () -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create() } })
