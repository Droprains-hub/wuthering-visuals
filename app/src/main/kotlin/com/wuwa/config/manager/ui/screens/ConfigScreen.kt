package com.wuwa.config.manager.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuwa.config.manager.R
import com.wuwa.config.manager.model.QualityPreset
import com.wuwa.config.manager.ui.MainViewModel
import com.wuwa.config.manager.ui.components.AppCard
import com.wuwa.config.manager.ui.components.IconBlock
import com.wuwa.config.manager.ui.components.SectionTitle
import com.wuwa.config.manager.ui.components.statusColor

@Composable
fun ConfigScreen(vm: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ConfigTargetCard(vm)
        SectionTitle(vm.app.getString(R.string.quality_preset_section))
        PresetCard(vm)
        SectionTitle(vm.app.getString(R.string.resolution_experiment_title))
        ResolutionCard(vm)
    }
}

@Composable
private fun ConfigTargetCard(vm: MainViewModel) {
    AppCard(
        modifier = Modifier.padding(horizontal = 20.dp),
        onClick = { vm.openServerChooser() },
        enabled = !vm.operationRunning,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = vm.configTargetName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = vm.configTargetState,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconBlock(
                icon = Icons.Rounded.KeyboardArrowRight,
                container = MaterialTheme.colorScheme.surfaceContainer,
                size = 36,
            )
        }
    }
}

@Composable
private fun PresetCard(vm: MainViewModel) {
    val ready = vm.privilegeState.isReady()
    val presetReady = vm.presetsReady && vm.repository.presetsReady(vm.selectedPreset)
    val canReplace = !vm.operationRunning && ready && vm.selectedServer != null && presetReady

    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = vm.presetCardTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = vm.app.getString(R.string.replace_card_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = vm.app.getString(R.string.preset_level_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            PresetSegmentedSelector(vm)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = vm.presetStatus,
                    style = MaterialTheme.typography.titleSmall,
                    color = statusColor(vm.presetStatusColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = vm.presetSelectionDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(vm.presetSelectionDetailColor),
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { vm.runReplace() },
                enabled = canReplace,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(
                    Icons.Rounded.Bolt,
                    contentDescription = null,
                    modifier = Modifier.width(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(vm.app.getString(R.string.replace_quality), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PresetSegmentedSelector(vm: MainViewModel) {
    val presets = listOf(
        QualityPreset.LOW to vm.app.getString(R.string.preset_low),
        QualityPreset.MEDIUM to vm.app.getString(R.string.preset_medium),
        QualityPreset.HIGH to vm.app.getString(R.string.preset_high),
        QualityPreset.EXTREME to vm.app.getString(R.string.preset_extreme),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        presets.forEachIndexed { index, (preset, label) ->
            SegmentedButton(
                selected = vm.selectedPreset == preset,
                onClick = { vm.selectPreset(preset) },
                enabled = !vm.operationRunning,
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = presets.size,
                ),
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ResolutionCard(vm: MainViewModel) {
    val ready = vm.privilegeState.isReady()
    val canLaunch = !vm.operationRunning && ready && vm.selectedServer != null
    val canRestore = !vm.operationRunning && ready

    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = vm.app.getString(R.string.resolution_sensor_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.startPortraitLaunch() },
                enabled = canLaunch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .alpha(if (canLaunch) 1f else 0.45f),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Icon(
                    Icons.Rounded.ScreenRotation,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(vm.app.getString(R.string.portrait_launch_game), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.restoreAutomaticRotation() },
                enabled = canRestore,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .alpha(if (canRestore) 1f else 0.45f),
            ) {
                Text(vm.app.getString(R.string.restore_auto_rotation))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = vm.rotationStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
