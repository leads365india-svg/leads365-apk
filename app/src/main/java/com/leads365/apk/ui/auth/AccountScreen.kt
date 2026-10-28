package com.leads365.apk.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.BuildConfig
import com.leads365.apk.data.repository.AuthState
import com.leads365.apk.ui.theme.*

@Composable
fun AccountScreen(
    viewModel: AuthViewModel,
    onNavigateToLogin: () -> Unit
) {
    val authState by viewModel.authState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Slate50
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Text(
            text = "Agent & Account",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Navy900
        )

        when (val state = authState) {
            is AuthState.Authenticated -> {
                // Agent Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = BlueAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Agent Profile", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Navy900)
                        }

                        DetailRow("Name", state.user.name)
                        DetailRow("Email", state.user.email)
                        DetailRow("Role", state.user.role.uppercase())
                    }
                }

                // Agency Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Business, contentDescription = null, tint = TealSuccess)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Agency Identity", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Navy900)
                        }

                        DetailRow("Agency", state.agency?.name ?: "Leads365 Direct")
                        if (!state.agency?.accountId.isNullOrBlank()) {
                            DetailRow("Account ID", state.agency?.accountId ?: "")
                        }
                    }
                }

                // Registered Device Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Navy900)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Registered Device", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Navy900)
                        }

                        DetailRow("Device ID", state.deviceId ?: "Registering...")
                        DetailRow("Status", "ACTIVE", valueColor = TealSuccess)
                        DetailRow("Build Flavour", BuildConfig.FLAVOR.uppercase())
                        DetailRow("App Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    }
                }

                // Logout Button
                Button(
                    onClick = { viewModel.showLogoutConfirmation(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RedDanger),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign Out", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            else -> {
                // Not authenticated
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = LightSurface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Not Signed In",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Navy900
                        )
                        Text(
                            text = "Sign in with your agent credentials to enable CRM lead synchronization and upload tracking.",
                            fontSize = 13.sp,
                            color = TextSecondaryLight,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Button(
                            onClick = onNavigateToLogin,
                            colors = ButtonDefaults.buttonColors(containerColor = Navy900),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Go to Sign In")
                        }
                    }
                }
            }
        }
    }

    // Logout Confirmation Dialog
    if (uiState.showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showLogoutConfirmation(false) },
            title = { Text("Sign Out", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Are you sure you want to sign out? Your session tokens will be removed. Any call recordings currently pending in the background upload queue will be safely preserved on this device.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.logout(preservePendingUploads = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = RedDanger)
                ) {
                    Text("Sign Out")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showLogoutConfirmation(false) }) {
                    Text("Cancel")
                }
            }
        )
    }
}
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = Navy900
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = TextSecondaryLight)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}
