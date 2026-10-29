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
        color = Color.White
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
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
                            if (step.stepNumber <= currentStep.stepNumber) {
                                onStepClick(step)
                            }
                        }
                    )

                    if (index < steps.size - 1) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .height(2.dp)
                                .background(
                                    if (step.stepNumber < currentStep.stepNumber) Emerald600
                                    else Slate200
                                )
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Slate200)
            )
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
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val circleBg = when {
            isCompleted -> Emerald600
            isCurrent -> Blue600
            else -> Slate200
        }
        val circleTextColor = when {
            isCompleted || isCurrent -> Color.White
            else -> Slate600
        }

        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(circleBg),
            contentAlignment = Alignment.Center
        ) {
            if (isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Completed",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = stepNumber.toString(),
                    color = circleTextColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        val labelColor = when {
            isCompleted -> Emerald600
            isCurrent -> Blue600
            else -> Slate500
        }

        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = if (isCurrent || isCompleted) FontWeight.Bold else FontWeight.Medium,
            color = labelColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}
