package com.leads365.apk.ui

import com.leads365.apk.ui.navigation.NavDestination
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class NavigationDestinationTest {

    @Test
    fun `navigation destinations contain all required roadmap tabs`() {
        val items = NavDestination.items
        assertEquals(4, items.size)

        val routes = items.map { it.route }
        assertTrue("Home route must exist", routes.contains("home"))
        assertTrue("Leads route must exist", routes.contains("leads"))
        assertTrue("Calls route must exist", routes.contains("calls"))
        assertTrue("Settings route must exist", routes.contains("settings"))
        assertEquals("account", NavDestination.Account.route)
        assertEquals("setup", NavDestination.Setup.route)
        assertEquals("login", NavDestination.Login.route)
    }

    @Test
    fun `navigation titles are user-friendly and properly formatted`() {
        assertEquals("Device Setup", NavDestination.Setup.title)
        assertEquals("Leads", NavDestination.Leads.title)
        assertEquals("Calls", NavDestination.Calls.title)
        assertEquals("Account", NavDestination.Account.title)
        assertEquals("Settings", NavDestination.Settings.title)
        assertEquals("Sign In", NavDestination.Login.title)
    }
}
