package com.wuwa.config.manager

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import com.wuwa.config.manager.diagnostics.AppLogger
import com.wuwa.config.manager.model.GameServer
import com.wuwa.config.manager.ui.MainScreen
import com.wuwa.config.manager.ui.MainViewModel
import com.wuwa.config.manager.ui.UiPreferences
import com.wuwa.config.manager.ui.theme.WuWaTheme

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var storageFolderSelectionChangesLocation = false

    private val storageFolderPicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null && storageFolderSelectionChangesLocation) {
                handleSelectedStorageFolder(uri)
            }
            storageFolderSelectionChangesLocation = false
        }

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val server = viewModel.pendingOverlayServer
            viewModel.clearPendingOverlayServer()
            if (server == null) return@registerForActivityResult
            if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
                viewModel.startFloatingRotationController(server)
            } else {
                viewModel.showError(getString(R.string.overlay_permission_denied))
            }
        }

    private val legacyLogPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                AppLogger.tryEnablePublicLogging()
                AppLogger.event("Permission", "已授予旧版系统日志存储权限")
            } else {
                AppLogger.warn("Permission", "用户拒绝旧版系统日志存储权限")
                Toast.makeText(this, R.string.log_permission_denied, Toast.LENGTH_LONG).show()
            }
            viewModel.openLogManager()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        UiPreferences.applyLanguage(this)
        UiPreferences.applyAppearance(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val darkTheme = when (viewModel.appearanceMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            WuWaTheme(
                darkTheme = darkTheme,
                dynamicColor = viewModel.dynamicColorEnabled,
            ) {
                MainScreen(
                    vm = viewModel,
                    onOpenBackupFolder = { openBackupFolder() },
                    onExitApp = { finishAndRemoveTask() },
                )
            }
            LaunchedEffect(viewModel.restartRequested) {
                if (viewModel.restartRequested) {
                    viewModel.consumeRestart()
                    recreate()
                }
            }
            LaunchedEffect(viewModel.overlayPermissionRequested) {
                if (viewModel.overlayPermissionRequested) {
                    viewModel.consumeOverlayPermissionRequest()
                    overlayPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                    )
                }
            }
        }
        viewModel.bindDeviceInformation()
        AppLogger.info("Activity", "MainActivity 已创建")
    }

    override fun onResume() {
        super.onResume()
        viewModel.onActivityResumed()
    }

    override fun onPause() {
        viewModel.onActivityPaused()
        super.onPause()
    }

    override fun onDestroy() {
        viewModel.onDestroyWithRotation()
        super.onDestroy()
    }

    // ===================== Storage folder =====================

    private fun openBackupFolder() {
        val root = viewModel.repository.getStorageRoot()
        val documentUri = storageRootToDocumentUri(root)
        if (documentUri != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(documentUri, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                startActivity(view)
                return
            } catch (_: Exception) {
            }
        }
        launchStorageFolderPicker(documentUri, false)
    }

    private fun launchStorageFolderPicker(initialUri: Uri?, changeLocation: Boolean) {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        if (Build.VERSION.SDK_INT >= 26 && initialUri != null) {
            picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        try {
            storageFolderSelectionChangesLocation = changeLocation
            storageFolderPicker.launch(picker)
        } catch (error: Exception) {
            storageFolderSelectionChangesLocation = false
            Toast.makeText(this, R.string.folder_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSelectedStorageFolder(treeUri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
        }
        val documentId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (error: Exception) {
            viewModel.showError(getString(R.string.storage_folder_unsupported))
            return
        }
        if (!documentId.startsWith("primary:") || documentId.length <= "primary:".length) {
            viewModel.showError(getString(R.string.storage_folder_unsupported))
            return
        }
        val relativePath = documentId.substring("primary:".length)
        if (relativePath.contains("..") || relativePath.contains("\n") || relativePath.contains("\r")) {
            viewModel.showError(getString(R.string.storage_folder_unsupported))
            return
        }
        viewModel.changeStorageAsync("/storage/emulated/0/" + relativePath)
    }

    private fun storageRootToDocumentUri(root: String): Uri? {
        val prefix = "/storage/emulated/0/"
        if (!root.startsWith(prefix)) return null
        val documentId = "primary:" + root.substring(prefix.length)
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents", documentId
        )
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    }
}
