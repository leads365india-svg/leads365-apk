package com.leads365.apk.ui.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Title (English only)
            Text(
                text = "Make a Test Call",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Navy900,
                fontSize = 18.sp
            )

            // Live Call Monitoring Status Banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = when (callMonitoringState) {
                    is CallMonitoringState.Ringing -> Color(0xFFFEF3C7)
                    is CallMonitoringState.CallActive -> Color(0xFFEFF6FF)
                    is CallMonitoringState.CallEndedQueryingLog -> Color(0xFFF3E8FF)
                    is CallMonitoringState.CallDetected -> Color(0xFFECFDF5)
                    is CallMonitoringState.CallLogUnavailable -> Color(0xFFFFFBEB)
                    is CallMonitoringState.Error -> Color(0xFFFEE2E2)
                    else -> Color(0xFFF8FAFC)
                },
                border = BorderStroke(
                    1.dp,
                    when (callMonitoringState) {
                        is CallMonitoringState.Ringing -> Color(0xFFFDE68A)
                        is CallMonitoringState.CallActive -> Color(0xFFBFDBFE)
                        is CallMonitoringState.CallEndedQueryingLog -> Color(0xFFE9D5FF)
                        is CallMonitoringState.CallDetected -> Color(0xFFBBF7D0)
                        is CallMonitoringState.CallLogUnavailable -> Color(0xFFFDE68A)
                        is CallMonitoringState.Error -> Color(0xFFFCA5A5)
                        else -> Color(0xFFE2E8F0)
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (callMonitoringState) {
                        is CallMonitoringState.Ringing -> {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AmberWarning)
                            Text("Phone is Ringing...", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB45309))
                        }
                        is CallMonitoringState.CallActive -> {
                            Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = Color(0xFF1D4ED8), modifier = Modifier.size(16.dp))
                            Text("Call in Progress...", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1D4ED8))
                        }
                        is CallMonitoringState.CallEndedQueryingLog -> {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF7E22CE))
                            Text("Call Ended! Reading Call Log (Attempt ${callMonitoringState.attempt}/4)...", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7E22CE))
                        }
                        is CallMonitoringState.CallDetected -> {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(16.dp))
                            Text("Call-log event detected", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
                        }
                        is CallMonitoringState.CallLogUnavailable -> {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(16.dp))
                            Text(callMonitoringState.reason, fontSize = 10.sp, color = Color(0xFFB45309), lineHeight = 14.sp)
                        }
                        is CallMonitoringState.Error -> {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = RedDanger, modifier = Modifier.size(16.dp))
                            Text(callMonitoringState.message, fontSize = 10.sp, color = RedDanger, lineHeight = 14.sp)
                        }
                        else -> {
                            Icon(Icons.Default.Radio, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(16.dp))
                            Text("Call Monitor Active", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
                        }
                    }
                }
            }

            // Test-Lead Allowlist Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Header Row with Title and Lead Counter Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Allowed Test Lead Number",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (primaryLead != null && testLeads.size > 1) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = BlueAccent.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = "${testLeads.size} Active",
                                    color = BlueAccent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Action Controls on Dedicated Row when leads exist
                    if (primaryLead != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (testLeads.size > 1) {
                                OutlinedButton(
                                    onClick = { showSwitchDialog = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "Switch (${testLeads.size})",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { showAddDialog = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BlueAccent)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Add New",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    if (primaryLead == null) {
                        // Empty State: No leads created yet
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = BlueAccent, modifier = Modifier.size(28.dp))
                                Text("No Test Lead Configured", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Navy900)
                                Button(
                                    onClick = { showAddDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Navy800),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.heightIn(min = 36.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Add Test Lead", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Display configured test lead card
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(primaryLead.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(
                                            PrivacyRedactor.redactPhoneNumber(primaryLead.normalizedNumber),
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = Navy900
                                        )
                                    }

                                    if (primaryLead.isDoNotRecord) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFFEE2E2)
                                        ) {
                                            Text(
                                                text = "DNR (Do-Not-Record)",
                                                color = RedDanger,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Do-Not-Record (DNR) Toggle:",
                                        fontSize = 11.sp,
                                        color = TextSecondaryLight
                                    )
                                    Switch(
                                        checked = primaryLead.isDoNotRecord,
                                        onCheckedChange = { onToggleDnr(primaryLead.id, it) },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Real Phone Testing Action Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF0FDF4),
                border = BorderStroke(1.dp, Color(0xFFBBF7D0))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(18.dp))
                        Text(
                            text = "Test on this phone ($deviceModel)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF15803D)
                        )
                    }

                    // Prominent Check Recent Call Button
                    Button(
                        onClick = onCheckRecentCall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy800),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check for Recent Call", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Call Log Error Message Box (if query failed or no call found)
            if (callLogErrorMessage != null && recentCallEvent == null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFEF2F2),
                    border = BorderStroke(1.dp, Color(0xFFFCA5A5)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = RedDanger, modifier = Modifier.size(18.dp))
                        Text(
                            text = callLogErrorMessage,
                            fontSize = 11.sp,
                            color = Color(0xFF991B1B),
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Detected Call Status Banner
            if (recentCallEvent != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (isCallSimulated) Color(0xFFF8FAFC) else Color(0xFFECFDF5),
                    border = BorderStroke(1.dp, if (isCallSimulated) Color(0xFFCBD5E1) else Color(0xFF6EE7B7))
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
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = if (isCallSimulated) Color(0xFF475569) else Color(0xFF047857)
                            )
                            if (isCallSimulated) {
                                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFE2E8F0)) {
                                    Text("DEMO", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            } else {
                                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFDCFCE7)) {
                                    Text("CALL LOGGED", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                }
                            }
                        }

                        Text(
                            text = "Number: ${PrivacyRedactor.redactPhoneNumber(recentCallEvent.normalizedNumber)} • Direction: ${recentCallEvent.direction} • Duration: ${recentCallEvent.durationSeconds}s",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Gate Result Explanation
                        when (gateResult) {
                            is GateEvaluationResult.Allowed -> {
                                Text(
                                    text = "Lead Gate: PASSED (Allowed Test Lead). Recording lookup permitted.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF047857),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            is GateEvaluationResult.RejectedNotALead -> {
                                Text(
                                    text = "Lead Gate: STOPPED (Number is not an allowed test lead. Recording lookup strictly blocked).",
                                    fontSize = 11.sp,
                                    color = RedDanger,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            is GateEvaluationResult.RejectedDoNotRecord -> {
                                Text(
                                    text = "Lead Gate: STOPPED (Lead is marked Do-Not-Record. Recording lookup strictly blocked).",
                                    fontSize = 11.sp,
                                    color = RedDanger,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            is GateEvaluationResult.RejectedDuplicateCall -> {
                                Text(
                                    text = "Lead Gate: STOPPED (Duplicate call event detected within 15s).",
                                    fontSize = 11.sp,
                                    color = Color(0xFFB45309),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            else -> {}
                        }

                        if (isSearchingRecording) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AmberWarning)
                                Text("Waiting for phone dialer to finish writing audio file...", fontSize = 10.sp, color = AmberWarning)
                            }
                        }
                    }
                }
            }

            // Developer / Demo Simulation Triggers (Clearly Demarcated)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF1F5F9),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "[DEMO SIMULATION ONLY] Testing Sandbox",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Navy800
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSimulateCall(SimulatedCallScenario.MATCHED_TEST_LEAD) },
                            modifier = Modifier.weight(1f).heightIn(min = 34.dp),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Simulate Lead Call", fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = { onSimulateCall(SimulatedCallScenario.NON_LEAD_NUMBER) },
                            modifier = Modifier.weight(1f).heightIn(min = 34.dp),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Simulate Non-Lead", fontSize = 10.sp)
                        }
                    }
                }
            }

            // Navigation Row: Back and Continue
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Back", fontSize = 13.sp)
                }

                Button(
                    onClick = onContinue,
                    enabled = canContinue,
                    modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TealSuccess,
                        disabledContainerColor = Color(0xFFCBD5E1)
                    )
                ) {
                    Text(
                        text = "View Result →",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (canContinue) Color.White else Color(0xFF64748B)
                    )
                }
            }
        }
    }

    // Configure/Add Test Lead Dialog
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
                        onValueChange = {
                            nameInput = it
                            validationError = null
                        },
                        label = { Text("Lead Name") },
                        placeholder = { Text("e.g. Sales Test") },
                        singleLine = true,
                        isError = validationError?.contains("name", ignoreCase = true) == true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = numberInput,
                        onValueChange = {
                            numberInput = it
                            validationError = null
                        },
                        label = { Text("Phone Number (+91...)") },
                        placeholder = { Text("e.g. 9876543210 or +91...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        isError = validationError?.contains("phone", ignoreCase = true) == true ||
                                validationError?.contains("number", ignoreCase = true) == true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Do-Not-Record (DNR)", fontSize = 12.sp)
                        Switch(checked = isDnrInput, onCheckedChange = { isDnrInput = it })
                    }

                    if (validationError != null) {
                        Text(
                            text = validationError!!,
                            color = RedDanger,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val result = onAddLead(nameInput, numberInput, isDnrInput)
                        result.fold(
                            onSuccess = {
                                validationError = null
                                nameInput = ""
                                numberInput = ""
                                isDnrInput = false
                                showAddDialog = false
                            },
                            onFailure = { error ->
                                validationError = error.message ?: "Failed to save lead."
                            }
                        )
                    }
                ) {
                    Text("Save Lead")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        validationError = null
                        showAddDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Switch Configured Lead Modal Dialog
    if (showSwitchDialog && testLeads.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showSwitchDialog = false },
            title = { Text("Select Test Lead", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    testLeads.forEach { lead ->
                        val isSelected = lead.id == primaryLead?.id
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFFEFF6FF) else Color(0xFFF8FAFC),
                            border = BorderStroke(1.dp, if (isSelected) BlueAccent else Color(0xFFE2E8F0)),
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(lead.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(
                                        PrivacyRedactor.redactPhoneNumber(lead.normalizedNumber),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Navy900
                                    )
                                }

                                if (isSelected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = BlueAccent, modifier = Modifier.size(18.dp))
                                } else {
                                    IconButton(
                                        onClick = { onRemoveLead(lead.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = RedDanger, modifier = Modifier.size(16.dp))
                                    }
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
