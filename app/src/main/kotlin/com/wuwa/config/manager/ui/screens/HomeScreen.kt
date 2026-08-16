package com.wuwa.config.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuwa.config.manager.R
import com.wuwa.config.manager.model.GameServer
import com.wuwa.config.manager.ui.MainViewModel
import com.wuwa.config.manager.ui.components.ActionRow
import com.wuwa.config.manager.ui.components.AppCard
import com.wuwa.config.manager.ui.components.Divider
import com.wuwa.config.manager.ui.components.SectionTitle
import com.wuwa.config.manager.ui.components.StatusCard
import com.wuwa.config.manager.ui.components.loadColor
import com.wuwa.config.manager.ui.components.statusColor

@Composable
fun HomeScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(12.dp))
        HomePermissionCard(vm)
        if (vm.permissionWarningVisible) {
            HomePermissionWarningCard(vm, onOpenSettings)
        }
        SectionTitle(vm.app.getString(R.string.device_overview))
        HomeDeviceCard(vm)
        SectionTitle(vm.app.getString(R.string.game_management_title))
        HomeServerSelectorCard(vm)
        SectionTitle(vm.app.getString(R.string.software_status_title))
        HomeSoftwareStatusCard(vm)
    }
}

@Composable
private fun HomePermissionCard(vm: MainViewModel) {
    val icon = when (vm.permissionModeIcon) {
        R.drawable.ic_check -> Icons.Rounded.CheckCircle
        R.drawable.ic_warning -> Icons.Rounded.Warning
        else -> Icons.Rounded.Shield
    }
    AppCard(
        modifier = Modifier.padding(horizontal = 20.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        background = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    icon,
                    contentDescription = null,
                    tint = statusColor(vm.permissionModeColor),
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = vm.app.getString(R.string.permission_banner_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = vm.permissionModeStatus,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun HomePermissionWarningCard(vm: MainViewModel, onOpenSettings: () -> Unit) {
    AppCard(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        background = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = vm.permissionWarningText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenSettings) {
                Text(vm.app.getString(R.string.check_permission))
            }
        }
    }
}

@Composable
private fun HomeDeviceCard(vm: MainViewModel) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = vm.deviceModel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LoadGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = vm.app.getString(R.string.cpu_label),
                    subtitle = vm.deviceProcessor,
                    percent = vm.cpuPercent,
                    available = vm.loadAvailable,
                    circular = true,
                    waitingText = vm.app.getString(R.string.load_waiting),
                )
                LoadGaugeCard(
                    modifier = Modifier.weight(1f),
                    title = vm.app.getString(R.string.gpu_label),
                    subtitle = vm.deviceGpu,
                    percent = vm.gpuPercent,
                    available = vm.loadAvailable,
                    circular = true,
                    waitingText = vm.app.getString(R.string.load_waiting),
                )
            }
            Spacer(Modifier.height(10.dp))
            StatusCard {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = vm.app.getString(R.string.memory_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (vm.loadAvailable) {
                                "${vm.memoryPercent}%"
                            } else vm.app.getString(R.string.load_waiting),
                            style = MaterialTheme.typography.titleSmall,
                            color = loadColor(vm.memoryPercent),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = vm.deviceMemory,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { vm.memoryPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = loadColor(vm.memoryPercent),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            StatusCard {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Rounded.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = vm.app.getString(R.string.system_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = vm.deviceSystem,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = vm.deviceSystemDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadGaugeCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    percent: Int,
    available: Boolean,
    circular: Boolean,
    waitingText: String,
) {
    StatusCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { if (available) percent / 100f else 0f },
                    modifier = Modifier.size(64.dp),
                    color = loadColor(percent),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    strokeWidth = 5.dp,
                )
                Text(
                    text = if (available) "$percent%" else waitingText,
                    style = MaterialTheme.typography.titleSmall,
                    color = loadColor(percent),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun HomeServerSelectorCard(vm: MainViewModel) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Column {
            vm.homeServerRows.forEachIndexed { index, row ->
                if (index > 0) Divider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = row.server.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = row.version,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.isCurrent) {
                        Text(
                            text = vm.app.getString(R.string.server_current),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .padding(end = 10.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    androidx.compose.foundation.shape.RoundedCornerShape(50),
                                )
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    } else {
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        text = vm.app.getString(
                            if (row.installed) R.string.server_installed else R.string.server_not_installed
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor(if (row.installed) R.color.wuwa_success else R.color.wuwa_error),
                    )
                    Spacer(Modifier.width(4.dp))
                    androidx.compose.material3.IconButton(onClick = { vm.onHomeServerClicked(row.server) }) {
                        androidx.compose.material3.Icon(
                            Icons.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Divider()
            Text(
                text = vm.targetState,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun HomeSoftwareStatusCard(vm: MainViewModel) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row {
            Column(
                modifier = Modifier.weight(1f).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = vm.app.getString(R.string.software_version_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = vm.softwareVersionStatus,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterVertically)
                    .width(1.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Column(
                modifier = Modifier.weight(1f).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = vm.app.getString(R.string.backup_status_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = vm.backupStatus,
                    style = MaterialTheme.typography.titleSmall,
                    color = statusColor(vm.backupStatusColor),
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterVertically)
                    .width(1.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Column(
                modifier = Modifier.weight(1f).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = vm.app.getString(R.string.direction_control_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = vm.homeRotationStatus,
                    style = MaterialTheme.typography.titleSmall,
                    color = statusColor(if (vm.homeRotationActive) R.color.wuwa_success else R.color.wuwa_on_surface_variant),
                )
            }
        }
    }
}
