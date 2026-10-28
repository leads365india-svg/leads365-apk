package com.leads365.apk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.core.*
import com.leads365.apk.ui.SimulatedCallScenario
import com.leads365.apk.ui.theme.*

/**
 * E. Test-Call & Lead Gate Workflow Card
 * Manages test-lead allowlist, DNR toggles, controlled call simulation scenarios,
 * and recent call gate/match inspection. Designed mobile-first for 320dp-440dp screens.
 */
@Composable
fun TestCallWorkflowCard(
    testLeads: List<TestLead>,
    onAddLead: (name: String, number: String, isDnr: Boolean) -> Unit,
    onRemoveLead: (id: String) -> Unit,
    onToggleDnr: (id: String, dnr: Boolean) -> Unit,
    onSimulateCall: (SimulatedCallScenario) -> Unit,
    recentCallEvent: CallEvent?,
    gateResult: GateEvaluationResult?,
    candidateRecordings: List<RecordingCandidate>,
    matchingResult: RecordingMatchResult?,
    onRequestUpload: (RecordingCandidate) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
            // Header: Title and Add Lead Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lead Gate & Call Workflow",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Lead-Only Gate strictly ignores all non-lead calls.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Lead", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Lead Allowlist
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Configured Test Leads (${testLeads.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                testLeads.forEach { lead ->
                    LeadItemCard(
                        lead = lead,
                        onToggleDnr = { onToggleDnr(lead.id, it) },
                        onRemove = { onRemoveLead(lead.id) }
                    )
                }
            }

            // Controlled Feasibility Simulations: 2x2 Grid for touch usability
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Controlled Feasibility Test Calls",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Simulates call events to verify gating and ambiguity logic on devices without live SIM calls.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )

                // Row 1: Lead Call & Non-Lead Call
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSimulateCall(SimulatedCallScenario.MATCHED_TEST_LEAD) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("1. Lead Call", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("Gate passes", fontSize = 9.sp, color = TealSuccess)
                        }
                    }

                    OutlinedButton(
                        onClick = { onSimulateCall(SimulatedCallScenario.NON_LEAD_NUMBER) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("2. Non-Lead", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("Gate halts", fontSize = 9.sp, color = TextSecondaryLight)
                        }
                    }
                }

                // Row 2: DNR Call & Ambiguous Call
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSimulateCall(SimulatedCallScenario.DO_NOT_RECORD_LEAD) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("3. DNR Call", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("No lookup", fontSize = 9.sp, color = RedDanger)
                        }
                    }

                    OutlinedButton(
                        onClick = { onSimulateCall(SimulatedCallScenario.AMBIGUOUS_RECORDINGS) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("4. Ambiguous", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("Upload gated", fontSize = 9.sp, color = AmberWarning)
                        }
                    }
                }
            }

            // Recent Observed Call State Card
            RecentCallEventSection(
                call = recentCallEvent,
                gateResult = gateResult,
                matchingResult = matchingResult,
                onRequestUpload = onRequestUpload
            )
        }
    }

    // Add Lead Dialog with IME handling
    if (showAddDialog) {
        AddLeadDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, number, dnr ->
                onAddLead(name, number, dnr)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun LeadItemCard(
    lead: TestLead,
    onToggleDnr: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Row 1: Name and DNR Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = lead.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                if (lead.isDoNotRecord) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFFFEE2E2)
                    ) {
                        Text(
                            text = "Do Not Record",
                            color = RedDanger,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // Row 2: Redacted Phone Number
            Text(
                text = PrivacyRedactor.redactPhoneNumber(lead.normalizedNumber),
                fontSize = 11.sp,
                color = TextSecondaryLight,
                fontFamily = FontFamily.Monospace
            )

            // Row 3: Action Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Do-Not-Record (DNR):",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = lead.isDoNotRecord,
                        onCheckedChange = onToggleDnr,
                        modifier = Modifier.height(28.dp)
                    )
                }

                TextButton(
                    onClick = onRemove,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Remove", fontSize = 11.sp, color = RedDanger)
                }
            }
        }
    }
}

@Composable
private fun RecentCallEventSection(
    call: CallEvent?,
    gateResult: GateEvaluationResult?,
    matchingResult: RecordingMatchResult?,
    onRequestUpload: (RecordingCandidate) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Recent Call Event Inspection",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (call == null) {
                Text(
                    text = "No call observed yet. Make a call to a test lead or tap Simulation #1 above.",
                    fontSize = 11.sp,
                    color = TextSecondaryLight,
                    lineHeight = 15.sp
                )
            } else {
                // Call Summary Row
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Number: ${PrivacyRedactor.redactPhoneNumber(call.normalizedNumber)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Duration: ${TimeHelpers.formatDurationSeconds(call.durationSeconds)}",
                            fontSize = 11.sp,
                            color = TextSecondaryLight
                        )
                    }
                    Text(
                        text = "${TimeHelpers.formatEpochMs(call.startTimeEpochMs)} • ${call.direction}",
                        fontSize = 10.sp,
                        color = TextTertiaryLight
                    )
                }

                // Lead Gate Evaluation Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = "Lead Gate:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    when (gateResult) {
                        is GateEvaluationResult.Allowed -> {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFDCFCE7)) {
                                Text(
                                    "PASSED (Allowed Test Lead)",
                                    color = Color(0xFF15803D),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        is GateEvaluationResult.RejectedNotALead -> {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFF1F5F9)) {
                                Text(
                                    "STOPPED: Not a test lead",
                                    color = TextSecondaryLight,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        is GateEvaluationResult.RejectedDoNotRecord -> {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFFEE2E2)) {
                                Text(
                                    "STOPPED: Do-Not-Record Flag",
                                    color = RedDanger,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        is GateEvaluationResult.RejectedDuplicateCall -> {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFFEF3C7)) {
                                Text(
                                    "STOPPED: Duplicate Call Event",
                                    color = Color(0xFFB45309),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        null -> Text("Pending", fontSize = 11.sp, color = TextSecondaryLight)
                    }
                }

                // Matching Result Section
                if (matchingResult != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = "Match Status:", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        val (bg, fg) = when (matchingResult.status) {
                            MatchStatus.FILE_FOUND -> Pair(Color(0xFFDCFCE7), Color(0xFF15803D))
                            MatchStatus.NO_FILE_FOUND -> Pair(Color(0xFFF1F5F9), TextSecondaryLight)
                            MatchStatus.AMBIGUOUS -> Pair(Color(0xFFFEF3C7), Color(0xFFB45309))
                            MatchStatus.INACCESSIBLE -> Pair(Color(0xFFFEE2E2), RedDanger)
                        }
                        Surface(shape = RoundedCornerShape(4.dp), color = bg) {
                            Text(
                                text = matchingResult.status.name,
                                color = fg,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = matchingResult.reason,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )

                    // Matched File Upload Card
                    if (matchingResult.status == MatchStatus.FILE_FOUND && matchingResult.matchedCandidate != null) {
                        val matched = matchingResult.matchedCandidate
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, TealSuccess)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Matched: ${PrivacyRedactor.redactFilename(matched.displayName)}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF15803D)
                                    )
                                    Text(
                                        text = "${TimeHelpers.formatSize(matched.sizeBytes)} • ${matched.mimeType} • ${matched.sourceMethod.name}",
                                        fontSize = 10.sp,
                                        color = TextSecondaryLight
                                    )
                                }

                                Button(
                                    onClick = { onRequestUpload(matched) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = TealSuccess),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Upload Test Recording", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddLeadDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, number: String, isDnr: Boolean) -> Unit
) {
    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }
    var isDnrInput by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Test Lead", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Lead Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )

                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    label = { Text("Phone Number (e.g. 9876543210)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Done
                    )
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Checkbox(
                        checked = isDnrInput,
                        onCheckedChange = { isDnrInput = it }
                    )
                    Text("Mark as Do-Not-Record (DNR)", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (phoneInput.isNotBlank()) {
                        onAdd(nameInput.ifBlank { "Test Lead" }, phoneInput.trim(), isDnrInput)
                    }
                },
                modifier = Modifier.heightIn(min = 40.dp)
            ) {
                Text("Add Lead")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 40.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * F. Safe Test-Upload Pipeline Card
 * Clean endpoint input, live streaming progress, and explicit retry controls.
 */
@Composable
fun TestUploadPanelCard(
    endpointUrl: String,
    onEndpointChanged: (String) -> Unit,
    uploadState: UploadState,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Safe Test-Upload Pipeline",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = "Streams only uniquely matched test recordings using chunked OkHttp pipeline (no RAM buffering).",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            // Endpoint Input with IME configuration
            OutlinedTextField(
                value = endpointUrl,
                onValueChange = onEndpointChanged,
                label = { Text("Test Upload Endpoint URL") },
                placeholder = { Text("http://192.168.1.X:8080/upload") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                supportingText = {
                    Text(
                        text = if (endpointUrl.isBlank()) "Endpoint not set (upload disabled)" else "Destination: ${PrivacyRedactor.redactUrl(endpointUrl)}",
                        fontSize = 10.sp,
                        color = if (endpointUrl.isBlank()) AmberWarning else TealSuccess
                    )
                }
            )

            // Dynamic Upload State Representation
            when (uploadState) {
                is UploadState.Idle -> {
                    Text(
                        text = "Awaiting matched test recording and explicit confirmation. Automatic upload is strictly prohibited.",
                        fontSize = 11.sp,
                        color = TextSecondaryLight
                    )
                }
                is UploadState.Uploading -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF0FDF4),
                        border = BorderStroke(1.dp, Color(0xFFBBF7D0))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Streaming Chunked Audio...", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15803D))
                                Text("${uploadState.progressPercent}% (${TimeHelpers.formatSize(uploadState.bytesSent)})", fontSize = 11.sp, color = Color(0xFF166534))
                            }
                            LinearProgressIndicator(
                                progress = { uploadState.progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                color = TealSuccess,
                                trackColor = Color(0xFFDCFCE7)
                            )
                        }
                    }
                }
                is UploadState.Success -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFDCFCE7),
                        border = BorderStroke(1.dp, Color(0xFF86EFAC))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF15803D), modifier = Modifier.size(20.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Upload Successful (HTTP ${uploadState.httpStatusCode})", color = Color(0xFF15803D), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(uploadState.responseMessage, color = Color(0xFF166534), fontSize = 11.sp)
                            }
                        }
                    }
                }
                is UploadState.Failed -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFEE2E2),
                        border = BorderStroke(1.dp, Color(0xFFFCA5A5))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = RedDanger, modifier = Modifier.size(18.dp))
                                Text("Upload Failed", color = RedDanger, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Text(uploadState.errorMessage, color = Color(0xFF991B1B), fontSize = 11.sp, lineHeight = 15.sp)

                            if (uploadState.isRetryable) {
                                Button(
                                    onClick = onRetry,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 40.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = RedDanger),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Retry Upload", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                is UploadState.ConfirmationRequired -> {
                    // Modal dialog handles confirmation
                }
            }
        }
    }
}

/**
 * Mobile-First Upload Confirmation Dialog
 * Scrollable content for smaller screens, full touch targets, clear metadata and privacy guarantee.
 */
@Composable
fun UploadConfirmationDialog(
    candidate: RecordingCandidate,
    checksum: String,
    endpointUrl: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CloudUpload, contentDescription = null, tint = BlueAccent, modifier = Modifier.size(24.dp)) },
        title = {
            Text(
                text = "Confirm Test Audio Upload",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Stream this single test audio recording to your designated test server:",
                    fontSize = 12.sp
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text("File: ${PrivacyRedactor.redactFilename(candidate.displayName)}", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text("Size: ${TimeHelpers.formatSize(candidate.sizeBytes)} • ${candidate.mimeType}", fontSize = 10.sp)
                        Text("SHA-256: ${checksum.take(16)}...", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("Destination: ${PrivacyRedactor.redactUrl(endpointUrl)}", fontSize = 10.sp, color = BlueAccent)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFEF3C7)
                ) {
                    Text(
                        text = "Guarantee: Original dialer recording will NOT be modified, renamed, moved, or deleted.",
                        fontSize = 10.sp,
                        color = Color(0xFF92400E),
                        modifier = Modifier.padding(8.dp),
                        lineHeight = 14.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TealSuccess),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Confirm & Stream", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * G. Diagnostics Card
 * Collapsible section, clean monospace telemetry view with wrapping,
 * and a full-width Copy Redacted JSON button.
 */
@Composable
fun DiagnosticsCard(
    auditLogs: List<String>,
    onExportDiagnostics: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Title Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Audit Log & Diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (expanded) "Collapse" else "Expand", fontSize = 11.sp)
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = "Privacy guarantee: Phone numbers, customer names, and tokens are automatically redacted.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )

            // Copy Action Button
            OutlinedButton(
                onClick = onExportDiagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy Redacted JSON", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            // Collapsible Log Box with text wrapping
            if (expanded) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F172A)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (auditLogs.isEmpty()) {
                            Text(
                                text = "[Awaiting telemetry events...]",
                                color = Color(0xFF64748B),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        } else {
                            auditLogs.takeLast(15).forEach { line ->
                                Text(
                                    text = PrivacyRedactor.redactFilename(PrivacyRedactor.redactPhoneNumber(line)),
                                    color = Color(0xFF38BDF8),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
