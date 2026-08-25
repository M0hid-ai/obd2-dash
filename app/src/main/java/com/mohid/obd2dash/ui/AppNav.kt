package com.mohid.obd2dash.ui

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
            if (currentRoute in bottomBarItems.map { it.route }) {
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
            composable(ROUTE_CONNECT) {
                ConnectScreen(graph = graph, onBack = { navController.popBackStack() })
            }
            composable(
                route = ROUTE_TRIP_DETAIL,
                arguments = listOf(navArgument("tripId") { type = NavType.LongType }),
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
