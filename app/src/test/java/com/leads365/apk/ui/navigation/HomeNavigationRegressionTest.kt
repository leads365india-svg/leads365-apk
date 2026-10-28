package com.leads365.apk.ui.navigation

import android.content.Context
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HomeNavigationRegressionTest {

    private lateinit var context: Context
    private lateinit var navController: NavHostController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        navController = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
        }

        // Build graph with Login as startDestination (simulating app initial state)
        navController.graph = navController.createGraph(startDestination = NavDestination.Login.route) {
            composable(NavDestination.Login.route) {}
            composable(NavDestination.Home.route) {}
            composable(NavDestination.Calls.route) {}
            composable(NavDestination.Leads.route) {}
            composable(NavDestination.Settings.route) {}
        }
    }

    private fun simulateBottomBarClick(destination: NavDestination) {
        val currentRoute = navController.currentDestination?.route
        if (destination == NavDestination.Home) {
            if (currentRoute != NavDestination.Home.route) {
                val popped = navController.popBackStack(NavDestination.Home.route, inclusive = false)
                if (!popped) {
                    navController.navigate(NavDestination.Home.route) {
                        popUpTo(0) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            } else {
                navController.popBackStack(NavDestination.Home.route, inclusive = false)
            }
        } else {
            if (currentRoute != destination.route) {
                navController.navigate(destination.route) {
                    popUpTo(NavDestination.Home.route) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    @Test
    fun `Home to Calls to Home returns to Home`() {
        // App starts at Login, transitions to Home
        navController.navigate(NavDestination.Home.route) {
            popUpTo(NavDestination.Login.route) { inclusive = true }
        }
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)

        // Home -> Calls
        simulateBottomBarClick(NavDestination.Calls)
        assertEquals(NavDestination.Calls.route, navController.currentDestination?.route)

        // Calls -> Home
        simulateBottomBarClick(NavDestination.Home)
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)
    }

    @Test
    fun `Home to Leads to Home returns to Home`() {
        navController.navigate(NavDestination.Home.route) {
            popUpTo(NavDestination.Login.route) { inclusive = true }
        }
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)

        // Home -> Leads
        simulateBottomBarClick(NavDestination.Leads)
        assertEquals(NavDestination.Leads.route, navController.currentDestination?.route)

        // Leads -> Home
        simulateBottomBarClick(NavDestination.Home)
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)
    }

    @Test
    fun `Home to Settings to Home returns to Home`() {
        navController.navigate(NavDestination.Home.route) {
            popUpTo(NavDestination.Login.route) { inclusive = true }
        }
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)

        // Home -> Settings
        simulateBottomBarClick(NavDestination.Settings)
        assertEquals(NavDestination.Settings.route, navController.currentDestination?.route)

        // Settings -> Home
        simulateBottomBarClick(NavDestination.Home)
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)
    }

    @Test
    fun `Home to Calls to Leads to Home returns to Home`() {
        navController.navigate(NavDestination.Home.route) {
            popUpTo(NavDestination.Login.route) { inclusive = true }
        }
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)

        // Home -> Calls
        simulateBottomBarClick(NavDestination.Calls)
        assertEquals(NavDestination.Calls.route, navController.currentDestination?.route)

        // Calls -> Leads
        simulateBottomBarClick(NavDestination.Leads)
        assertEquals(NavDestination.Leads.route, navController.currentDestination?.route)

        // Leads -> Home
        simulateBottomBarClick(NavDestination.Home)
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)
    }

    @Test
    fun `Repeated Home taps do not duplicate Home or crash`() {
        navController.navigate(NavDestination.Home.route) {
            popUpTo(NavDestination.Login.route) { inclusive = true }
        }
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)

        // Tap Home multiple times
        simulateBottomBarClick(NavDestination.Home)
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)

        simulateBottomBarClick(NavDestination.Home)
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)

        simulateBottomBarClick(NavDestination.Calls)
        assertEquals(NavDestination.Calls.route, navController.currentDestination?.route)

        simulateBottomBarClick(NavDestination.Home)
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)

        simulateBottomBarClick(NavDestination.Home)
        assertEquals(NavDestination.Home.route, navController.currentDestination?.route)
    }
}
