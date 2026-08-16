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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuwa.config.manager.R
import com.wuwa.config.manager.ui.MainViewModel
import com.wuwa.config.manager.ui.components.AppCard
import com.wuwa.config.manager.ui.components.IconBlock
import com.wuwa.config.manager.ui.components.SectionTitle
import com.wuwa.config.manager.ui.components.statusColor

@Composable
fun BackupScreen(vm: MainViewModel, onOpenStorage: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(12.dp))
        BackupTargetCard(vm)
        Spacer(Modifier.height(12.dp))
        AutomaticRetentionCard(vm)
        SectionTitle(vm.app.getString(R.string.backup_center_title))
        BackupCenterCard(vm)
        SectionTitle(vm.app.getString(R.string.storage_settings_title))
        StorageCard(vm, onOpenStorage)
    }
}

@Composable
private fun BackupTargetCard(vm: MainViewModel) {
    AppCard(
        modifier = Modifier.padding(horizontal = 20.dp),
        onClick = { vm.openServerChooser() },
        enabled = !vm.operationRunning,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBlock(
                icon = Icons.Rounded.FolderOpen,
                container = MaterialTheme.colorScheme.primaryContainer,
                size = 48,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = vm.app.getString(R.string.current_backup_target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = vm.backupTargetName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = vm.backupTargetState,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutomaticRetentionCard(vm: MainViewModel) {
    AppCard(
        modifier = Modifier.padding(horizontal = 20.dp),
        onClick = { vm.showAutomaticBackupRetentionChooser() },
        enabled = !vm.operationRunning,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBlock(
                icon = Icons.Rounded.Add,
                container = MaterialTheme.colorScheme.surfaceContainer,
                size = 40,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = vm.app.getString(R.string.automatic_backup_retention_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = vm.retentionLabel(vm.retentionLimit),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = vm.app.getString(R.string.automatic_backup_retention_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackupCenterCard(vm: MainViewModel) {
    val ready = vm.privilegeState.isReady()
    val canOperate = !vm.operationRunning && ready && vm.selectedServer != null

    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBlock(
                    icon = Icons.Rounded.Backup,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    size = 48,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = vm.app.getString(R.string.backup_library_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = vm.backupLibrarySummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { vm.openManualBackupManager() },
                    enabled = !vm.operationRunning,
                ) {
                    Text(vm.app.getString(R.string.manage))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = vm.app.getString(R.string.backup_center_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.runManualBackup() },
                enabled = canOperate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .alpha(if (canOperate) 1f else 0.45f),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(vm.app.getString(R.string.create_snapshot), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { vm.openRestoreCenter() },
                enabled = canOperate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .alpha(if (canOperate) 1f else 0.45f),
                colors = ButtonDefaults.filledTonalButtonColors(),
            ) {
                Icon(Icons.Rounded.Restore, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(vm.app.getString(R.string.open_restore_center), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StorageCard(vm: MainViewModel, onOpenStorage: () -> Unit) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = vm.app.getString(R.string.backup_storage_location),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = vm.storageRoot,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(
                    onClick = { vm.showChangeStorageDialog() },
                    enabled = !vm.operationRunning && vm.privilegeState.isReady(),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        modifier = Modifier.width(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(vm.app.getString(R.string.change_location))
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = onOpenStorage,
                    enabled = !vm.operationRunning,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.width(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(vm.app.getString(R.string.open_folder))
                }
            }
        }
    }
}
