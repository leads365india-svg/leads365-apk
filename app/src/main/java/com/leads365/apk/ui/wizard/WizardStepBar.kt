package com.leads365.apk.ui.wizard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leads365.apk.ui.WizardStep
import com.leads365.apk.ui.theme.*

@Composable
fun WizardStepBar(
    currentStep: WizardStep,
    onStepClick: (WizardStep) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Slate200)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val steps = WizardStep.values()
            steps.forEachIndexed { index, step ->
                val isCurrent = step == currentStep
                val isCompleted = step.stepNumber < currentStep.stepNumber

                StepIndicatorItem(
                    stepNumber = step.stepNumber,
                    title = when (step) {
                        WizardStep.PERMISSIONS -> "Perms"
                        WizardStep.RECORDING_FOLDER -> "Folder"
                        WizardStep.TEST_CALL -> "Test Call"
                        WizardStep.TEST_RESULT -> "Result"
                    },
                    isCurrent = isCurrent,
                    isCompleted = isCompleted,
                    onClick = {
                        // Allow tapping back to previous steps
                        if (step.stepNumber <= currentStep.stepNumber) {
                            onStepClick(step)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )

                if (index < steps.size - 1) {
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(2.dp)
                            .background(
                                if (step.stepNumber < currentStep.stepNumber) Emerald500
                                else Slate200
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun StepIndicatorItem(
    stepNumber: Int,
    title: String,
    isCurrent: Boolean,
    isCompleted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable(enabled = isCompleted, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        val circleBg = when {
            isCompleted -> Emerald600
            isCurrent -> Blue600
            else -> Slate100
        }
        val circleTextColor = when {
            isCompleted || isCurrent -> Color.White
            else -> Slate500
        }

        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(circleBg),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = stepNumber.toString(),
                    color = circleTextColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) Slate900 else Slate500,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}
