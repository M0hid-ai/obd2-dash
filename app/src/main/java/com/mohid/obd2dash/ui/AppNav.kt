package com.mohid.obd2dash.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mohid.obd2dash.AppGraph
import com.mohid.obd2dash.ui.connect.ConnectScreen
import com.mohid.obd2dash.ui.dashboard.DashboardScreen
import com.mohid.obd2dash.ui.metrics.AllMetricsScreen
import com.mohid.obd2dash.ui.settings.SettingsScreen
import com.mohid.obd2dash.ui.theme.Cyan
import com.mohid.obd2dash.ui.theme.Panel
import com.mohid.obd2dash.ui.theme.TextMuted
import com.mohid.obd2dash.ui.trips.TripDetailScreen
import com.mohid.obd2dash.ui.trips.TripListScreen

private sealed class Destination(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Destination("dashboard", "Dash", Icons.Filled.Speed)
    data object Metrics : Destination("metrics", "Metrics", Icons.Filled.ViewModule)
    data object Trips : Destination("trips", "Trips", Icons.Filled.Route)
    data object Settings : Destination("settings", "Settings", Icons.Filled.Settings)
}

private val bottomBarItems = listOf(
    Destination.Dashboard,
    Destination.Metrics,
    Destination.Trips,
    Destination.Settings,
)

private val bottomBarRoutes: Set<String> = bottomBarItems.mapTo(HashSet()) { it.route }

/**
 * How long a screen change is allowed to take.
 *
 * Navigation Compose defaults to a 700ms slide, which is what made switching
 * tabs feel like the app was thinking. Nothing here is actually slow enough to
 * need covering up: the screens read from flows that are already in memory. So
 * the tabs cross-fade fast enough to register as a change without being a wait,
 * and only the full-screen sub-pages slide, because there the motion carries
 * real meaning about where you just went.
 */
private const val TAB_FADE_MS = 90
private const val PAGE_SLIDE_MS = 210

const val ROUTE_CONNECT = "connect"
const val ROUTE_TRIP_DETAIL = "trip/{tripId}"

fun tripDetailRoute(tripId: Long) = "trip/$tripId"

@Composable
fun AppNav(graph: AppGraph) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            // Hidden on the full-screen sub-pages so they get the whole display.
            if (currentRoute in bottomBarRoutes) {
                NavigationBar(containerColor = Panel) {
                    bottomBarItems.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Cyan,
                                selectedTextColor = Cyan,
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted,
                                indicatorColor = Panel,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(TAB_FADE_MS)) },
            exitTransition = { fadeOut(tween(TAB_FADE_MS)) },
            popEnterTransition = { fadeIn(tween(TAB_FADE_MS)) },
            popExitTransition = { fadeOut(tween(TAB_FADE_MS)) },
        ) {
            composable(Destination.Dashboard.route) {
                DashboardScreen(
                    graph = graph,
                    onOpenConnect = { navController.navigate(ROUTE_CONNECT) },
                    onOpenTrip = { navController.navigate(tripDetailRoute(it)) },
                )
            }
            composable(Destination.Metrics.route) {
                AllMetricsScreen(graph = graph)
            }
            composable(Destination.Trips.route) {
                TripListScreen(
                    graph = graph,
                    onOpenTrip = { navController.navigate(tripDetailRoute(it)) },
                )
            }
            composable(Destination.Settings.route) {
                SettingsScreen(graph = graph)
            }
            composable(
                route = ROUTE_CONNECT,
                enterTransition = {
                    slideInHorizontally(tween(PAGE_SLIDE_MS)) { it / 4 } + fadeIn(tween(PAGE_SLIDE_MS))
                },
                popExitTransition = {
                    slideOutHorizontally(tween(PAGE_SLIDE_MS)) { it / 4 } + fadeOut(tween(PAGE_SLIDE_MS))
                },
            ) {
                ConnectScreen(graph = graph, onBack = { navController.popBackStack() })
            }
            composable(
                route = ROUTE_TRIP_DETAIL,
                arguments = listOf(navArgument("tripId") { type = NavType.LongType }),
                enterTransition = {
                    slideInHorizontally(tween(PAGE_SLIDE_MS)) { it / 4 } + fadeIn(tween(PAGE_SLIDE_MS))
                },
                popExitTransition = {
                    slideOutHorizontally(tween(PAGE_SLIDE_MS)) { it / 4 } + fadeOut(tween(PAGE_SLIDE_MS))
                },
            ) { entry ->
                TripDetailScreen(
                    graph = graph,
                    tripId = entry.arguments?.getLong("tripId") ?: 0L,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
