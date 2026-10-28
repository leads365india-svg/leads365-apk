package com.leads365.apk.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class NavDestination(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    object Home : NavDestination("home", "Home", Icons.Default.Home)
    object Setup : NavDestination("setup", "Device Setup", Icons.Default.Check)
    object Leads : NavDestination("leads", "Leads", Icons.AutoMirrored.Filled.List)
    object Calls : NavDestination("calls", "Calls", Icons.Default.Call)
    object Uploads : NavDestination("uploads", "Uploads", Icons.Default.CloudUpload)
    object Settings : NavDestination("settings", "Settings", Icons.Default.Settings)
    object Account : NavDestination("account", "Account", Icons.Default.Person)
    object Privacy : NavDestination("privacy", "Privacy", Icons.Default.PrivacyTip)
    object FollowUps : NavDestination("followups", "Follow-ups", Icons.Default.DateRange)
    object Login : NavDestination("login", "Sign In", Icons.Default.Lock)

    companion object {
        val items: List<NavDestination>
            get() = listOf(Home, Leads, Calls, Settings)
    }
}
