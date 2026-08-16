package com.wuwa.config.manager.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import android.text.TextUtils
import com.wuwa.config.manager.R
import com.wuwa.config.manager.BuildConfig
import com.wuwa.config.manager.ui.MainViewModel
import com.wuwa.config.manager.ui.RemoteUpdateData
import com.wuwa.config.manager.ui.SecurityModeData
import com.wuwa.config.manager.ui.components.statusColor

@Composable
fun RemoteNoticeDialog(vm: MainViewModel) {
    val announcement = vm.dialogRemoteNotice ?: return
    val hasAction = vm.hasAnnouncementAction(announcement)
    val metadata = if (TextUtils.isEmpty(announcement.publishedAt)) {
        vm.app.getString(R.string.remote_announcement_label)
    } else {
        vm.app.getString(
            R.string.published_format, vm.formatNoticeTimestamp(announcement.publishedAt)
        )
    }
    val icon = when (announcement.type.lowercase()) {
        "important", "maintenance" -> Icons.Rounded.Warning
        "version_update" -> Icons.Rounded.Update
        else -> Icons.Rounded.Campaign
    }
    AlertDialog(
        onDismissRequest = { vm.dismissRemoteNotice() },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
        },
        title = {
            Column {
                Text(
                    text = announcement.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                if (announcement.type.isNotBlank()) {
                    Text(
                        text = vm.announcementTypeLabel(announcement.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    text = styledAnnouncementBody(vm, announcement),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(min = 60.dp, max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            if (hasAction) {
                TextButton(onClick = { vm.openAnnouncementAction(announcement) }) {
                    Text(announcement.actionLabel)
                }
            } else {
                TextButton(onClick = { vm.dismissRemoteNotice() }) {
                    Text(vm.app.getString(R.string.announcement_got_it))
                }
            }
        },
        dismissButton = {
            if (hasAction) {
                TextButton(onClick = { vm.dismissRemoteNotice() }) {
                    Text(vm.app.getString(R.string.close))
                }
            }
        },
    )
}

@Composable
private fun styledAnnouncementBody(
    vm: MainViewModel,
    announcement: com.wuwa.config.manager.data.RemoteNoticePayload.Announcement,
): androidx.compose.ui.text.AnnotatedString {
    val content = announcement.content ?: ""
    val highlight = announcement.highlightText
    if (TextUtils.isEmpty(highlight) || !content.contains(highlight)) {
        return androidx.compose.ui.text.AnnotatedString(content)
    }
    val color = statusColor(vm.announcementHighlightColorRes(announcement))
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val index = content.indexOf(highlight, start)
            if (index < 0) break
            append(content.substring(start, index))
            withStyle(SpanStyle(color = color)) { append(highlight) }
            start = index + highlight.length
        }
        append(content.substring(start))
    }
}

@Composable
fun RemoteUpdateDialog(vm: MainViewModel) {
    val data = vm.dialogRemoteUpdate ?: return
    val update = data.update
    val mandatory = vm.isMandatoryUpdate(update)
    val currentCode = BuildConfig.VERSION_CODE
    val version = vm.displayRemoteVersion(update)
    val title = if (TextUtils.isEmpty(update.title)) {
        vm.app.getString(R.string.update_available_format, version)
    } else {
        update.title
    }
    val content = if (TextUtils.isEmpty(update.content)) {
        vm.app.getString(R.string.update_available_format, version)
    } else {
        update.content
    }
    AlertDialog(
        onDismissRequest = { if (!mandatory) vm.dismissRemoteUpdate() },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(26.dp),
                )
            }
        },
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = vm.app.getString(if (mandatory) R.string.required_update else R.string.optional_update),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .background(
                            if (mandatory) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        },
        text = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = vm.app.getString(R.string.current_version_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = vm.app.getString(R.string.version_with_code, BuildConfig.VERSION_NAME, currentCode),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Icon(
                        Icons.Rounded.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(
                            text = vm.app.getString(R.string.target_version_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = vm.app.getString(
                                R.string.version_with_code, version, update.latestVersionCode
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(min = 60.dp, max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(8.dp))
                if (!update.publishedAt.isNullOrBlank()) {
                    MetaLine(vm.app.getString(R.string.update_published_detail, vm.formatNoticeTimestamp(update.publishedAt)))
                }
                if (update.apkSizeBytes > 0) {
                    MetaLine(vm.app.getString(R.string.update_size_detail, vm.formatFileSize(update.apkSizeBytes)))
                }
                if (!update.downloadChannel.isNullOrBlank()) {
                    MetaLine(vm.app.getString(R.string.update_channel_detail, update.downloadChannel))
                }
                if (!update.sha256.isNullOrBlank()) {
                    MetaLine(vm.app.getString(R.string.update_sha256_detail, vm.abbreviateHash(update.sha256)))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                vm.openUpdateUrl(update)
                if (!mandatory) vm.dismissRemoteUpdate()
            }) {
                Text(vm.app.getString(R.string.update_now), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Column {
                if (mandatory) {
                    TextButton(onClick = { vm.refreshRemoteUpdateCheck() }) {
                        Text(vm.app.getString(R.string.refresh_update_status))
                    }
                } else {
                    TextButton(onClick = { vm.ignoreUpdate(update) }) {
                        Text(vm.app.getString(R.string.ignore_this_update))
                    }
                    TextButton(onClick = { vm.dismissRemoteUpdate() }) {
                        Text(vm.app.getString(R.string.later))
                    }
                }
            }
        },
    )
}

@Composable
private fun MetaLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

// ===================== Security mode overlay =====================

@Composable
fun SecurityModeOverlay(vm: MainViewModel, onExit: () -> Unit) {
    val data = vm.securityMode ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = vm.app.getString(R.string.security_mode_badge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = data.mode.title ?: vm.app.getString(R.string.security_mode_default_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = vm.app.getString(
                    R.string.security_mode_cloud_meta,
                    vm.securityModeLevelLabel(data.mode.level),
                    if (data.mode.expiresAt.isNullOrBlank()) {
                        "-"
                    } else {
                        vm.formatNoticeTimestamp(data.mode.expiresAt)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = data.mode.content ?: vm.app.getString(R.string.security_mode_default_content),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (data.allowRecheck) {
                    Button(
                        onClick = { vm.checkRemoteUpdates(true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(vm.app.getString(R.string.security_mode_recheck))
                    }
                }
                Button(
                    onClick = { vm.openSecurityModeDownload(data.mode) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(vm.app.getString(R.string.security_mode_download))
                }
                if (data.allowLogExport) {
                    OutlinedButton(
                        onClick = { vm.openLogManager() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text(vm.app.getString(R.string.security_mode_export_log))
                    }
                }
                if (data.allowRotationRecovery) {
                    OutlinedButton(
                        onClick = { vm.requestEmergencyRotationRecovery() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Icon(
                            Icons.Rounded.ScreenRotation,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(vm.app.getString(R.string.security_mode_restore_rotation))
                    }
                }
                OutlinedButton(
                    onClick = onExit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(vm.app.getString(R.string.exit_app))
                }
            }
        }
    }
}
