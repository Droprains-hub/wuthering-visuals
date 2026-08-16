package com.wuwa.config.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.wuwa.config.manager.R
import com.wuwa.config.manager.ui.MainTab
import com.wuwa.config.manager.ui.MainViewModel
import com.wuwa.config.manager.ui.dialogs.ChoicePanelView
import com.wuwa.config.manager.ui.dialogs.LogManagerDialog
import com.wuwa.config.manager.ui.dialogs.ManualManagerDialog
import com.wuwa.config.manager.ui.dialogs.PanelDialogView
import com.wuwa.config.manager.ui.dialogs.ProgressDialogView
import com.wuwa.config.manager.ui.dialogs.RemoteNoticeDialog
import com.wuwa.config.manager.ui.dialogs.RemoteUpdateDialog
import com.wuwa.config.manager.ui.dialogs.RestoreCenterDialog
import com.wuwa.config.manager.ui.dialogs.SecurityModeOverlay
import com.wuwa.config.manager.ui.dialogs.ServerChooserDialog
import com.wuwa.config.manager.ui.dialogs.TextInputDialogView
import com.wuwa.config.manager.ui.screens.HomeScreen
import com.wuwa.config.manager.ui.screens.ConfigScreen
import com.wuwa.config.manager.ui.screens.BackupScreen
import com.wuwa.config.manager.ui.screens.SettingsScreen
import com.wuwa.config.manager.ui.screens.LicensesScreen
import com.wuwa.config.manager.ui.overlays.FirstRunOverlay
import com.wuwa.config.manager.ui.overlays.RotationRecoveryOverlay
import androidx.activity.compose.BackHandler

@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenBackupFolder: () -> Unit,
    onExitApp: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(vm.toastMessage) {
        val toast = vm.toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, toast.first, toast.second).show()
        vm.consumeToast()
    }

    // Full-screen overlays take absolute priority.
    if (vm.showFirstRun) {
        BackHandler { onExitApp() }
        FirstRunOverlay(vm, onExit = onExitApp)
        return
    }
    if (vm.securityMode != null) {
        BackHandler { onExitApp() }
        SecurityModeOverlay(vm, onExit = onExitApp)
        return
    }
    if (vm.showRotationRecovery) {
        BackHandler { onExitApp() }
        RotationRecoveryOverlay(vm)
        return
    }

    if (vm.tab == MainTab.LICENSES) {
        BackHandler { vm.closeLicensesPage() }
    }

    val tabs = listOf(
        MainTab.HOME to (Icons.Rounded.Home to vm.app.getString(R.string.navigation_home)),
        MainTab.CONFIG to (Icons.Rounded.Tune to vm.app.getString(R.string.navigation_config)),
        MainTab.BACKUP to (Icons.Rounded.Backup to vm.app.getString(R.string.navigation_backup)),
        MainTab.SETTINGS to (Icons.Rounded.Settings to vm.app.getString(R.string.navigation_settings)),
    )

    val pageTitle = when (vm.tab) {
        MainTab.HOME -> vm.app.getString(R.string.dashboard_title)
        MainTab.CONFIG -> vm.app.getString(R.string.config_page_title)
        MainTab.BACKUP -> vm.app.getString(R.string.backup_page_title)
        MainTab.SETTINGS -> vm.app.getString(R.string.settings_page_title)
        MainTab.LICENSES -> ""
    }

    Scaffold(
        topBar = {
            if (vm.tab != MainTab.LICENSES) {
                TopAppBar(
                    title = { Text(pageTitle, modifier = Modifier.padding(start = 12.dp)) },
                )
            }
        },
        bottomBar = {
            if (vm.tab != MainTab.LICENSES) {
                NavigationBar {
                    tabs.forEach { (tab, pair) ->
                        NavigationBarItem(
                            selected = vm.tab == tab,
                            onClick = { vm.selectTab(tab) },
                            icon = {
                                Icon(
                                    pair.first as ImageVector,
                                    contentDescription = pair.second,
                                )
                            },
                            label = { Text(pair.second) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (vm.tab) {
                MainTab.HOME -> HomeScreen(
                    vm = vm,
                    onOpenSettings = { vm.selectTab(MainTab.SETTINGS) },
                )
                MainTab.CONFIG -> ConfigScreen(vm)
                MainTab.BACKUP -> BackupScreen(vm, onOpenStorage = onOpenBackupFolder)
                MainTab.SETTINGS -> SettingsScreen(vm)
                MainTab.LICENSES -> LicensesScreen(vm)
            }
        }
    }

    // Dialogs render above the scaffold.
    if (vm.dialogProgress != null) ProgressDialogView(vm)
    if (vm.dialogPanel != null) PanelDialogView(vm)
    if (vm.dialogChoice != null) ChoicePanelView(vm)
    if (vm.dialogTextInput != null) TextInputDialogView(vm)
    if (vm.dialogServerChooser) ServerChooserDialog(vm)
    if (vm.dialogRestoreCenter != null) RestoreCenterDialog(vm)
    if (vm.dialogManualManager != null) ManualManagerDialog(vm)
    if (vm.dialogLogManager != null) LogManagerDialog(vm)
    if (vm.dialogRemoteNotice != null) RemoteNoticeDialog(vm)
    if (vm.dialogRemoteUpdate != null) RemoteUpdateDialog(vm)
}
