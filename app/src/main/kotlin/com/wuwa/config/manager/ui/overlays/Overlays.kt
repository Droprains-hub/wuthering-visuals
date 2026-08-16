package com.wuwa.config.manager.ui.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wuwa.config.manager.R
import com.wuwa.config.manager.ui.MainViewModel
import com.wuwa.config.manager.ui.components.AppCard
import kotlinx.coroutines.delay
import kotlin.random.Random

// ===================== First run overlay =====================

@Composable
fun FirstRunOverlay(vm: MainViewModel, onExit: () -> Unit) {
    if (!vm.showFirstRun) return
    var page by remember { mutableIntStateOf(1) }
    var countdown by remember { mutableIntStateOf(30) }
    var agreement by remember { mutableStateOf("") }
    var agreementError by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var answerError by remember { mutableStateOf(false) }
    var recovering by remember { mutableStateOf(false) }
    val question = remember { MathQuestion.generate() }

    LaunchedEffect(page) {
        if (page == 1) {
            countdown = 30
            while (countdown > 0) {
                delay(1000)
                countdown--
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .heightIn(max = 600.dp)
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(top = 20.dp, start = 20.dp, end = 20.dp, bottom = 16.dp),
            ) {
                if (recovering) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (vm.isFirstRunRecoveryButtonEnabled()) {
                                vm.app.getString(R.string.first_run_restore_rotation_success)
                            } else {
                                vm.app.getString(R.string.first_run_restore_rotation_connecting)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { recovering = false }) {
                            Text(vm.app.getString(R.string.close))
                        }
                    }
                } else {
                    // ---- Header (fixed) ----
                    Text(
                        text = vm.app.getString(R.string.first_run_notice_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = vm.app.getString(
                            if (page == 1) R.string.first_run_page_one_label
                            else R.string.first_run_page_two_label
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))

                    // ---- Scrollable reading content ----
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (page == 1) {
                            AppCard(
                                shape = RoundedCornerShape(22.dp),
                                background = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        text = vm.app.getString(R.string.first_run_statement_section),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = vm.app.getString(R.string.first_run_statement_body),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            AppCard(
                                shape = RoundedCornerShape(22.dp),
                                background = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        text = vm.app.getString(R.string.first_run_section_notice_bracketed),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = vm.app.getString(R.string.first_run_notice_test_body),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = vm.app.getString(R.string.first_run_section_usage_bracketed),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = vm.app.getString(R.string.first_run_usage_test_body),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        } else {
                            AppCard(
                                shape = RoundedCornerShape(22.dp),
                                background = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        text = vm.app.getString(R.string.first_run_guide_instruction,
                                            question.a, question.operator, question.b),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    // ---- Footer (fixed) ----
                    if (page == 1) {
                        Text(
                            text = if (countdown > 0) {
                                vm.app.getString(R.string.first_run_countdown_remaining, countdown)
                            } else {
                                vm.app.getString(R.string.first_run_countdown_complete)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(50),
                                )
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (30 - countdown) / 30f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = vm.app.getString(R.string.first_run_agreement_instruction),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = agreement,
                            onValueChange = {
                                agreement = it
                                agreementError = false
                            },
                            label = { Text(vm.app.getString(R.string.first_run_agreement_hint)) },
                            singleLine = true,
                            enabled = countdown <= 0,
                            isError = agreementError,
                            supportingText = {
                                if (agreementError) {
                                    Text(vm.app.getString(R.string.first_run_agreement_required))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        OutlinedTextField(
                            value = answer,
                            onValueChange = {
                                answer = it.filter { c -> c.isDigit() || c == '-' }.take(4)
                                answerError = false
                            },
                            label = { Text(vm.app.getString(R.string.first_run_math_answer_hint)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = answerError,
                            supportingText = {
                                if (answerError) {
                                    Text(vm.app.getString(R.string.first_run_guide_required))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        OutlinedButton(
                            onClick = {
                                if (page == 1) {
                                    onExit()
                                } else {
                                    page = 1
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = if (page == 2) {
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                )
                            },
                        ) {
                            Text(vm.app.getString(if (page == 1) R.string.first_run_reject else R.string.first_run_back))
                        }
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = {
                                if (page == 1) {
                                    if (agreement.trim() == vm.app.getString(R.string.first_run_agreement_exact)) {
                                        page = 2
                                    } else {
                                        agreementError = true
                                    }
                                } else {
                                    val expected = if (question.operator == "+") {
                                        question.a + question.b
                                    } else {
                                        question.a - question.b
                                    }
                                    if (answer.trim().toIntOrNull() == expected) {
                                        vm.acceptFirstRun()
                                    } else {
                                        answerError = true
                                    }
                                }
                            },
                            enabled = page == 2 || countdown <= 0,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                        ) {
                            Text(
                                vm.app.getString(
                                    if (page == 1) R.string.first_run_accept else R.string.first_run_guide_accept
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = vm.app.getString(R.string.first_run_restore_rotation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(50),
                            )
                            .clickable {
                                recovering = true
                                vm.requestEmergencyRotationRecovery()
                            }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private data class MathQuestion(val a: Int, val b: Int, val operator: String) {
    companion object {
        fun generate(): MathQuestion {
            val add = Random.nextBoolean()
            val a = Random.nextInt(1, 21)
            val b = if (add) Random.nextInt(1, 21) else Random.nextInt(1, a + 1)
            return MathQuestion(a, b, if (add) "+" else "-")
        }
    }
}

// ===================== Rotation recovery overlay =====================

@Composable
fun RotationRecoveryOverlay(vm: MainViewModel) {
    if (!vm.showRotationRecovery) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.ScreenRotation,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = vm.app.getString(R.string.rotation_recovery_page_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = vm.rotationRecoveryStatus,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = vm.app.getString(R.string.rotation_recovery_page_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            AppCard(
                shape = RoundedCornerShape(18.dp),
                background = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = vm.rotationRecoverySnapshot,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { vm.retryRotationRecovery() },
                enabled = vm.rotationRecoveryRetryEnabled,
                modifier = Modifier.height(48.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(vm.app.getString(R.string.rotation_recovery_retry))
            }
        }
    }
}
