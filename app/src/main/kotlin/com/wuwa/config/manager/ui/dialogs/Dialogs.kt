package com.wuwa.config.manager.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wuwa.config.manager.R
import com.wuwa.config.manager.data.ManualBackupInfo
import com.wuwa.config.manager.model.GameServer
import com.wuwa.config.manager.ui.ChoicePanel
import com.wuwa.config.manager.ui.DialogTone
import com.wuwa.config.manager.ui.LogManagerData
import com.wuwa.config.manager.ui.MainViewModel
import com.wuwa.config.manager.ui.ManualManagerData
import com.wuwa.config.manager.ui.PanelDialog
import com.wuwa.config.manager.ui.RestoreCenterData
import com.wuwa.config.manager.ui.TextInputRequest
import com.wuwa.config.manager.ui.components.AppCard
import com.wuwa.config.manager.ui.components.statusColor

// ===================== Panel dialog =====================

@Composable
fun PanelDialogView(vm: MainViewModel) {
    val dialog = vm.dialogPanel ?: return
    val (icon, accent) = when (dialog.tone) {
        DialogTone.SUCCESS -> Icons.Rounded.CheckCircle to statusColor(R.color.wuwa_success)
        DialogTone.WARNING -> Icons.Rounded.Warning to statusColor(R.color.wuwa_warning)
        DialogTone.ERROR -> Icons.Rounded.Warning to MaterialTheme.colorScheme.error
        DialogTone.INFO -> Icons.Rounded.Info to MaterialTheme.colorScheme.primary
    }
    AlertDialog(
        onDismissRequest = {
            if (dialog.negativeText == null) {
                vm.dismissPanelDialog()
                dialog.negativeAction?.invoke()
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(accent.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
            }
        },
        title = {
            Text(
                text = dialog.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Text(
                text = dialog.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.dismissPanelDialog()
                    dialog.positiveAction?.invoke()
                },
                colors = if (dialog.destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                },
            ) {
                Text(
                    if (dialog.positiveText.isEmpty()) vm.app.getString(R.string.done)
                    else dialog.positiveText
                )
            }
        },
        dismissButton = {
            if (dialog.negativeText != null) {
                TextButton(
                    onClick = {
                        vm.dismissPanelDialog()
                        dialog.negativeAction?.invoke()
                    },
                ) {
                    Text(dialog.negativeText!!)
                }
            }
        },
    )
}

// ===================== Choice panel =====================

@Composable
fun ChoicePanelView(vm: MainViewModel) {
    val panel = vm.dialogChoice ?: return
    AlertDialog(
        onDismissRequest = { vm.dismissChoicePanel() },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = panel.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                if (panel.description != null) {
                    Text(
                        text = panel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    panel.items.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.dismissChoicePanel()
                                    panel.onChoice(index)
                                }
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (item.detail != null) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = item.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        if (index != panel.items.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.dismissChoicePanel() }) {
                Text(vm.app.getString(R.string.cancel))
            }
        },
    )
}

// ===================== Progress dialog =====================

@Composable
fun ProgressDialogView(vm: MainViewModel) {
    val message = vm.dialogProgress ?: return
    AlertDialog(
        onDismissRequest = {},
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = null,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = vm.app.getString(R.string.dialog_processing_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = vm.app.getString(R.string.dialog_processing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

// ===================== Text input dialog =====================

@Composable
fun TextInputDialogView(vm: MainViewModel) {
    val request = vm.dialogTextInput ?: return
    var value by remember(request) { mutableStateOf(request.initial) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { vm.dismissTextInput() },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = request.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                if (request.message != null) {
                    Text(
                        text = request.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        if (it.length <= request.maxLength) value = it
                    },
                    label = { Text(request.hint) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(
                            text = error ?: "${value.length} / ${request.maxLength}",
                            color = if (error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = value.trim()
                    if (trimmed.isEmpty()) {
                        error = vm.app.getString(R.string.name)
                    } else {
                        vm.dismissTextInput()
                        request.onSave(value)
                    }
                },
            ) {
                Text(vm.app.getString(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissTextInput() }) {
                Text(vm.app.getString(R.string.cancel))
            }
        },
    )
}

// ===================== Server chooser =====================

@Composable
fun ServerChooserDialog(vm: MainViewModel) {
    if (!vm.dialogServerChooser) return
    AlertDialog(
        onDismissRequest = { vm.dismissServerChooser() },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = vm.app.getString(R.string.select_server),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    text = vm.app.getString(R.string.server_chooser_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    GameServer.entries.forEach { server ->
                        val installed = vm.installedServers.contains(server)
                        val selected = vm.selectedServer == server
                        ServerRow(vm, server, installed, selected)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ServerRow(
    vm: MainViewModel,
    server: GameServer,
    installed: Boolean,
    selected: Boolean,
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(16.dp))
            .clickable {
                if (!installed) {
                    vm.showToast(
                        R.string.server_not_installed_message, vm.serverDisplayName(server)
                    )
                } else {
                    vm.selectServer(server)
                    vm.dismissServerChooser()
                }
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(36.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(50),
                ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = vm.serverDisplayName(server),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = vm.getGameVersion(server),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = vm.app.getString(if (installed) R.string.server_installed else R.string.server_not_installed),
            style = MaterialTheme.typography.labelMedium,
            color = statusColor(if (installed) R.color.wuwa_success else R.color.wuwa_error),
            modifier = Modifier
                .background(
                    if (installed) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (selected) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ===================== Restore center =====================

@Composable
fun RestoreCenterDialog(vm: MainViewModel) {
    val data = vm.dialogRestoreCenter ?: return
    AlertDialog(
        onDismissRequest = { vm.dismissRestoreCenter() },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = vm.app.getString(R.string.restore_center_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = vm.app.getString(R.string.restore_center_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (data.hasInitial) {
                    RestoreRow(
                        title = vm.app.getString(R.string.initial_protection_backup),
                        detail = vm.app.getString(R.string.initial_protection_detail),
                        onClick = { vm.confirmInitialRestore() },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (data.snapshots.isEmpty() && !data.hasInitial) {
                    Text(
                        text = vm.app.getString(R.string.restore_center_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    data.snapshots.forEach { snapshot ->
                        RestoreRow(
                            title = vm.displayNameForSnapshot(snapshot),
                            detail = vm.snapshotTypeLabel(snapshot),
                            onClick = { vm.confirmManualRestore(data.server, snapshot) },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.dismissRestoreCenter() }) {
                Text(vm.app.getString(R.string.close))
            }
        },
    )
}

@Composable
private fun RestoreRow(title: String, detail: String, onClick: () -> Unit) {
    AppCard(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        background = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Restore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ===================== Manual backup manager =====================

@Composable
fun ManualManagerDialog(vm: MainViewModel) {
    val data = vm.dialogManualManager ?: return
    var multiSelect by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = {
            vm.dismissManualManager()
            multiSelect = false
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = vm.app.getString(R.string.manual_manager_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    text = vm.app.getString(R.string.manual_manager_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (data.hasOriginal) {
                    AppCard(
                        onClick = {
                            if (!multiSelect) vm.confirmInitialRestore()
                        },
                        shape = RoundedCornerShape(16.dp),
                        background = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Restore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = vm.app.getString(R.string.initial_backup_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = vm.app.getString(R.string.initial_backup_description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = vm.app.getString(R.string.backup_list_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        multiSelect = !multiSelect
                        selectedNames = emptySet()
                    }) {
                        Text(
                            vm.app.getString(
                                if (multiSelect) R.string.done_selecting else R.string.multi_select
                            )
                        )
                    }
                }

                if (multiSelect) {
                    Row {
                        TextButton(
                            onClick = {
                                selectedNames = data.snapshots.map { it.snapshotName }.toSet()
                            },
                        ) {
                            Text(vm.app.getString(R.string.select_all))
                        }
                        TextButton(
                            onClick = { selectedNames = emptySet() },
                        ) {
                            Text(vm.app.getString(R.string.clear_selection))
                        }
                        Spacer(Modifier.weight(1f))
                        if (selectedNames.isNotEmpty()) {
                            Text(
                                text = vm.app.getString(R.string.selected_count, selectedNames.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                        }
                        Button(
                            onClick = {
                                if (selectedNames.isEmpty()) {
                                    vm.showToast(R.string.select_backup_first)
                                } else {
                                    vm.confirmDeleteManualBackups(data.server, selectedNames.toList())
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text(vm.app.getString(R.string.delete_selected))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    data.snapshots.forEach { snapshot ->
                        ManualBackupRow(
                            vm = vm,
                            data = data,
                            snapshot = snapshot,
                            multiSelect = multiSelect,
                            selected = snapshot.snapshotName in selectedNames,
                            onToggle = {
                                selectedNames = if (snapshot.snapshotName in selectedNames) {
                                    selectedNames - snapshot.snapshotName
                                } else {
                                    selectedNames + snapshot.snapshotName
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.dismissManualManager()
                multiSelect = false
            }) {
                Text(vm.app.getString(R.string.close))
            }
        },
    )
}

@Composable
private fun ManualBackupRow(
    vm: MainViewModel,
    data: ManualManagerData,
    snapshot: ManualBackupInfo,
    multiSelect: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (multiSelect) Modifier.clickable { onToggle() } else Modifier
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiSelect) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = vm.displayNameForSnapshot(snapshot),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = vm.snapshotTypeLabel(snapshot),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!multiSelect) {
            IconButton(onClick = { vm.showRenameBackupDialog(data.server, snapshot) }) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = vm.app.getString(R.string.rename),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = {
                vm.confirmDeleteManualBackups(data.server, listOf(snapshot.snapshotName))
            }) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = vm.app.getString(R.string.delete_backup),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ===================== Log manager =====================

@Composable
fun LogManagerDialog(vm: MainViewModel) {
    val data = vm.dialogLogManager ?: return
    AlertDialog(
        onDismissRequest = { vm.dismissLogManager() },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text = vm.app.getString(R.string.log_manager_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                AppCard(
                    shape = RoundedCornerShape(16.dp),
                    background = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = vm.app.getString(R.string.log_storage_location),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = vm.app.getString(R.string.log_storage_path_value),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(
                        onClick = { vm.shareCurrentLog() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(vm.app.getString(R.string.share_current_log))
                    }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { vm.shareLogs(data.logs) },
                        enabled = data.logs.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(vm.app.getString(R.string.share_all_logs))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = vm.app.getString(R.string.log_list_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                if (data.logs.isEmpty()) {
                    Text(
                        text = vm.app.getString(R.string.log_list_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        data.logs.forEach { log ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = if (log.currentSession) {
                                            vm.app.getString(R.string.log_current_session, log.displayName)
                                        } else {
                                            log.displayName
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = vm.logFileDetail(log),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { vm.shareLogs(listOf(log)) }) {
                                    Icon(
                                        Icons.Rounded.Share,
                                        contentDescription = vm.app.getString(R.string.share_log),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (log.currentSession) {
                                            vm.showToast(R.string.log_current_protected)
                                        } else {
                                            vm.confirmDeleteLog(log)
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = vm.app.getString(R.string.delete_log),
                                        tint = if (log.currentSession) {
                                            MaterialTheme.colorScheme.outline
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.dismissLogManager() }) {
                Text(vm.app.getString(R.string.close))
            }
        },
    )
}
