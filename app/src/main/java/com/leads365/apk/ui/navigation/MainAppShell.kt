package com.leads365.apk.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.leads365.apk.BuildConfig
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.ui.MainScreen
import com.leads365.apk.ui.MainViewModel
import com.leads365.apk.ui.auth.AccountScreen
import com.leads365.apk.ui.auth.AuthViewModel
import com.leads365.apk.ui.auth.LoginScreen
import com.leads365.apk.ui.calls.CallsScreen
import com.leads365.apk.ui.home.HomeScreen
import com.leads365.apk.ui.leads.LeadsScreen
import com.leads365.apk.ui.privacy.PrivacyScreen
import com.leads365.apk.ui.theme.*
import com.leads365.apk.ui.upload.UploadStatusScreen

@Composable
fun MainAppShell(
    viewModel: MainViewModel,
    authViewModel: AuthViewModel = hiltViewModel(),
    onRequestPermissions: () -> Unit,
    onRequestDirectCallPermission: () -> Unit = {},
    onRequestOverlayPermission: () -> Unit = {},
    onRequestBatteryOptimization: () -> Unit = {},
    onOpenAppSettings: () -> Unit,
    onPickSafFolder: () -> Unit
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val authState by authViewModel.authState.collectAsState()

    val isAuthenticated = authState is AuthState.Authenticated
    var isLocalRecordingTestMode by rememberSaveable { mutableStateOf(false) }

    // Session gating: allow full app access if authenticated OR in dev local recording test mode
    val canAccessApp = isAuthenticated || (isLocalRecordingTestMode && BuildConfig.FLAVOR == "dev")

    val startDest = if (canAccessApp) {
        NavDestination.Home.route
    } else {
        NavDestination.Login.route
    }

    val currentRoute = navBackStackEntry?.destination?.route ?: startDest

    // Bottom navigation visible whenever user can access app and is not on Login
    val isBottomBarVisible = canAccessApp && currentRoute != NavDestination.Login.route

    // Reactively enforce session state transitions (single authoritative source of truth)
    LaunchedEffect(authState, isLocalRecordingTestMode) {
        val inDevTestMode = isLocalRecordingTestMode && (BuildConfig.FLAVOR == "dev")
        val canAccess = authState is AuthState.Authenticated || inDevTestMode
        if (canAccess) {
            if (authState is AuthState.Authenticated) {
                isLocalRecordingTestMode = false
            }
            if (navController.currentDestination?.route == NavDestination.Login.route) {
                navController.navigate(NavDestination.Home.route) {
                    popUpTo(NavDestination.Login.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        } else {
            if (navController.currentDestination?.route != NavDestination.Login.route) {
                navController.navigate(NavDestination.Login.route) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    // Handle deep-link/notification pending navigation
    val pendingDestination by viewModel.pendingDestination.collectAsState()
    LaunchedEffect(pendingDestination, canAccessApp) {
        val dest = pendingDestination
        if (dest != null && canAccessApp) {
            val validRoute = when (dest.lowercase()) {
                "followups" -> NavDestination.FollowUps.route
                "leads" -> NavDestination.Leads.route
                "calls" -> NavDestination.Calls.route
                "uploads" -> NavDestination.Uploads.route
                "settings" -> NavDestination.Settings.route
                else -> null
            }
            if (validRoute != null) {
                navController.navigate(validRoute) {
                    popUpTo(NavDestination.Home.route) {
                        saveState = true
                    }
                    launchSingleTop = true
                }
            }
            viewModel.clearPendingDestination()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Slate50,
        contentColor = Slate900,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (isBottomBarVisible) {
                Surface(
                    color = Color.White,
                    tonalElevation = 0.dp,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    NavigationBar(
                        containerColor = Color.White,
                        contentColor = Slate900,
                        tonalElevation = 0.dp,
                        windowInsets = NavigationBarDefaults.windowInsets,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NavDestination.items.forEach { destination ->
                            val isSelected = currentRoute == destination.route
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    if (!canAccessApp) return@NavigationBarItem
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
                                },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.title,
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = destination.title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Blue600,
                                    selectedTextColor = Blue600,
                                    unselectedIconColor = Slate400,
                                    unselectedTextColor = Slate500,
                                    indicatorColor = Blue50
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Slate50)
                .padding(bottom = innerPadding.calculateBottomPadding())
                .consumeWindowInsets(PaddingValues(bottom = innerPadding.calculateBottomPadding()))
        ) {
            // Dev Local Test Mode Persistent Top Banner
            if (!isAuthenticated && isLocalRecordingTestMode && BuildConfig.FLAVOR == "dev") {
                Surface(
                    color = Amber50,
                    border = BorderStroke(1.dp, Amber200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = null,
                                tint = Amber600,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Local Recording Test Mode — dialer discovery active",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Amber900,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = {
                                isLocalRecordingTestMode = false
                                navController.navigate(NavDestination.Login.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Sign In", fontSize = 11.5.sp, color = Blue600, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = startDest,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Slate50)
            ) {
                composable(NavDestination.Login.route) {
                    LoginScreen(
                        viewModel = authViewModel,
                        onNavigateToSetup = {
                            if (BuildConfig.FLAVOR == "dev") {
                                isLocalRecordingTestMode = true
                                navController.navigate(NavDestination.Home.route) {
                                    popUpTo(NavDestination.Login.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }

                composable(NavDestination.Home.route) {
                    HomeScreen(
                        onNavigateToLeads = { navController.navigate(NavDestination.Leads.route) },
                        onNavigateToCalls = { navController.navigate(NavDestination.Calls.route) },
                        onNavigateToUploads = { navController.navigate(NavDestination.Uploads.route) },
                        onNavigateToSetup = { navController.navigate(NavDestination.Setup.route) },
                        onNavigateToSettings = { navController.navigate(NavDestination.Settings.route) },
                        onNavigateToAccount = { navController.navigate(NavDestination.Account.route) },
                        onNavigateToPrivacy = { navController.navigate(NavDestination.Privacy.route) },
                        onNavigateToFollowUps = { navController.navigate(NavDestination.FollowUps.route) }
                    )
                }

                composable(NavDestination.Leads.route) {
                    val leadsViewModel: com.leads365.apk.ui.leads.LeadsViewModel = hiltViewModel()
                    LeadsScreen(viewModel = leadsViewModel)
                }

                composable(NavDestination.Calls.route) {
                    CallsScreen(
                        onNavigateToUploads = {
                            navController.navigate(NavDestination.Uploads.route)
                        },
                        onNavigateToSetup = {
                            navController.navigate(NavDestination.Setup.route)
                        }
                    )
                }

                composable(NavDestination.Uploads.route) {
                    UploadStatusScreen(
                        onNavigateBack = {
                            navController.popBackStack()
                        }
                    )
                }

                composable(NavDestination.Privacy.route) {
                    PrivacyScreen()
                }

                composable(NavDestination.FollowUps.route) {
                    com.leads365.apk.ui.followups.FollowUpsScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                composable(NavDestination.Account.route) {
                    AccountScreen(
                        viewModel = authViewModel,
                        onNavigateToLogin = {
                            navController.navigate(NavDestination.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    )
                }

                composable(NavDestination.Settings.route) {
                    SettingsScreen(
                        onRequestPermissions = onRequestPermissions,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onRequestBatteryOptimization = onRequestBatteryOptimization,
                        onOpenAppSettings = onOpenAppSettings,
                        onNavigateToAccount = {
                            navController.navigate(NavDestination.Account.route)
                        },
                        onNavigateToPrivacy = {
                            navController.navigate(NavDestination.Privacy.route)
                        },
                        onNavigateToDeviceSetup = {
                            navController.navigate(NavDestination.Setup.route)
                        },
                        authViewModel = authViewModel
                    )
                }

                composable(NavDestination.Setup.route) {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestPermissions = onRequestPermissions,
                        onRequestDirectCallPermission = onRequestDirectCallPermission,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onRequestBatteryOptimization = onRequestBatteryOptimization,
                        onOpenAppSettings = onOpenAppSettings,
                        onPickSafFolder = onPickSafFolder,
                        onNavigateToCalls = { navController.navigate(NavDestination.Calls.route) }
                    )
                }
            }
        }
    }
}
