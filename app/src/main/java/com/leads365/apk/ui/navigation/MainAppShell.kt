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
import com.leads365.apk.ui.theme.AmberWarning
import com.leads365.apk.ui.theme.BlueAccent
import com.leads365.apk.ui.theme.Navy900
import com.leads365.apk.ui.theme.TextSecondaryLight
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

    // Reactively enforce session state transitions
    LaunchedEffect(authState, isLocalRecordingTestMode) {
        if (authState is AuthState.Authenticated) {
            isLocalRecordingTestMode = false
            if (navController.currentDestination?.route == NavDestination.Login.route) {
                navController.navigate(NavDestination.Home.route) {
                    popUpTo(NavDestination.Login.route) { inclusive = true }
                }
            }
        } else {
            val inDevTestMode = isLocalRecordingTestMode && (BuildConfig.FLAVOR == "dev")
            if (!inDevTestMode) {
                if (navController.currentDestination?.route != NavDestination.Login.route) {
                    navController.navigate(NavDestination.Login.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (isBottomBarVisible) {
                NavigationBar(
                    containerColor = Color.White,
                    contentColor = Navy900
                ) {
                    NavDestination.items.forEach { destination ->
                        val isSelected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                if (!canAccessApp) return@NavigationBarItem
                                if (currentRoute != destination.route) {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.title
                                )
                            },
                            label = {
                                Text(
                                    text = destination.title,
                                    fontSize = 10.sp
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BlueAccent,
                                selectedTextColor = BlueAccent,
                                unselectedIconColor = TextSecondaryLight,
                                unselectedTextColor = TextSecondaryLight,
                                indicatorColor = BlueAccent.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Dev Local Test Mode Persistent Top Banner
            if (!isAuthenticated && isLocalRecordingTestMode && BuildConfig.FLAVOR == "dev") {
                Surface(
                    color = AmberWarning.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
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
                                tint = AmberWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Local Recording Test Mode — dialer discovery active",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Navy900
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
                            Text("Sign In", fontSize = 11.sp, color = BlueAccent, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = startDest,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(NavDestination.Login.route) {
                    LoginScreen(
                        viewModel = authViewModel,
                        onNavigateToSetup = {
                            if (BuildConfig.FLAVOR == "dev") {
                                isLocalRecordingTestMode = true
                                navController.navigate(NavDestination.Home.route) {
                                    popUpTo(NavDestination.Login.route) { inclusive = true }
                                }
                            }
                        }
                    )
                }

                composable(NavDestination.Home.route) {
                    if (!canAccessApp) {
                        LaunchedEffect(Unit) {
                            navController.navigate(NavDestination.Login.route) { popUpTo(0) { inclusive = true } }
                        }
                    } else {
                        HomeScreen(
                            onNavigateToLeads = { navController.navigate(NavDestination.Leads.route) },
                            onNavigateToCalls = { navController.navigate(NavDestination.Calls.route) },
                            onNavigateToUploads = { navController.navigate(NavDestination.Uploads.route) },
                            onNavigateToSetup = { navController.navigate(NavDestination.Setup.route) },
                            onNavigateToSettings = { navController.navigate(NavDestination.Settings.route) },
                            onNavigateToAccount = { navController.navigate(NavDestination.Account.route) },
                            onNavigateToPrivacy = { navController.navigate(NavDestination.Privacy.route) }
                        )
                    }
                }

                composable(NavDestination.Leads.route) {
                    if (!canAccessApp) {
                        LaunchedEffect(Unit) {
                            navController.navigate(NavDestination.Login.route) { popUpTo(0) { inclusive = true } }
                        }
                    } else {
                        val leadsViewModel: com.leads365.apk.ui.leads.LeadsViewModel = hiltViewModel()
                        LeadsScreen(viewModel = leadsViewModel)
                    }
                }

                composable(NavDestination.Calls.route) {
                    if (!canAccessApp) {
                        LaunchedEffect(Unit) {
                            navController.navigate(NavDestination.Login.route) { popUpTo(0) { inclusive = true } }
                        }
                    } else {
                        CallsScreen(
                            onNavigateToUploads = {
                                navController.navigate(NavDestination.Uploads.route)
                            },
                            onNavigateToSetup = {
                                navController.navigate(NavDestination.Setup.route)
                            }
                        )
                    }
                }

                composable(NavDestination.Uploads.route) {
                    if (!canAccessApp) {
                        LaunchedEffect(Unit) {
                            navController.navigate(NavDestination.Login.route) { popUpTo(0) { inclusive = true } }
                        }
                    } else {
                        UploadStatusScreen(
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }

                composable(NavDestination.Privacy.route) {
                    PrivacyScreen()
                }

                composable(NavDestination.Account.route) {
                    if (!canAccessApp) {
                        LaunchedEffect(Unit) {
                            navController.navigate(NavDestination.Login.route) { popUpTo(0) { inclusive = true } }
                        }
                    } else {
                        AccountScreen(
                            viewModel = authViewModel,
                            onNavigateToLogin = {
                                navController.navigate(NavDestination.Login.route) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        )
                    }
                }

                composable(NavDestination.Settings.route) {
                    if (!canAccessApp) {
                        LaunchedEffect(Unit) {
                            navController.navigate(NavDestination.Login.route) { popUpTo(0) { inclusive = true } }
                        }
                    } else {
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
                }

                composable(NavDestination.Setup.route) {
                    if (!canAccessApp) {
                        LaunchedEffect(Unit) {
                            navController.navigate(NavDestination.Login.route) { popUpTo(0) { inclusive = true } }
                        }
                    } else {
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
}
