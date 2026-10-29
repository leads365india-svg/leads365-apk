package com.leads365.apk.ui.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.calls.CallLogAccessStatus
import com.leads365.apk.calls.CallMonitoringState
import com.leads365.apk.core.*
import com.leads365.apk.ui.SimulatedCallScenario
import com.leads365.apk.ui.theme.*

@Composable
fun Step3TestCall(
    testLeads: List<TestLead>,
    selectedLeadId: String? = null,
    recentCallEvent: CallEvent?,
    gateResult: GateEvaluationResult?,
    callMonitoringState: CallMonitoringState,
    callLogAccessStatus: CallLogAccessStatus,
    callLogErrorMessage: String?,
    isCallSimulated: Boolean,
    isSearchingRecording: Boolean,
    canContinue: Boolean,
    deviceModel: String,
    onAddLead: (name: String, number: String, isDnr: Boolean) -> Result<TestLead>,
    onSelectLead: (id: String) -> Unit = {},
    onRemoveLead: (id: String) -> Unit = {},
    onToggleDnr: (id: String, dnr: Boolean) -> Unit,
    onCheckRecentCall: () -> Unit,
    onSimulateCall: (SimulatedCallScenario) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showSwitchDialog by remember { mutableStateOf(false) }

    val primaryLead = testLeads.find { it.id == selectedLeadId } ?: testLeads.firstOrNull()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section Heading
        Text(
            text = "Make a Test Call",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Slate900
        )

        // 1. Live Call Monitoring Status Banner
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when (callMonitoringState) {
                                    is CallMonitoringState.Ringing -> Amber600
                                    is CallMonitoringState.CallActive -> Color(0xFF1D4ED8)
                                    is CallMonitoringState.Error -> RedDanger
                                    else -> Emerald500
                                }
                            )
                    )
                    Text(
                        text = "Call Monitor Active",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                }

                val monitorStatusText = when (callMonitoringState) {
                    is CallMonitoringState.Ringing -> "Phone is Ringing..."
                    is CallMonitoringState.CallActive -> "Call in Progress..."
                    is CallMonitoringState.CallEndedQueryingLog -> "Reading Call Log (${callMonitoringState.attempt}/4)..."
                    is CallMonitoringState.CallDetected -> "Call event detected"
                    is CallMonitoringState.Error -> "Monitor Error"
                    else -> "Waiting for call..."
                }

                Text(
                    text = monitorStatusText,
                    fontSize = 12.sp,
                    color = Slate500,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 2. Allowed Test Lead Number Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header row with uppercase title, active badge, and action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "ALLOWED TEST LEAD NUMBER",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate500
                        )
                        if (testLeads.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Blue100
                            ) {
                                Text(
                                    text = "${testLeads.size} Active",
                                    color = Blue700,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Action buttons: Switch and Add New
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (testLeads.size > 1) {
                            OutlinedButton(
                                onClick = { showSwitchDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Slate300)
                            ) {
                                Text(
                                    text = "Switch",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Slate700,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        Button(
                            onClick = { showAddDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate900)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Add New",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                if (primaryLead != null) {
                    // Lead item box
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = primaryLead.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate900
                                )
                                Text(
                                    text = PrivacyRedactor.redactPhoneNumber(primaryLead.normalizedNumber),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Slate600
                                )
                            }

                            // DNR Toggle Button / Pill
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (primaryLead.isDoNotRecord) Red100 else Slate200,
                                modifier = Modifier.clickable {
                                    onToggleDnr(primaryLead.id, !primaryLead.isDoNotRecord)
                                }
                            ) {
                                Text(
                                    text = if (primaryLead.isDoNotRecord) "DNR On" else "DNR Off",
                                    color = if (primaryLead.isDoNotRecord) RedDanger else Slate600,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                } else {
                    // Empty state: no lead configured
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "No test lead configured",
                                fontSize = 12.sp,
                                color = Slate500
                            )
                            Button(
                                onClick = { showAddDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Slate900),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("Add Test Lead", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 3. Real Phone Testing Action Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Test on this phone ($deviceModel)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Slate600
                )

                // Full-width Check for Recent Call button
                Button(
                    onClick = onCheckRecentCall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Slate900)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Check for Recent Call",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Red Error Alert (Triggers if call log search failed or no recent call found)
                if (callLogErrorMessage != null && recentCallEvent == null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Rose50,
                        border = BorderStroke(1.dp, Rose200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = RedDanger,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = callLogErrorMessage,
                                fontSize = 11.sp,
                                color = Rose800,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                // Detected Call Summary if an event was detected
                if (recentCallEvent != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isCallSimulated) Slate100 else Emerald50,
                        border = BorderStroke(1.dp, if (isCallSimulated) Slate300 else Emerald200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isCallSimulated) "Call Event: [DEMO SIMULATION]" else "Stage 1: Call-Log Event Detected",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCallSimulated) Slate700 else Emerald800
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isCallSimulated) Slate200 else Emerald100
                                ) {
                                    Text(
                                        text = if (isCallSimulated) "DEMO" else "CALL LOGGED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCallSimulated) Slate700 else Emerald700,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Number: ${PrivacyRedactor.redactPhoneNumber(recentCallEvent.normalizedNumber)} • Direction: ${recentCallEvent.direction} • Duration: ${recentCallEvent.durationSeconds}s",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate800
                            )
                            if (isSearchingRecording) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Amber600)
                                    Text("Waiting for phone dialer to finish writing audio file...", fontSize = 10.sp, color = Amber700)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Developer / Demo Simulation Sandbox
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Slate100,
            border = BorderStroke(1.dp, Slate300),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "[DEMO SIMULATION ONLY] Testing Sandbox",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate700
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSimulateCall(SimulatedCallScenario.MATCHED_TEST_LEAD) },
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Slate800
                        ),
                        border = BorderStroke(1.dp, Slate300)
                    ) {
                        Text("Simulate Lead Call", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onSimulateCall(SimulatedCallScenario.NON_LEAD_NUMBER) },
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Slate800
                        ),
                        border = BorderStroke(1.dp, Slate300)
                    ) {
                        Text("Simulate Non-Lead", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Bottom Navigation Row: Back (1/3) + View Result (2/3)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Slate200,
                    contentColor = Slate700
                )
            ) {
                Text("Back", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier
                    .weight(2f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Emerald800,
                    disabledContainerColor = Slate200,
                    disabledContentColor = Slate500
                )
            ) {
                Text(
                    text = "View Result →",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (canContinue) Color.White else Slate500
                )
            }
        }
    }

    // Configure / Add Test Lead Dialog
    if (showAddDialog) {
        var nameInput by remember { mutableStateOf("") }
        var numberInput by remember { mutableStateOf("") }
        var isDnrInput by remember { mutableStateOf(false) }
        var validationError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = {
                validationError = null
                showAddDialog = false
            },
            title = { Text("Configure Test Lead", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Lead / Contact Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = numberInput,
                        onValueChange = { numberInput = it },
                        label = { Text("Phone Number") },
                        placeholder = { Text("+91 98765 43210") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mark Do-Not-Record (DNR)", fontSize = 12.sp)
                        Switch(checked = isDnrInput, onCheckedChange = { isDnrInput = it })
                    }
                    if (validationError != null) {
                        Text(text = validationError ?: "", color = RedDanger, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val result = onAddLead(nameInput, numberInput, isDnrInput)
                        result.onSuccess {
                            validationError = null
                            showAddDialog = false
                        }.onFailure {
                            validationError = it.message ?: "Invalid phone number"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Slate900)
                ) {
                    Text("Save Lead")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Switch Active Lead Dialog
    if (showSwitchDialog) {
        AlertDialog(
            onDismissRequest = { showSwitchDialog = false },
            title = { Text("Select Active Test Lead", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    testLeads.forEach { lead ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (lead.id == primaryLead?.id) Blue50 else Slate50,
                            border = BorderStroke(1.dp, if (lead.id == primaryLead?.id) Blue600 else Slate200),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectLead(lead.id)
                                    showSwitchDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(lead.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(
                                        PrivacyRedactor.redactPhoneNumber(lead.normalizedNumber),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Slate600
                                    )
                                }
                                if (lead.id == primaryLead?.id) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Blue600, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSwitchDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
