package com.wingman.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wingman.core.di.AppContainer
import com.wingman.core.ui.compose.LocalAppContainer
import com.wingman.ui.theme.WingmanTheme

/** App root: theme + DI + scaffold with bottom navigation + NavHost. */
@Composable
fun WingmanApp(
    container: AppContainer,
    screens: ScreenRegistry<ScreenContent> = remember { featureScreens() },
    navController: NavHostController = rememberNavController(),
) {
    WingmanTheme {
        CompositionLocalProvider(LocalAppContainer provides container) {
            val navigator = remember(navController) { NavControllerNavigator(navController) }
            val backStack by navController.currentBackStackEntryAsState()
            val current = Destination.fromRoute(backStack?.destination?.route)
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Destination.topLevelDestinations.forEach { dest ->
                            NavigationBarItem(
                                selected = current == dest,
                                onClick = { navigator.navigateTo(dest) },
                                icon = { Icon(dest.icon(), contentDescription = null) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                WingmanNavHost(navController, navigator, screens, Modifier.padding(padding))
            }
        }
    }
}

@Composable
fun WingmanNavHost(
    navController: NavHostController,
    navigator: Navigator,
    screens: ScreenRegistry<ScreenContent>,
    modifier: Modifier = Modifier,
) {
    NavHost(navController, startDestination = Destination.START.pattern, modifier = modifier) {
        Destination.entries.forEach { dest ->
            val arguments = if (dest == Destination.Conversation) {
                listOf(navArgument(Routes.ARG_CONVERSATION_ID) { type = NavType.StringType })
            } else {
                emptyList()
            }
            composable(dest.pattern, arguments = arguments) { entry ->
                val args = ScreenArgs(conversationId = entry.arguments?.getString(Routes.ARG_CONVERSATION_ID))
                val registered = screens.resolve(dest)
                when {
                    registered != null -> registered(args, navigator)
                    dest == Destination.Dashboard -> DashboardScreen(navigator)
                    else -> PlaceholderScreen(dest, args, navigator)
                }
            }
        }
    }
}

/** [Navigator] backed by a NavController. Top-level switches keep one copy of each tab on the stack. */
class NavControllerNavigator(private val nav: NavHostController) : Navigator {
    override fun navigateTo(destination: Destination) {
        if (destination == Destination.Conversation) return // needs an ID: use openConversation
        nav.navigate(destination.pattern) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    override fun openConversation(conversationId: String) {
        // Invalid IDs are ignored rather than crashing the app.
        if (Routes.isValidId(conversationId)) nav.navigate(Routes.conversation(conversationId))
    }

    override fun back() {
        nav.popBackStack()
    }
}

private fun Destination.icon(): ImageVector = when (this) {
    Destination.Dashboard -> Icons.Filled.Home
    Destination.Inbox -> Icons.Filled.Email
    Destination.Approvals -> Icons.Filled.CheckCircle
    Destination.Discovery -> Icons.Filled.Favorite
    Destination.Settings -> Icons.Filled.Settings
    Destination.Conversation -> Icons.Filled.Email
}

@Preview(name = "App shell (mock API)", showSystemUi = true)
@Composable
private fun WingmanAppPreview() = WingmanApp(AppContainer.mock())

@Preview(name = "App shell dark (mock API)", showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WingmanAppDarkPreview() = WingmanApp(AppContainer.mock())
