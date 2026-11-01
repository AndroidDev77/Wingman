package com.wingman.core.ui.compose

import androidx.compose.runtime.staticCompositionLocalOf
import com.wingman.core.di.AppContainer

/**
 * The process [AppContainer], available to any composable via `LocalAppContainer.current`.
 * Provided once by [com.wingman.navigation.WingmanApp]. Previews provide [AppContainer.mock].
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("LocalAppContainer not provided; wrap content in WingmanApp or CompositionLocalProvider")
}
