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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.wingman.features.approvals.ApprovalsNavIcon
import com.wingman.features.approvals.LocalPendingBadge
import com.wingman.features.approvals.PendingBadgeState
import com.wingman.features.auth.AuthGate
import com.wingman.features.auth.SignInScreen
import com.wingman.features.onboarding.OnboardingGate
import com.wingman.ui.theme.WingmanTheme
import kotlinx.coroutines.flow.first

/** App root: theme + DI + scaffold with bottom navigation + NavHost. */
@Composable
fun WingmanApp(
    container: AppContainer,
    screens: ScreenRegistry<ScreenContent> = remember { featureScreens() },
    /** Null: the shell creates its own, so each sign-in starts from a fresh back stack. */
    navController: NavHostController? = null,
) {
    WingmanTheme {
        CompositionLocalProvider(LocalAppContainer provides container) {
            // I-011 sign-in gate: while signed out, the sign-in screen replaces the whole shell, so
            // no authenticated screen (or its back stack) is reachable. Mock-API builds have no
            // session (container.auth == null) and skip the gate.
            val session = container.auth
            val authState = session?.state?.collectAsState()?.value
            val signInReason = AuthGate.signInReason(authState)
            if (session != null && signInReason != null) {
                SignInScreen(session, signInReason)
            } else {
                // Signing out removes MainShell from composition, so the next sign-in (possibly as
                // another user) starts with a new back stack and re-runs the onboarding check.
                MainShell(container, screens, navController)
            }
        }
    }
}

/** The signed-in app: bottom navigation, NavHost and the first-launch onboarding check. */
@Composable
private fun MainShell(
    container: AppContainer,
    screens: ScreenRegistry<ScreenContent>,
    providedNavController: NavHostController?,
) {
    val navController = providedNavController ?: rememberNavController()
    val navigator = remember(navController) { NavControllerNavigator(navController) }
    val backStack by navController.currentBackStackEntryAsState()
    val current = Destination.fromRoute(backStack?.destination?.route)
    // WING-015: pending-approvals badge, re-read from the server on every destination change.
    val badgeScope = rememberCoroutineScope()
    val pendingBadge = remember(container) { PendingBadgeState(badgeScope, container.api) }
    val badge by pendingBadge.badge.collectAsState()
    LaunchedEffect(current) { pendingBadge.refresh() }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.topLevelDestinations.forEach { dest ->
                    NavigationBarItem(
                        selected = current == dest,
                        onClick = { navigator.navigateTo(dest) },
                        icon = {
                            if (dest == Destination.Approvals) {
                                ApprovalsNavIcon(badge) { Icon(dest.icon(), contentDescription = null) }
                            } else {
                                Icon(dest.icon(), contentDescription = null)
                            }
                        },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalPendingBadge provides pendingBadge) {
            WingmanNavHost(navController, navigator, screens, Modifier.padding(padding))
        }
    }
    // First-launch rule (WING-013): one GET /v1/preferences. Only a confirmed never-saved user
    // (server version 0) is sent to onboarding; a failed or pending check returns false, so a
    // network error never traps someone who already onboarded. Saveable so a configuration
    // change doesn't re-run it.
    var onboardingChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (onboardingChecked) return@LaunchedEffect
        val needsOnboarding = OnboardingGate.check(container.api)
        onboardingChecked = true // only once the check completed; a canceled check re-runs
        if (needsOnboarding) {
            // The NavHost is in the Scaffold's subcomposition: wait until its graph is set.
            navController.currentBackStackEntryFlow.first()
            navController.navigate(Routes.onboarding()) {
                popUpTo(Destination.START.pattern) { inclusive = true }
                launchSingleTop = true
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
        val leaving = Destination.fromRoute(nav.currentDestination?.route)
        nav.navigate(destination.pattern) {
            if (FirstLaunch.popsOnLeave(leaving)) {
                // The start destination was popped when onboarding opened; drop onboarding too.
                popUpTo(Destination.Onboarding.pattern) { inclusive = true }
            } else {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                restoreState = true
            }
            launchSingleTop = true
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
    Destination.Onboarding -> Icons.Filled.Home
}

@Preview(name = "App shell (mock API)", showSystemUi = true)
@Composable
private fun WingmanAppPreview() = WingmanApp(AppContainer.mock())

@Preview(name = "App shell dark (mock API)", showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun WingmanAppDarkPreview() = WingmanApp(AppContainer.mock())
