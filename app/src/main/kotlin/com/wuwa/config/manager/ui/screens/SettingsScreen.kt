package com.wuwa.config.manager.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wuwa.config.manager.R
import com.wuwa.config.manager.BuildConfig
import com.wuwa.config.manager.ui.MainTab
import com.wuwa.config.manager.ui.MainViewModel
import com.wuwa.config.manager.ui.components.ActionRow
import com.wuwa.config.manager.ui.components.AppCard
import com.wuwa.config.manager.ui.components.Divider
import com.wuwa.config.manager.ui.components.IconBlock
import com.wuwa.config.manager.ui.components.SectionTitle
import com.wuwa.config.manager.ui.components.statusColor
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(vm: MainViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        SectionTitle(vm.app.getString(R.string.appearance_title))
        AppearanceCard(vm)
        SectionTitle(vm.app.getString(R.string.permission_settings_title))
        PermissionCard(vm)
        SectionTitle(vm.app.getString(R.string.announcement_update_title))
        AnnouncementCard(vm)
        SectionTitle(vm.app.getString(R.string.log_diagnostics_title))
        LogCard(vm)
        SectionTitle(vm.app.getString(R.string.about_support_title))
        AboutCard(vm)
    }
}

@Composable
private fun AppearanceCard(vm: MainViewModel) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Column {
            // Language
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = vm.app.getString(R.string.language_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf("zh-CN" to vm.app.getString(R.string.language_chinese),
                        "en" to vm.app.getString(R.string.language_english))
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = vm.language == value,
                            onClick = { vm.updateLanguage(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }
            Divider()
            // Theme mode
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = vm.app.getString(R.string.appearance_mode_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val options = listOf(
                        "system" to vm.app.getString(R.string.appearance_system),
                        "light" to vm.app.getString(R.string.appearance_light),
                        "dark" to vm.app.getString(R.string.appearance_dark),
                    )
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = vm.appearanceMode == value,
                            onClick = { vm.updateAppearanceMode(value) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }
            Divider()
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBlock(
                    icon = Icons.Rounded.Star,
                    container = MaterialTheme.colorScheme.surfaceContainer,
                    size = 40,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = vm.app.getString(R.string.material_you_dynamic_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = vm.dynamicColorEnabled,
                    onCheckedChange = { vm.updateDynamicColorEnabled(it) },
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(vm: MainViewModel) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    when (vm.permissionModeIcon) {
                        R.drawable.ic_check -> Icons.Rounded.CheckCircle
                        R.drawable.ic_warning -> Icons.Rounded.Warning
                        else -> Icons.Rounded.Shield
                    },
                    contentDescription = null,
                    tint = statusColor(vm.permissionModeColor),
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = vm.bottomPermissionStatus,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = statusColor(vm.bottomPermissionColor),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = vm.permissionDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(
                    onClick = { vm.requestAuthorization() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(vm.app.getString(R.string.check_permission))
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = { vm.reconnectShizuku() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(vm.app.getString(R.string.reconnect_shizuku))
                }
            }
        }
    }
}

@Composable
private fun AnnouncementCard(vm: MainViewModel) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        ActionRow(
            icon = Icons.Rounded.Campaign,
            title = vm.app.getString(R.string.announcement_center),
            subtitle = vm.announcementStatus,
            onClick = { vm.openAnnouncementCenter() },
        )
    }
}

@Composable
private fun LogCard(vm: MainViewModel) {
    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        ActionRow(
            icon = Icons.Rounded.Description,
            title = vm.app.getString(R.string.log_manager_title),
            subtitle = vm.logSummary,
            onClick = { vm.openLogManager() },
        )
    }
}

@Composable
private fun AboutCard(vm: MainViewModel) {
    var versionTapCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(versionTapCount) {
        if (versionTapCount > 0 && versionTapCount < 5) {
            delay(2000)
            versionTapCount = 0
        }
    }
    AppCard(modifier = Modifier.padding(horizontal = 20.dp)) {
        Column {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .clickable {
                        versionTapCount++
                        if (versionTapCount >= 5) {
                            versionTapCount = 0
                            vm.openExternalLink(
                                "https://www.bilibili.com/video/BV1GJ411x7h7", false
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBlock(
                    icon = Icons.Rounded.Info,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    size = 48,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = vm.settingsAppIdentity,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = vm.app.getString(R.string.software_description_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Divider()
            ActionRow(
                icon = Icons.Rounded.Info,
                title = vm.app.getString(R.string.software_description_title),
                onClick = {
                    vm.showPanelDialog(
                        vm.app.getString(R.string.software_description_title),
                        vm.app.getString(R.string.software_description_body),
                        com.wuwa.config.manager.ui.DialogTone.INFO,
                        null,
                        vm.app.getString(R.string.done),
                    )
                },
            )
            ActionRow(
                icon = Icons.Rounded.Star,
                title = vm.app.getString(R.string.author_bilibili_title),
                onClick = { vm.openConfiguredLink(vm.app.getString(R.string.support_bilibili_url)) },
            )
            ActionRow(
                icon = Icons.Rounded.Campaign,
                title = vm.app.getString(R.string.qq_group_title),
                onClick = { vm.openConfiguredLink(vm.app.getString(R.string.support_qq_group_url)) },
            )
            ActionRow(
                icon = Icons.Rounded.Code,
                title = vm.app.getString(R.string.view_source_code),
                onClick = { vm.openConfiguredLink(vm.app.getString(R.string.source_repository_url)) },
            )
            Divider()
            ActionRow(
                icon = Icons.Rounded.Description,
                title = vm.app.getString(R.string.open_source_licenses_title),
                onClick = { vm.selectTab(MainTab.LICENSES) },
            )
            Divider()
            ActionRow(
                icon = Icons.Rounded.Sync,
                title = vm.app.getString(
                    R.string.current_version_click_check, BuildConfig.VERSION_NAME
                ),
                subtitle = vm.updateStatus,
                onClick = { vm.checkRemoteUpdates(true) },
            )
        }
    }
}
