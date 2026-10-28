package com.leads365.apk.ui.leads

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.database.entity.CallEntity
import com.leads365.apk.database.entity.LeadEntity
import com.leads365.apk.ui.theme.BlueAccent
import com.leads365.apk.ui.theme.Navy900
import com.leads365.apk.ui.theme.TextSecondaryLight
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCallOutcomeDialog(
    call: CallEntity,
    lead: LeadEntity,
    onSaveOutcome: (outcome: String, notes: String?, nextFollowUpEpochMs: Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedOutcome by remember { mutableStateOf("Connected") }
    var notesText by remember { mutableStateOf("") }
    var selectedFollowUpMs by remember { mutableStateOf<Long?>(null) }

    val outcomeOptions = listOf(
        "Connected",
        "Interested",
        "Follow-up Needed",
        "Not Interested",
        "No Answer / Busy",
        "Wrong Number"
    )

    val durationMin = call.durationSeconds / 60
    val durationSec = call.durationSeconds % 60
    val durationStr = if (durationMin > 0) "${durationMin}m ${durationSec}s" else "${durationSec}s"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Post-Call Summary",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Navy900
                )
                Text(
                    text = "${lead.name} • $durationStr",
                    fontSize = 12.sp,
                    color = TextSecondaryLight
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Outcome Selection
                Text(
                    text = "Call Outcome",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Navy900
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    outcomeOptions.forEach { option ->
                        val isSelected = selectedOutcome == option
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedOutcome = option },
                            label = { Text(option, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BlueAccent,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                // 2. Notes Field
                OutlinedTextField(
                    value = notesText,
                    onValueChange = { notesText = it },
                    label = { Text("Call Notes (Optional)", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    shape = RoundedCornerShape(8.dp)
                )

                // 3. Follow-up Date Quick Presets
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Next Follow-up",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = Navy900
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val now = System.currentTimeMillis()
                        val presets = listOf(
                            Pair("Tomorrow", now + 86_400_000L),
                            Pair("In 3 Days", now + (3 * 86_400_000L)),
                            Pair("Next Week", now + (7 * 86_400_000L))
                        )
                        presets.forEach { (label, epochMs) ->
                            val isSelected = selectedFollowUpMs != null &&
                                    Math.abs(selectedFollowUpMs!! - epochMs) < 60_000L
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedFollowUpMs = if (isSelected) null else epochMs
                                },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                        if (selectedFollowUpMs != null) {
                            TextButton(onClick = { selectedFollowUpMs = null }) {
                                Text("Clear", fontSize = 11.sp, color = Color.Red)
                            }
                        }
                    }

                    if (selectedFollowUpMs != null) {
                        val formattedDate = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
                            .format(Date(selectedFollowUpMs!!))
                        Text(
                            text = "Scheduled: $formattedDate",
                            fontSize = 11.sp,
                            color = BlueAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveOutcome(selectedOutcome, notesText.takeIf { it.isNotBlank() }, selectedFollowUpMs)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Navy900)
            ) {
                Text("Save Outcome")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip", color = TextSecondaryLight)
            }
        }
    )
}
