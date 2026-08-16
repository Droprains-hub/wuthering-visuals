package com.wuwa.config.manager.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.wuwa.config.manager.BuildConfig
import com.wuwa.config.manager.R
import com.wuwa.config.manager.diagnostics.AppLogger
import com.wuwa.config.manager.data.ConfigRepository
import com.wuwa.config.manager.data.ManualBackupInfo
import com.wuwa.config.manager.data.OperationException
import com.wuwa.config.manager.data.RemoteNoticeEvaluator
import com.wuwa.config.manager.data.RemoteNoticePayload
import com.wuwa.config.manager.data.RemoteNoticeRepository
import com.wuwa.config.manager.diagnostics.LogEntry
import com.wuwa.config.manager.model.GameServer
import com.wuwa.config.manager.model.QualityPreset
import com.wuwa.config.manager.privilege.PrivilegeManager
import com.wuwa.config.manager.privilege.PrivilegeState
import com.wuwa.config.manager.RotationControllerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ThreadLocalRandom
import java.util.regex.Pattern
import kotlin.math.roundToInt

enum class MainTab { HOME, CONFIG, BACKUP, SETTINGS, LICENSES }

enum class DialogTone { INFO, SUCCESS, WARNING, ERROR }

data class PanelDialog(
    val title: String,
    val message: String,
    val tone: DialogTone,
    val negativeText: String? = null,
    val positiveText: String = "",
    val destructive: Boolean = false,
    val negativeAction: (() -> Unit)? = null,
    val positiveAction: (() -> Unit)? = null,
)

data class ChoiceItem(val label: String, val detail: String? = null)

data class ChoicePanel(
    val title: String,
    val description: String? = null,
    val items: List<ChoiceItem>,
    val onChoice: (Int) -> Unit,
)

data class TextInputRequest(
    val title: String,
    val message: String? = null,
    val hint: String,
    val initial: String,
    val maxLength: Int,
    val onSave: (String) -> Unit,
)

data class RestoreCenterData(
    val server: GameServer,
    val hasInitial: Boolean,
    val snapshots: List<ManualBackupInfo>,
)

data class ManualManagerData(
    val server: GameServer,
    val hasOriginal: Boolean,
    val snapshots: List<ManualBackupInfo>,
)

data class LogManagerData(val logs: List<LogEntry>)

data class RemoteUpdateData(val update: RemoteNoticePayload.Update)

data class SecurityModeData(
    val mode: RemoteNoticePayload.SecurityMode,
    val allowRecheck: Boolean,
    val allowLogExport: Boolean,
    val allowRotationRecovery: Boolean,
)

data class HomeServerUi(
    val server: GameServer,
    val installed: Boolean,
    val version: String,
    val isCurrent: Boolean,
)

data class LicenseInfo(
    val name: String,
    val version: String,
    val author: String,
    val license: String,
    val url: String,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val PREFS = "wuwa_config_preferences"
        const val KEY_SELECTED_PACKAGE = "selected_package"
        const val KEY_SELECTED_PRESET = "selected_preset"
        const val KEY_APPEARANCE = "appearance_mode"
        const val KEY_DYNAMIC_COLORS = "dynamic_colors_enabled"
        const val KEY_FIRST_RUN_NOTICE_ACCEPTED = "first_run_notice_accepted_v3"
        const val KEY_LANGUAGE = "language"
        const val KEY_LAST_ANNOUNCEMENT_ID = "last_announcement_id"
        const val KEY_LAST_DAILY_ANNOUNCEMENT_ID = "last_daily_announcement_id"
        const val KEY_LAST_DAILY_ANNOUNCEMENT_DATE = "last_daily_announcement_date"
        const val KEY_IGNORED_UPDATE_VERSION_CODE = "ignored_update_version_code"

        private const val REMOTE_LOCK_CACHE_MAX_AGE_MS = 6L * 60L * 60L * 1000L
        private const val REMOTE_MAX_RETRIES = 1
        private const val REMOTE_RETRY_DELAY_MS = 3_000L
    }

    val app: Application get() = getApplication()
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    lateinit var privilegeManager: PrivilegeManager
    lateinit var repository: ConfigRepository
    var remoteNoticeRepository: RemoteNoticeRepository? = null

    // ---------- Navigation / selection ----------
    var tab by mutableStateOf(MainTab.HOME)
        private set
    var selectedServer by mutableStateOf<GameServer?>(null)
        private set
    var installedServers by mutableStateOf<List<GameServer>>(emptyList())
        private set
    var selectedPreset by mutableStateOf(QualityPreset.MEDIUM)
        private set
    var appearanceMode by mutableStateOf("light")
        private set
    var dynamicColorEnabled by mutableStateOf(false)
        private set
    var language by mutableStateOf("zh-CN")
        private set
    var restartRequested by mutableStateOf(false)
        private set

    // ---------- Privilege / status ----------
    var privilegeState by mutableStateOf<PrivilegeState>(PrivilegeState(true, false, PrivilegeState.ShizukuStatus.NOT_RUNNING, ""))
        private set
    var rootProviderName by mutableStateOf("")
        private set
    var presetsChecked by mutableStateOf(false)
        private set
    var presetsReady by mutableStateOf(false)
        private set
    var operationRunning by mutableStateOf(false)
        private set

    // ---------- Home ----------
    var deviceModel by mutableStateOf("")
        private set
    var deviceProcessor by mutableStateOf("")
        private set
    var deviceGpu by mutableStateOf("")
        private set
    var deviceMemory by mutableStateOf("")
        private set
    var deviceSystem by mutableStateOf("")
        private set
    var deviceSystemDetail by mutableStateOf("")
        private set
    var cpuPercent by mutableIntStateOf(0)
        private set
    var gpuPercent by mutableIntStateOf(0)
        private set
    var memoryPercent by mutableIntStateOf(0)
        private set
    var loadAvailable by mutableStateOf(false)
        private set
    var targetName by mutableStateOf("")
        private set
    var targetState by mutableStateOf("")
        private set
    var gameVersionStatus by mutableStateOf("")
        private set
    var gameProcessStatus by mutableStateOf("")
        private set
    var softwareVersionStatus by mutableStateOf("")
        private set
    var homeRotationStatus by mutableStateOf("")
        private set
    var homeRotationActive by mutableStateOf(false)
        private set
    var homeServerRows by mutableStateOf<List<HomeServerUi>>(emptyList())
        private set
    var gpuRendererReady by mutableStateOf(false)
        private set

    // ---------- Config ----------
    var presetStatus by mutableStateOf("")
        private set
    var presetStatusColor by mutableStateOf(0)
        private set
    var presetSelectionDetail by mutableStateOf("")
        private set
    var presetSelectionDetailColor by mutableStateOf(0)
        private set
    var configTargetName by mutableStateOf("")
        private set
    var configTargetState by mutableStateOf("")
        private set
    var rotationStatus by mutableStateOf("")
        private set
    var presetCardTitle by mutableStateOf("")
        private set

    // ---------- Backup ----------
    var backupTargetName by mutableStateOf("")
        private set
    var backupTargetState by mutableStateOf("")
        private set
    var backupStatus by mutableStateOf("")
        private set
    var backupStatusColor by mutableStateOf(0)
        private set
    var backupLibrarySummary by mutableStateOf("")
        private set
    var storageRoot by mutableStateOf(ConfigRepository.DEFAULT_STORAGE_ROOT)
        private set
    var retentionLimit by mutableIntStateOf(10)
        private set

    // ---------- Settings ----------
    var permissionModeStatus by mutableStateOf("")
        private set
    var permissionModeColor by mutableStateOf(0)
        private set
    var permissionModeIcon by mutableStateOf(0)
        private set
    var permissionWarningVisible by mutableStateOf(false)
        private set
    var permissionWarningText by mutableStateOf("")
        private set
    var permissionDetail by mutableStateOf("")
        private set
    var bottomPermissionStatus by mutableStateOf("")
        private set
    var bottomPermissionColor by mutableStateOf(0)
        private set
    var logSummary by mutableStateOf("")
        private set
    var announcementStatus by mutableStateOf("")
        private set
    var updateStatus by mutableStateOf("")
        private set
    var settingsAppIdentity by mutableStateOf("")
        private set

    // ---------- Overlays / dialogs ----------
    var showFirstRun by mutableStateOf(false)
        private set
    var showRotationRecovery by mutableStateOf(false)
        private set
    var rotationRecoveryStatus by mutableStateOf("")
        private set
    var rotationRecoverySnapshot by mutableStateOf("")
        private set
    var rotationRecoveryRetryEnabled by mutableStateOf(true)
        private set
    var securityMode by mutableStateOf<SecurityModeData?>(null)
        private set

    var dialogServerChooser by mutableStateOf(false)
        private set
    var dialogPanel by mutableStateOf<PanelDialog?>(null)
        private set
    var dialogChoice by mutableStateOf<ChoicePanel?>(null)
        private set
    var dialogProgress by mutableStateOf<String?>(null)
        private set
    var dialogTextInput by mutableStateOf<TextInputRequest?>(null)
        private set
    var dialogRestoreCenter by mutableStateOf<RestoreCenterData?>(null)
        private set
    var dialogManualManager by mutableStateOf<ManualManagerData?>(null)
        private set
    var dialogLogManager by mutableStateOf<LogManagerData?>(null)
        private set
    var dialogRemoteNotice by mutableStateOf<RemoteNoticePayload.Announcement?>(null)
        private set
    var dialogRemoteUpdate by mutableStateOf<RemoteUpdateData?>(null)
        private set

    var toastMessage by mutableStateOf<Pair<String, Int>?>(null)
        private set

    var pendingOverlayServer by mutableStateOf<GameServer?>(null)
        private set
    var overlayPermissionRequested by mutableStateOf(false)
        private set

    private var activityResumed = false
    private var destroyed = false

    // Load monitor
    private var loadJob: Job? = null
    private var loadGeneration = 0
    private var previousCpuTotal = -1L
    private var previousCpuIdle = -1L
    private var previousGpuActive = -1L
    private var previousGpuTotal = -1L

    private var processQueryGeneration = 0
    private var backupQueryGeneration = 0
    private var rootProviderLookupRunning = false

    private var emergencyRotationRecoveryRequested = false
    private var firstRunRecoveryButtonEnabled = true
    private var updateCheckStarted = false

    private var remoteCheckRunning = false

    private val announcementsShownThisSession = HashSet<String>()

    private val licenses = listOf(
        LicenseInfo("AndroidX AppCompat", "1.7.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/appcompat"),
        LicenseInfo("AndroidX Activity", "1.13.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/activity"),
        LicenseInfo("AndroidX Annotation", "1.8.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/annotation"),
        LicenseInfo("AndroidX Arch Core", "2.2.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/arch-core"),
        LicenseInfo("AndroidX CardView", "1.0.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/cardview"),
        LicenseInfo("AndroidX Collection", "1.4.2", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/collection"),
        LicenseInfo("AndroidX Compose", "BOM 2026.06.01", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/compose"),
        LicenseInfo("AndroidX Core", "1.16.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/core"),
        LicenseInfo("AndroidX Fragment", "1.5.4", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/fragment"),
        LicenseInfo("AndroidX Lifecycle", "2.11.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
        LicenseInfo("AndroidX RecyclerView", "1.2.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/recyclerview"),
        LicenseInfo("AndroidX Startup", "1.1.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/startup"),
        LicenseInfo("Material Components for Android", "1.14.0", "Google", "Apache License 2.0", "https://github.com/material-components/material-components-android"),
        LicenseInfo("Material 3 (Compose)", "1.5.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/compose-material3"),
        LicenseInfo("Shizuku API & Provider", "13.1.5", "RikkaApps", "MIT License", "https://github.com/RikkaApps/Shizuku-API"),
        LicenseInfo("Kotlin", "2.4.10", "JetBrains", "Apache License 2.0", "https://github.com/JetBrains/kotlin"),
        LicenseInfo("kotlinx.coroutines", "1.11.0", "JetBrains", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        LicenseInfo("Guava ListenableFuture", "1.0", "Google", "Apache License 2.0", "https://github.com/google/guava"),
        LicenseInfo("Error Prone Annotations", "2.15.0", "Google", "Apache License 2.0", "https://github.com/google/error-prone"),
        LicenseInfo("JSpecify", "1.0.0", "JSpecify", "Apache License 2.0", "https://github.com/jspecify/jspecify"),
        LicenseInfo("JetBrains Annotations", "13.0", "JetBrains", "Apache License 2.0", "https://github.com/JetBrains/java-annotations"),
        LicenseInfo("desugar_jdk_libs", "2.1.5", "Google", "GPL-2.0 with Classpath Exception", "https://github.com/google/desugar_jdk_libs"),
    )
    fun licenses() = licenses

    init {
        applyLanguageBeforeCreate()
        migrateAppearancePreferences()
        applyAppearanceBeforeCreate()

        remoteNoticeRepository = RemoteNoticeRepository(
            app,
            app.getString(R.string.remote_notice_endpoint),
            app.getString(R.string.remote_notice_fallback_endpoint),
        )

        privilegeManager = PrivilegeManager(app)
        repository = ConfigRepository(app, privilegeManager)

        language = prefs.getString(KEY_LANGUAGE, "zh-CN") ?: "zh-CN"
        appearanceMode = readAppearanceModeFromPrefs()
        dynamicColorEnabled = isDynamicColorEnabled()

        installedServers = detectInstalledServers()
        chooseInitialServer()

        privilegeManager.setListener { state -> onPrivilegeStateChanged(state) }

        preparePresetsAsync()
        refreshUi()
        showRotationRecoveryPageIfNeeded()

        viewModelScope.launch {
            delay(400)
            if (!destroyed) showFirstRunNoticeIfNeeded()
        }
        viewModelScope.launch {
            delay(800)
            if (!destroyed) checkRemoteUpdates(false)
        }
    }

    // ===================== Appearance / language =====================

    fun applyLanguageBeforeCreate() {
        val languageValue = prefs.getString(KEY_LANGUAGE, "zh-CN") ?: "zh-CN"
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(if ("en" == languageValue) "en" else "zh-CN")
        )
    }

    fun applyAppearanceBeforeCreate() {
        when (readAppearanceModeFromPrefs()) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun migrateAppearancePreferences() {
        val appearance = prefs.getString(KEY_APPEARANCE, null) ?: return
        val editor = prefs.edit()
        when (appearance) {
            "dynamic" -> {
                editor.putString(KEY_APPEARANCE, "system")
                editor.putBoolean(KEY_DYNAMIC_COLORS, true)
            }
            "default" -> editor.putString(KEY_APPEARANCE, "light")
        }
        editor.apply()
    }

    private fun readAppearanceModeFromPrefs(): String {
        val mode = prefs.getString(KEY_APPEARANCE, "light") ?: "light"
        return when (mode) {
            "dark", "system", "light" -> mode
            else -> "light"
        }
    }

    private fun isDynamicColorEnabled(): Boolean =
        prefs.getBoolean(KEY_DYNAMIC_COLORS, false)

    fun updateAppearanceMode(mode: String) {
        if (mode == appearanceMode) return
        appearanceMode = mode
        prefs.edit().putString(KEY_APPEARANCE, mode).apply()
        AppLogger.event("Appearance", "主题模式：" + mode)
        applyAppearanceBeforeCreate()
        restartRequested = true
    }

    fun updateDynamicColorEnabled(enabled: Boolean) {
        dynamicColorEnabled = enabled
        prefs.edit().putBoolean(KEY_DYNAMIC_COLORS, enabled).apply()
        AppLogger.event("Appearance", "动态取色：" + enabled)
        restartRequested = true
    }

    fun updateLanguage(languageValue: String) {
        if (languageValue == language) return
        language = languageValue
        prefs.edit().putString(KEY_LANGUAGE, languageValue).apply()
        AppLogger.event("Language", "界面语言：" + languageValue)
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(if ("en" == languageValue) "en" else "zh-CN")
        )
        restartRequested = true
    }

    // ===================== Lifecycle =====================

    fun onActivityResumed() {
        activityResumed = true
        startLoadMonitor()
        refreshLogSummary()
        if (!prefs.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) return
        checkRemoteUpdates(false)
        if (operationRunning) return

        installedServers = detectInstalledServers()
        if (selectedServer != null && !installedServers.contains(selectedServer)) {
            val first = installedServers.firstOrNull()
            if (first != null) selectServer(first) else selectedServer = null
        }
        refreshUi()
        privilegeManager.refresh()
        refreshGameRuntimeStatus()
        viewModelScope.launch {
            delay(900)
            if (!destroyed && !repository.isRotationSessionActive()) {
                rotationStatus = app.getString(R.string.rotation_status_auto)
            }
        }
    }

    fun onActivityPaused() {
        activityResumed = false
        stopLoadMonitor()
    }

    fun consumeRestart() {
        restartRequested = false
    }

    override fun onCleared() {
        destroyed = true
        loadJob?.cancel()
        privilegeManager.close()
        super.onCleared()
    }

    fun onDestroyWithRotation() {
        destroyed = true
        loadJob?.cancel()
        if (RotationControllerService.isRunning()) {
            privilegeManager.closePreservingUserService()
        } else {
            privilegeManager.close()
        }
    }

    // ===================== Navigation =====================

    fun selectTab(newTab: MainTab) {
        if (newTab == MainTab.LICENSES) {
            tab = MainTab.LICENSES
            stopLoadMonitor()
            return
        }
        tab = newTab
        when (newTab) {
            MainTab.HOME -> startLoadMonitor()
            MainTab.CONFIG -> stopLoadMonitor()
            MainTab.BACKUP -> stopLoadMonitor()
            MainTab.SETTINGS -> stopLoadMonitor()
            else -> Unit
        }
    }

    fun closeLicensesPage() {
        tab = MainTab.SETTINGS
        refreshUi()
    }

    // ===================== Servers =====================

    private fun chooseInitialServer() {
        if (installedServers.isEmpty()) {
            selectedServer = null
            return
        }
        val saved = GameServer.fromPackageName(prefs.getString(KEY_SELECTED_PACKAGE, null))
        if (saved != null && installedServers.contains(saved)) {
            selectedServer = saved
            return
        }
        val server = installedServers.first()
        selectedServer = server
        persistSelectedServer(server)
        if (installedServers.size > 1 &&
            prefs.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)
        ) {
            viewModelScope.launch {
                delay(600)
                dialogServerChooser = true
            }
        }
    }

    private fun detectInstalledServers(): List<GameServer> {
        val result = mutableListOf<GameServer>()
        val packageManager = app.packageManager
        for (server in GameServer.entries) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getPackageInfo(
                        server.packageName, PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(server.packageName, 0)
                }
                result.add(server)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return result
    }

    fun selectServer(server: GameServer) {
        selectedServer = server
        AppLogger.info("Server", "当前目标=" + server.packageName)
        persistSelectedServer(server)
        backupQueryGeneration++
        refreshUi()
        refreshBackupStatus()
        refreshGameRuntimeStatus()
    }

    private fun persistSelectedServer(server: GameServer) {
        prefs.edit().putString(KEY_SELECTED_PACKAGE, server.packageName).apply()
    }

    fun onHomeServerClicked(server: GameServer) {
        if (operationRunning) return
        if (!installedServers.contains(server)) {
            showToast(R.string.server_not_installed_message, serverDisplayName(server))
            return
        }
        selectServer(server)
    }

    fun openServerChooser() {
        if (operationRunning) return
        dialogServerChooser = true
    }

    fun dismissServerChooser() {
        dialogServerChooser = false
    }

    // ===================== Privilege =====================

    private fun onPrivilegeStateChanged(state: PrivilegeState) {
        privilegeState = state
        if (state.isRootAvailable()) {
            detectRootProviderAsync()
        } else {
            rootProviderName = ""
        }
        refreshUi()
        if (state.isReady()) {
            if (emergencyRotationRecoveryRequested) {
                emergencyRotationRecoveryRequested = false
                performEmergencyRotationRecovery()
            } else {
                recoverInterruptedRotationSession()
            }
            refreshBackupStatus()
            refreshGameRuntimeStatus()
        } else {
            val stable = !state.isCheckingRoot() &&
                state.shizukuStatus != PrivilegeState.ShizukuStatus.CONNECTING &&
                state.shizukuStatus != PrivilegeState.ShizukuStatus.WAITING_PERMISSION
            if (emergencyRotationRecoveryRequested && stable) {
                emergencyRotationRecoveryRequested = false
                firstRunRecoveryButtonEnabled = true
                showToast(R.string.first_run_restore_rotation_permission)
            }
        }
    }

    private fun detectRootProviderAsync() {
        if (rootProviderLookupRunning || rootProviderName.isNotEmpty()) return
        rootProviderLookupRunning = true
        viewModelScope.launch {
            val detected = withContext(Dispatchers.IO) {
                try {
                    privilegeManager.detectRootProvider()
                } catch (_: Exception) {
                    ""
                }
            }
            rootProviderLookupRunning = false
            if (destroyed || !privilegeState.isRootAvailable()) return@launch
            rootProviderName = if (TextUtils.isEmpty(detected)) "Root" else detected
            refreshUi()
        }
    }

    fun requestAuthorization() {
        privilegeManager.requestAuthorization()
    }

    fun reconnectShizuku() {
        privilegeManager.reconnectShizuku()
    }

    // ===================== Presets =====================

    private fun preparePresetsAsync() {
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                try {
                    repository.preparePresets()
                } catch (_: Exception) {
                    false
                }
            }
            if (destroyed) return@launch
            presetsChecked = true
            presetsReady = ready
            refreshUi()
            refreshBackupStatus()
        }
    }

    fun selectPreset(preset: QualityPreset) {
        if (operationRunning) return
        selectedPreset = preset
        prefs.edit().putString(KEY_SELECTED_PRESET, preset.id).apply()
        refreshUi()
    }

    private fun presetDisplayName(preset: QualityPreset): String = when (preset) {
        QualityPreset.LOW -> app.getString(R.string.preset_low)
        QualityPreset.HIGH -> app.getString(R.string.preset_high)
        QualityPreset.EXTREME -> app.getString(R.string.preset_extreme)
        QualityPreset.MEDIUM -> app.getString(R.string.preset_medium)
    }

    fun serverDisplayName(server: GameServer?): String {        if (server == null) return app.getString(R.string.current_target_none)
        return when (server) {
            GameServer.CHINA_OFFICIAL -> app.getString(R.string.server_china_official)
            GameServer.CHINA_BILIBILI -> app.getString(R.string.server_china_bilibili)
            GameServer.GLOBAL -> app.getString(R.string.server_global)
        }
    }

    // ===================== Main refresh =====================

    fun refreshUi() {
        if (destroyed) return

        if (selectedServer == null) {
            targetName = app.getString(R.string.current_target_none)
            targetState = app.getString(R.string.current_target_none_detail)
            configTargetName = app.getString(R.string.select_server)
            configTargetState = app.getString(R.string.current_target_none_detail)
            backupTargetName = app.getString(R.string.select_server)
            backupTargetState = app.getString(R.string.current_target_none_detail)
            gameVersionStatus = app.getString(R.string.game_version_unknown)
            presetCardTitle = app.getString(R.string.replace_card_title)
        } else {
            val server = selectedServer!!
            val gameVersion = getGameVersion(server)
            targetName = serverDisplayName(server)
            targetState = app.getString(R.string.game_process_checking)
            configTargetName = serverDisplayName(server)
            configTargetState = app.getString(R.string.tap_to_select_server)
            backupTargetName = serverDisplayName(server)
            backupTargetState = app.getString(R.string.tap_to_select_server)
            gameVersionStatus = app.getString(R.string.game_version_format, gameVersion, serverDisplayName(server))
            presetCardTitle = app.getString(
                R.string.preset_version_title, gameVersion, BuildConfig.PRESET_REVISION
            )
        }
        renderHomeServerRows()
        softwareVersionStatus = app.getString(R.string.software_version_short, BuildConfig.VERSION_NAME)

        val rotationActive = RotationControllerService.isRunning()
        homeRotationActive = rotationActive
        homeRotationStatus = app.getString(
            if (rotationActive) R.string.direction_control_active else R.string.direction_control_idle
        )

        storageRoot = repository.getStorageRoot()
        refreshAutomaticBackupRetentionUi()

        val checking = privilegeState.isCheckingRoot() ||
            privilegeState.shizukuStatus == PrivilegeState.ShizukuStatus.CONNECTING
        val ready = privilegeState.isReady()
        permissionWarningVisible = !ready && !checking
        permissionWarningText = if (TextUtils.isEmpty(privilegeState.detail)) {
            app.getString(R.string.permission_required)
        } else {
            app.getString(R.string.permission_with_detail, privilegeState.detail)
        }

        // Preset status
        if (!presetsChecked) {
            presetStatus = app.getString(R.string.status_checking)
            presetStatusColor = R.color.wuwa_on_surface_variant
            presetSelectionDetail = app.getString(R.string.preset_checking_detail)
            presetSelectionDetailColor = R.color.wuwa_on_surface_variant
        } else {
            val selectedPresetReady = presetsReady && repository.presetsReady(selectedPreset)
            if (selectedPresetReady) {
                val emptyTest = repository.hasEmptyPresetFile(selectedPreset)
                presetStatus = app.getString(
                    if (emptyTest) R.string.preset_status_test else R.string.preset_status_ready,
                    presetDisplayName(selectedPreset),
                )
                presetStatusColor = if (emptyTest) R.color.wuwa_primary else R.color.wuwa_success
                presetSelectionDetail = app.getString(
                    if (emptyTest) R.string.preset_selected_test else R.string.preset_selected_ready,
                    presetDisplayName(selectedPreset),
                )
                presetSelectionDetailColor = if (emptyTest) R.color.wuwa_primary else R.color.wuwa_on_surface_variant
            } else {
                presetStatus = app.getString(R.string.status_missing)
                presetStatusColor = R.color.wuwa_error
                presetSelectionDetail = app.getString(R.string.preset_missing)
                presetSelectionDetailColor = R.color.wuwa_error
            }
        }

        // Permission mode
        if (privilegeState.isRootAvailable()) {
            val provider = if (TextUtils.isEmpty(rootProviderName)) "Root" else rootProviderName
            showReadyPermission(
                app.getString(R.string.permission_root_provider_format, provider),
                R.string.permission_root,
            )
        } else if (privilegeState.isShizukuReady()) {
            showReadyPermission(app.getString(R.string.permission_shizuku_authorized), R.string.permission_shizuku)
        } else if (checking) {
            permissionModeIcon = R.drawable.ic_shield
            permissionModeStatus = app.getString(R.string.status_checking)
            permissionModeColor = R.color.wuwa_on_surface_variant
            setBottomPermissionStatus(R.drawable.ic_shield, R.string.permission_checking, R.color.wuwa_on_surface_variant)
        } else {
            permissionModeIcon = R.drawable.ic_warning
            permissionModeStatus = app.getString(R.string.status_not_ready)
            permissionModeColor = R.color.wuwa_error
            setBottomPermissionStatus(R.drawable.ic_warning, R.string.permission_not_ready, R.color.wuwa_error)
        }
        permissionDetail = if (TextUtils.isEmpty(privilegeState.detail)) {
            app.getString(R.string.permission_settings_description)
        } else {
            privilegeState.detail
        }

        settingsAppIdentity = app.getString(
            R.string.settings_app_identity_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
        rotationStatus = app.getString(R.string.rotation_status_idle)
    }

    private fun showReadyPermission(modeText: String, bottomStatusText: Int) {
        permissionModeIcon = R.drawable.ic_check
        permissionModeStatus = modeText
        permissionModeColor = R.color.wuwa_success
        setBottomPermissionStatus(R.drawable.ic_check, bottomStatusText, R.color.wuwa_success)
    }

    private fun setBottomPermissionStatus(icon: Int, text: Int, color: Int) {
        bottomPermissionStatus = app.getString(text)
        bottomPermissionColor = color
    }

    private fun renderHomeServerRows() {
        homeServerRows = GameServer.entries.map { server ->
            val installed = installedServers.contains(server)
            HomeServerUi(
                server = server,
                installed = installed,
                version = if (installed) {
                    app.getString(R.string.game_version_short, getGameVersion(server))
                } else {
                    app.getString(R.string.server_version_unavailable)
                },
                isCurrent = installed && selectedServer == server,
            )
        }
    }

    fun getGameVersion(server: GameServer): String {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                app.packageManager.getPackageInfo(
                    server.packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(server.packageName, 0)
            }
            val version = info.versionName ?: ""
            if (TextUtils.isEmpty(version)) app.getString(R.string.unknown_value)
            else {
                val matcher = Pattern.compile("(\\d+)\\.(\\d+)").matcher(version)
                if (matcher.find()) matcher.group(1) + "." + matcher.group(2) else version
            }
        } catch (_: PackageManager.NameNotFoundException) {
            app.getString(R.string.unknown_value)
        }
    }

    // ===================== Process / backup status =====================

    private fun refreshGameRuntimeStatus() {
        val server = selectedServer
        if (server == null) {
            gameProcessStatus = app.getString(R.string.game_process_not_installed)
            return
        }
        if (!privilegeState.isReady()) {
            gameProcessStatus = app.getString(R.string.game_process_permission_required)
            return
        }
        val generation = ++processQueryGeneration
        gameProcessStatus = app.getString(R.string.game_process_checking)
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    repository.getRunningProcessCount(server)
                }
                if (destroyed || generation != processQueryGeneration || server != selectedServer) return@launch
                gameProcessStatus = app.getString(
                    if (count == 0) R.string.game_process_stopped else R.string.game_process_running, count
                )
                targetState = app.getString(
                    if (count == 0) R.string.game_process_stopped else R.string.game_running_summary, count
                )
            } catch (_: OperationException) {
                if (destroyed || generation != processQueryGeneration) return@launch
                gameProcessStatus = app.getString(R.string.game_process_unavailable)
            }
        }
    }

    private fun refreshBackupStatus() {
        val server = selectedServer
        if (server == null || !privilegeState.isReady() || operationRunning) return
        val generation = ++backupQueryGeneration
        backupStatus = app.getString(R.string.backup_checking)
        backupStatusColor = R.color.wuwa_on_surface_variant
        backupLibrarySummary = app.getString(R.string.backup_library_checking)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val backedUp = repository.hasValidBackup(server)
                    val manualCount = repository.listManualBackups(server).size
                    backedUp to manualCount
                }
                if (destroyed || generation != backupQueryGeneration || server != selectedServer) return@launch
                val backupCount = result.second + if (result.first) 1 else 0
                if (backupCount > 0) {
                    backupStatus = app.getString(R.string.backup_count, backupCount)
                    backupStatusColor = R.color.wuwa_success
                } else {
                    backupStatus = app.getString(R.string.backup_missing)
                    backupStatusColor = R.color.wuwa_error
                }
                backupLibrarySummary = app.getString(
                    R.string.backup_library_summary_format,
                    app.getString(if (result.first) R.string.backup_ready else R.string.backup_missing),
                    result.second,
                )
            } catch (_: OperationException) {
                if (destroyed || generation != backupQueryGeneration || server != selectedServer) return@launch
                backupStatus = app.getString(R.string.backup_unreadable)
                backupStatusColor = R.color.wuwa_error
                backupLibrarySummary = app.getString(R.string.backup_unreadable)
            }
        }
    }

    private fun refreshRotationStatus() {
        rotationStatus = app.getString(R.string.rotation_status_idle)
    }

    private fun refreshAutomaticBackupRetentionUi() {
        retentionLimit = repository.getAutomaticBackupRetentionLimit()
    }

    // ===================== Load monitor =====================

    fun startLoadMonitor() {
        if (!activityResumed || destroyed) return
        val generation = ++loadGeneration
        previousCpuTotal = -1L
        previousCpuIdle = -1L
        previousGpuActive = -1L
        previousGpuTotal = -1L
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            while (isActive && generation == loadGeneration) {
                sampleLoadCycle()
                delay(2000)
            }
        }
    }

    fun stopLoadMonitor() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
    }

    private suspend fun sampleLoadCycle() {
        if (destroyed || !activityResumed) return
        updateMemoryUsage()
        if (!privilegeState.isReady()) {
            showLoadUnavailable()
            return
        }
        try {
            val sample = withContext(Dispatchers.IO) { repository.sampleSystemLoad() }
            if (destroyed) return
            applySystemLoadSample(sample)
        } catch (_: Exception) {
            if (destroyed) return
            showLoadUnavailable()
        }
    }

    private fun applySystemLoadSample(sample: ConfigRepository.SystemLoadSample) {
        if (previousCpuTotal >= 0L) {
            val totalDelta = sample.cpuTotal - previousCpuTotal
            val idleDelta = sample.cpuIdle - previousCpuIdle
            if (totalDelta > 0L) {
                val percent = clampPercent(
                    (100.0 * (totalDelta - Math.max(0L, idleDelta)) / totalDelta).roundToInt()
                )
                cpuPercent = percent
                loadAvailable = true
            }
        }
        previousCpuTotal = sample.cpuTotal
        previousCpuIdle = sample.cpuIdle

        var gpuPercentValue = -1
        if (sample.gpuDirectPercent >= 0) {
            gpuPercentValue = clampPercent(sample.gpuDirectPercent)
        } else if (previousGpuTotal >= 0L) {
            val activeDelta = sample.gpuActive - previousGpuActive
            val totalDelta = sample.gpuTotal - previousGpuTotal
            if (totalDelta > 0L && activeDelta >= 0L) {
                gpuPercentValue = clampPercent((100.0 * activeDelta / totalDelta).roundToInt())
            }
        }
        previousGpuActive = sample.gpuActive
        previousGpuTotal = sample.gpuTotal
        if (gpuPercentValue >= 0) {
            gpuPercent = gpuPercentValue
            loadAvailable = true
        }
    }

    private fun showLoadUnavailable() {
        cpuPercent = 0
        gpuPercent = 0
        memoryPercent = 0
        loadAvailable = false
    }

    private fun clampPercent(value: Int): Int = Math.max(0, Math.min(100, value))

    private fun updateMemoryUsage() {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        deviceMemory = app.getString(
            R.string.device_memory_available_total,
            formatMemoryGigabytes(info.availMem),
            formatMemoryGigabytes(info.totalMem),
        )
        val usedPercent = if (info.totalMem <= 0L) 0
        else clampPercent((100.0 * (info.totalMem - info.availMem) / info.totalMem).roundToInt())
        memoryPercent = usedPercent
        loadAvailable = true
    }

    private fun formatMemoryGigabytes(bytes: Long): String {
        val gigabytes = Math.max(0L, bytes) / 1_000_000_000.0
        val rounded = Math.rint(gigabytes)
        return if (Math.abs(gigabytes - rounded) < 0.08) {
            String.format(Locale.getDefault(), "%.0fGB", rounded)
        } else {
            String.format(Locale.getDefault(), "%.1fGB", gigabytes)
        }
    }

    fun bindDeviceInformation() {
        val deviceModelValue = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        deviceModel = deviceModelValue
        val processor = if (Build.VERSION.SDK_INT >= 31) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
        } else {
            Build.HARDWARE
        }
        deviceProcessor = if (processor.isBlank()) app.getString(R.string.unknown_value) else processor
        deviceSystem = app.getString(R.string.android_version_format, Build.VERSION.RELEASE)
        deviceSystemDetail = app.getString(
            R.string.android_system_detail, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        )
        updateMemoryUsage()
        viewModelScope.launch {
            val renderer = withContext(Dispatchers.IO) { readGpuRenderer() }
            if (destroyed) return@launch
            deviceGpu = renderer
            gpuRendererReady = true
        }
    }

    private fun readGpuRenderer(): String {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return app.getString(R.string.unknown_value)
        val versions = IntArray(2)
        if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            return app.getString(R.string.unknown_value)
        }
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        var surface = EGL14.EGL_NO_SURFACE
        var context = EGL14.EGL_NO_CONTEXT
        return try {
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                app.getString(R.string.unknown_value)
            } else {
                val surfaceAttributes = intArrayOf(
                    EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE
                )
                surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttributes, 0)
                val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
                if (surface == EGL14.EGL_NO_SURFACE || context == EGL14.EGL_NO_CONTEXT ||
                    !EGL14.eglMakeCurrent(display, surface, surface, context)
                ) {
                    app.getString(R.string.unknown_value)
                } else {
                    val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
                    if (TextUtils.isEmpty(renderer)) app.getString(R.string.unknown_value) else renderer.trim()
                }
            }
        } finally {
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    // ===================== Operations =====================

    private fun validateOperation(server: GameServer?, requirePreset: Boolean): Boolean {
        if (server == null) {
            showPanelDialog(
                app.getString(R.string.operation_failed),
                app.getString(R.string.select_server),
                DialogTone.WARNING,
                null,
                app.getString(R.string.done),
                false,
            )
            return false
        }
        if (!privilegeState.isReady()) {
            showError(app.getString(R.string.permission_required))
            return false
        }
        if (requirePreset && (!presetsReady || !repository.presetsReady(selectedPreset))) {
            showError(app.getString(R.string.preset_missing))
            return false
        }
        return true
    }

    fun runReplace() {
        val server = selectedServer ?: return
        if (!validateOperation(server, true)) return
        if (repository.hasEmptyPresetFile(selectedPreset)) {
            showPanelDialog(
                app.getString(R.string.preset_empty_warning_title),
                app.getString(R.string.preset_empty_warning_message, presetDisplayName(selectedPreset)),
                DialogTone.WARNING,
                app.getString(R.string.cancel),
                app.getString(R.string.continue_test),
                false,
                positiveAction = {
                    startReplace(server, selectedPreset)
                },
            )
        } else {
            startReplace(server, selectedPreset)
        }
    }

    private fun startReplace(server: GameServer, preset: QualityPreset) {
        runOperation(
            app.getString(R.string.progress_replace, presetDisplayName(preset)),
            { repository.replace(server, preset) },
            promptPortraitLaunch = true,
        )
    }

    fun runRestore() {
        val server = selectedServer ?: return
        if (!validateOperation(server, false)) return
        runOperation(
            app.getString(R.string.progress_restore),
            { repository.restore(server) },
            promptPortraitLaunch = false,
        )
    }

    fun runManualBackup() {
        val server = selectedServer ?: return
        if (!validateOperation(server, false)) return
        viewModelScope.launch {
            operationRunning = true
            backupQueryGeneration++
            refreshUi()
            dialogProgress = app.getString(R.string.progress_manual_backup)
            try {
                val snapshotName = withContext(Dispatchers.IO) { repository.createManualBackup(server) }
                finishOperation()
                refreshBackupStatus()
                showPanelDialog(
                    app.getString(R.string.dialog_completed),
                    app.getString(R.string.manual_backup_success, snapshotName),
                    DialogTone.SUCCESS,
                    null,
                    app.getString(R.string.done),
                    false,
                )
            } catch (error: Exception) {
                finishOperation()
                showError(safeMessage(error))
            }
        }
    }

    private fun runOperation(
        message: String,
        action: () -> Unit,
        promptPortraitLaunch: Boolean = false,
    ) {
        if (operationRunning) return
        viewModelScope.launch {
            operationRunning = true
            backupQueryGeneration++
            refreshUi()
            dialogProgress = message
            try {
                withContext(Dispatchers.IO) { action() }
                finishOperation()
                refreshBackupStatus()
                showSuccessDialog(promptPortraitLaunch)
            } catch (error: Exception) {
                finishOperation()
                showError(safeMessage(error))
            }
        }
    }

    private fun finishOperation() {
        operationRunning = false
        dialogProgress = null
        refreshUi()
    }

    private fun showSuccessDialog(promptPortraitLaunch: Boolean) {
        if (!promptPortraitLaunch) {
            showPanelDialog(
                app.getString(R.string.dialog_completed),
                app.getString(R.string.operation_success),
                DialogTone.SUCCESS,
                null,
                app.getString(R.string.done),
                false,
            )
            return
        }
        showPanelDialog(
            app.getString(R.string.replace_step_one_complete_title),
            app.getString(R.string.replace_step_two_prompt),
            DialogTone.SUCCESS,
            null,
            app.getString(R.string.understood),
            false,
        )
    }

    // ===================== Rotation =====================

    fun startPortraitLaunch() {
        val server = selectedServer ?: return
        if (!validateOperation(server, false)) return
        showPanelDialog(
            app.getString(R.string.portrait_launch_confirm_title),
            app.getString(R.string.portrait_launch_confirm_message),
            DialogTone.INFO,
            app.getString(R.string.cancel),
            app.getString(R.string.continue_action),
            false,
            positiveAction = { requestOverlayAndLaunch(server) },
        )
    }

    fun requestOverlayAndLaunch(server: GameServer) {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app)) {
            startFloatingRotationController(server)
            return
        }
        showPanelDialog(
            app.getString(R.string.overlay_permission_title),
            app.getString(R.string.overlay_permission_message),
            DialogTone.INFO,
            app.getString(R.string.cancel),
            app.getString(R.string.open_overlay_settings),
            false,
            positiveAction = {
                pendingOverlayServer = server
                overlayPermissionRequested = true
            },
        )
    }

    fun consumeOverlayPermissionRequest() {
        overlayPermissionRequested = false
    }

    fun clearPendingOverlayServer() {
        pendingOverlayServer = null
    }

    fun startFloatingRotationController(server: GameServer) {
        try {
            AppLogger.info("Rotation", "启动悬浮方向控制服务；服务器=" + server.packageName)
            RotationControllerService.start(app, server.packageName)
            rotationStatus = app.getString(R.string.rotation_status_overlay_active)
            showToast(R.string.rotation_overlay_starting)
        } catch (error: RuntimeException) {
            AppLogger.error("Rotation", "启动悬浮方向控制服务失败", error)
            showError(app.getString(R.string.rotation_service_start_failed, safeMessage(error)))
        }
    }

    fun restoreAutomaticRotation() {
        if (!privilegeState.isReady()) {
            showError(app.getString(R.string.permission_required))
            return
        }
        if (repository.isRotationSessionActive()) {
            rotationStatus = app.getString(R.string.rotation_status_restoring)
            RotationControllerService.requestRecovery(app)
            showToast(R.string.rotation_recovery_status, Toast.LENGTH_LONG)
            return
        }
        runRotationAction(
            app.getString(R.string.progress_restore_rotation),
            { repository.restoreAutomaticRotation() },
            onSuccess = {
                app.stopService(Intent(app, RotationControllerService::class.java))
                rotationStatus = app.getString(R.string.rotation_status_auto)
                showToast(R.string.rotation_restored, Toast.LENGTH_LONG)
            },
        )
    }

    private fun runRotationAction(
        progressMessage: String,
        action: () -> Unit,
        onSuccess: () -> Unit,
    ) {
        if (operationRunning) return
        viewModelScope.launch {
            operationRunning = true
            refreshUi()
            dialogProgress = progressMessage
            try {
                withContext(Dispatchers.IO) { action() }
                finishOperation()
                onSuccess()
            } catch (error: Exception) {
                finishOperation()
                showError(safeMessage(error))
            }
        }
    }

    // ===================== Rotation recovery overlay =====================

    private fun showRotationRecoveryPageIfNeeded() {
        if (!repository.hasPrivateRotationRecoveryState()) return
        showRotationRecovery = true
        rotationRecoveryStatus = app.getString(R.string.rotation_recovery_working)
        renderRotationRecoverySnapshot()
        viewModelScope.launch {
            requestPendingRotationRecovery()
        }
    }

    private fun renderRotationRecoverySnapshot() {
        val info = repository.getPrivateRotationRecoveryInfo()
        rotationRecoverySnapshot = app.getString(
            R.string.rotation_recovery_snapshot,
            app.getString(if (info.automaticRotationEnabled) R.string.rotation_recovery_auto_on else R.string.rotation_recovery_auto_off),
            info.userRotation,
            app.getString(
                when {
                    info.currentRotation == SurfaceCompat.ROTATION_90 ||
                        info.currentRotation == SurfaceCompat.ROTATION_270 -> R.string.rotation_recovery_landscape
                    else -> R.string.rotation_recovery_portrait
                }
            ),
            rotationPhaseLabel(info.phase),
        )
    }

    private fun rotationPhaseLabel(phase: String): String = when (phase) {
        ConfigRepository.ROTATION_PHASE_PREPARING -> app.getString(R.string.rotation_recovery_phase_preparing)
        ConfigRepository.ROTATION_PHASE_ACTIVE -> app.getString(R.string.rotation_recovery_phase_active)
        ConfigRepository.ROTATION_PHASE_RESTORING -> app.getString(R.string.rotation_recovery_phase_restoring)
        else -> app.getString(R.string.rotation_recovery_phase_restoring)
    }

    private suspend fun requestPendingRotationRecovery() = coroutineScope {
        rotationRecoveryStatus = app.getString(R.string.rotation_recovery_working)
        rotationRecoveryRetryEnabled = false
        try {
            RotationControllerService.requestRecovery(app)
        } catch (_: RuntimeException) {
            rotationRecoveryStatus = app.getString(R.string.rotation_recovery_waiting_permission)
            rotationRecoveryRetryEnabled = true
            return@coroutineScope
        }
        while (isActive && repository.hasPrivateRotationRecoveryState()) {
            delay(900)
        }
        if (!isActive) return@coroutineScope
        delay(450)
        showRotationRecovery = false
        tab = MainTab.HOME
        refreshUi()
        refreshBackupStatus()
    }

    fun retryRotationRecovery() {
        if (repository.hasPrivateRotationRecoveryState()) {
            viewModelScope.launch { requestPendingRotationRecovery() }
        }
    }

    fun requestEmergencyRotationRecovery() {
        emergencyRotationRecoveryRequested = true
        firstRunRecoveryButtonEnabled = false
        if (privilegeState.isReady()) {
            emergencyRotationRecoveryRequested = false
            performEmergencyRotationRecovery()
        } else {
            privilegeManager.requestAuthorization()
        }
    }

    fun isFirstRunRecoveryButtonEnabled(): Boolean = firstRunRecoveryButtonEnabled

    private fun performEmergencyRotationRecovery() {
        viewModelScope.launch {
            try {
                val restored = withContext(Dispatchers.IO) {
                    repository.recoverInterruptedRotationSessionIfNeeded()
                }
                if (destroyed) return@launch
                firstRunRecoveryButtonEnabled = true
                if (restored) {
                    showToast(R.string.first_run_restore_rotation_success)
                } else {
                    withContext(Dispatchers.IO) { repository.restoreAutomaticRotation() }
                    firstRunRecoveryButtonEnabled = true
                    showToast(R.string.first_run_restore_rotation_success)
                }
            } catch (error: Exception) {
                if (destroyed) return@launch
                firstRunRecoveryButtonEnabled = true
                showToast(R.string.first_run_restore_rotation_failed)
                AppLogger.error("Rotation", "紧急恢复失败", error)
            }
        }
    }

    private fun recoverInterruptedRotationSession() {
        if (repository.isRotationSessionActive()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.recoverInterruptedRotationSessionIfNeeded()
                }
            } catch (error: Exception) {
                AppLogger.error("Rotation", "自动恢复旋转会话失败", error)
                withContext(Dispatchers.IO) {
                    try {
                        repository.restoreAutomaticRotation()
                    } catch (ignored: Exception) {
                    }
                }
            }
        }
    }

    // ===================== Manual backups =====================

    fun openRestoreCenter() {
        val server = selectedServer ?: return
        if (!validateOperation(server, false)) return
        viewModelScope.launch {
            dialogProgress = app.getString(R.string.progress_scan_backup)
            try {
                val result = withContext(Dispatchers.IO) {
                    val hasInitial = repository.hasValidBackup(server)
                    val snapshots = repository.listManualBackups(server)
                    RestoreCenterData(server, hasInitial, snapshots)
                }
                dialogProgress = null
                dialogRestoreCenter = result
            } catch (error: Exception) {
                dialogProgress = null
                showError(safeMessage(error))
            }
        }
    }

    fun confirmInitialRestore() {
        dialogRestoreCenter = null
        val server = selectedServer ?: return
        showPanelDialog(
            app.getString(R.string.initial_backup_title),
            app.getString(R.string.initial_backup_description),
            DialogTone.WARNING,
            app.getString(R.string.cancel),
            app.getString(R.string.restore_action),
            false,
            positiveAction = {
                runOperation(app.getString(R.string.progress_restore), { repository.restore(server) })
            },
        )
    }

    fun confirmManualRestore(server: GameServer, snapshot: ManualBackupInfo) {
        dialogRestoreCenter = null
        showPanelDialog(
            app.getString(R.string.manual_restore_confirm_title),
            app.getString(R.string.manual_restore_confirm_message, displayNameForSnapshot(snapshot)),
            DialogTone.WARNING,
            app.getString(R.string.cancel),
            app.getString(R.string.restore_snapshot),
            false,
            positiveAction = {
                runOperation(
                    app.getString(R.string.progress_restore_snapshot),
                    { repository.restoreManualBackup(server, snapshot.snapshotName) },
                )
            },
        )
    }

    fun dismissRestoreCenter() {
        dialogRestoreCenter = null
    }

    fun openManualBackupManager() {
        val server = selectedServer ?: return
        if (!validateOperation(server, false)) return
        viewModelScope.launch {
            dialogProgress = app.getString(R.string.progress_scan_backup)
            try {
                val result = withContext(Dispatchers.IO) {
                    val hasOriginal = repository.hasValidBackup(server)
                    val snapshots = repository.listManualBackups(server)
                    ManualManagerData(server, hasOriginal, snapshots)
                }
                dialogProgress = null
                dialogManualManager = result
            } catch (error: Exception) {
                dialogProgress = null
                showError(safeMessage(error))
            }
        }
    }

    fun dismissManualManager() {
        dialogManualManager = null
    }

    fun showRenameBackupDialog(server: GameServer, snapshot: ManualBackupInfo) {
        dialogTextInput = TextInputRequest(
            title = app.getString(R.string.rename_backup_title),
            message = null,
            hint = app.getString(R.string.name),
            initial = displayNameForSnapshot(snapshot),
            maxLength = 30,
            onSave = { newName ->
                renameManualBackupAsync(server, snapshot, newName)
            },
        )
    }

    private fun renameManualBackupAsync(
        server: GameServer,
        snapshot: ManualBackupInfo,
        name: String,
    ) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            showToast(R.string.first_run_agreement_required)
            return
        }
        viewModelScope.launch {
            dialogProgress = app.getString(R.string.dialog_processing_title)
            try {
                withContext(Dispatchers.IO) {
                    repository.renameManualBackup(server, snapshot.snapshotName, trimmed)
                }
                dialogProgress = null
                showToast(R.string.rename_success)
                openManualBackupManager()
            } catch (error: Exception) {
                dialogProgress = null
                showError(safeMessage(error))
            }
        }
    }

    fun confirmDeleteManualBackups(server: GameServer, names: List<String>) {
        if (names.isEmpty()) return
        showPanelDialog(
            app.getString(R.string.delete_selected_title),
            app.getString(R.string.delete_selected_message, names.size),
            DialogTone.WARNING,
            app.getString(R.string.cancel),
            app.getString(R.string.delete_backup),
            destructive = true,
            positiveAction = { deleteManualBackupsAsync(server, names) },
        )
    }

    private fun deleteManualBackupsAsync(server: GameServer, names: List<String>) {
        viewModelScope.launch {
            dialogProgress = app.getString(R.string.progress_delete_backup)
            try {
                withContext(Dispatchers.IO) {
                    repository.deleteManualBackups(server, names)
                }
                dialogProgress = null
                showToast(R.string.manual_backups_deleted)
                openManualBackupManager()
            } catch (error: Exception) {
                dialogProgress = null
                showError(safeMessage(error))
            }
        }
    }

    fun displayNameForSnapshot(snapshot: ManualBackupInfo): String {
        return if (snapshot.hasCustomName() && snapshot.displayName.isNotBlank()) {
            snapshot.displayName
        } else {
            formatSnapshotName(snapshot.snapshotName)
        }
    }

    fun snapshotTypeLabel(snapshot: ManualBackupInfo): String = if (snapshot.isAutomatic) {
        app.getString(R.string.backup_type_automatic)
    } else {
        app.getString(R.string.backup_type_manual)
    }

    fun logFileDetail(log: LogEntry): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(log.modifiedTimeMillis))
        return app.getString(R.string.log_file_detail_format, time, formatBytes(log.sizeBytes))
    }

    private fun formatSnapshotName(name: String): String {
        val source = Pattern.compile("^(\\d{14})").matcher(name)
        return if (source.find()) {
            val timestamp = source.group(1)
            try {
                val parsed = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    .parse(timestamp)
                if (parsed != null) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(parsed)
                } else name
            } catch (_: Exception) {
                name
            }
        } else name
    }

    // ===================== Storage =====================

    fun showChangeStorageDialog() {
        showChoicePanel(
            app.getString(R.string.change_storage_title),
            app.getString(R.string.change_storage_method_title),
            listOf(
                ChoiceItem(app.getString(R.string.choose_storage_folder)),
                ChoiceItem(app.getString(R.string.enter_storage_path)),
            ),
            onChoice = { index ->
                if (index == 1) showManualStoragePathDialog()
            },
        )
    }

    fun showManualStoragePathDialog() {
        dialogTextInput = TextInputRequest(
            title = app.getString(R.string.change_storage_title),
            message = app.getString(R.string.change_storage_message),
            hint = app.getString(R.string.storage_path_hint),
            initial = repository.getStorageRoot(),
            maxLength = 220,
            onSave = { path ->
                val trimmed = path.trim()
                if (!trimmed.startsWith("/storage/emulated/0/") || trimmed.contains("..")) {
                    showToast(R.string.storage_path_invalid)
                    return@TextInputRequest
                }
                changeStorageAsync(trimmed)
            },
        )
    }

    fun changeStorageAsync(path: String) {
        viewModelScope.launch {
            dialogProgress = app.getString(R.string.dialog_processing_title)
            try {
                withContext(Dispatchers.IO) { repository.setStorageRoot(path) }
                dialogProgress = null
                storageRoot = repository.getStorageRoot()
                showToast(R.string.storage_changed)
                refreshUi()
            } catch (error: Exception) {
                dialogProgress = null
                showError(safeMessage(error))
            }
        }
    }

    fun showAutomaticBackupRetentionChooser() {
        val current = repository.getAutomaticBackupRetentionLimit()
        showChoicePanel(
            app.getString(R.string.automatic_backup_retention_title),
            app.getString(R.string.automatic_backup_retention_dialog_description),
            listOf(
                ChoiceItem(
                    app.getString(R.string.automatic_backup_retention_5),
                    if (current == 5) app.getString(R.string.current_selection) else null,
                ),
                ChoiceItem(
                    app.getString(R.string.automatic_backup_retention_10),
                    if (current == 10) app.getString(R.string.current_selection) else null,
                ),
                ChoiceItem(
                    app.getString(R.string.automatic_backup_retention_20),
                    if (current == 20) app.getString(R.string.current_selection) else null,
                ),
                ChoiceItem(
                    app.getString(R.string.automatic_backup_retention_unlimited),
                    if (current == 0) app.getString(R.string.current_selection) else null,
                ),
            ),
            onChoice = { index ->
                val limit = when (index) {
                    0 -> 5
                    1 -> 10
                    2 -> 20
                    else -> 0
                }
                repository.setAutomaticBackupRetentionLimit(limit)
                retentionLimit = limit
                showToast(R.string.automatic_backup_retention_saved)
            },
        )
    }

    fun retentionLabel(limit: Int): String = when (limit) {
        5 -> app.getString(R.string.automatic_backup_retention_5)
        10 -> app.getString(R.string.automatic_backup_retention_10)
        20 -> app.getString(R.string.automatic_backup_retention_20)
        else -> app.getString(R.string.automatic_backup_retention_unlimited)
    }

    // ===================== Logs =====================

    fun refreshLogSummary() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val logs = AppLogger.listLogs(app)
                    val count = logs.size
                    val bytes = logs.sumOf { it.sizeBytes }
                    count to bytes
                } catch (_: Exception) {
                    0 to 0L
                }
            }
            if (destroyed) return@launch
            logSummary = app.getString(
                R.string.log_manager_status_format, result.first, formatBytes(result.second)
            )
        }
    }

    fun openLogManager() {
        viewModelScope.launch {
            val logs = withContext(Dispatchers.IO) {
                try {
                    AppLogger.listLogs(app)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (destroyed) return@launch
            dialogLogManager = LogManagerData(logs)
        }
    }

    fun dismissLogManager() {
        dialogLogManager = null
    }

    fun confirmDeleteLog(log: LogEntry) {
        showPanelDialog(
            app.getString(R.string.delete_log_title),
            app.getString(R.string.delete_log_message, log.displayName),
            DialogTone.WARNING,
            app.getString(R.string.cancel),
            app.getString(R.string.delete_log),
            destructive = true,
            positiveAction = { deleteLog(log) },
        )
    }

    private fun deleteLog(log: LogEntry) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { AppLogger.deleteLog(app, log) }
                showToast(R.string.log_deleted)
                refreshLogSummary()
                openLogManager()
            } catch (error: Exception) {
                showError(app.getString(R.string.log_delete_failed) + "\n" + safeMessage(error))
            }
        }
    }

    fun shareLogs(logs: List<LogEntry>) {
        val uris = logs.mapNotNull { it.contentUri }
        if (uris.isEmpty()) {
            showToast(R.string.log_share_failed)
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = android.content.ClipData.newRawUri("Wuwa CFBP log", uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val chooser = Intent.createChooser(intent, app.getString(R.string.log_share_chooser))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(chooser)
        } catch (_: Exception) {
            showToast(R.string.log_share_failed)
        }
    }

    fun shareCurrentLog() {
        val uri = AppLogger.getCurrentShareUri(app) ?: run {
            showToast(R.string.log_share_failed)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("Wuwa CFBP log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            app.startActivity(
                Intent.createChooser(intent, app.getString(R.string.log_share_chooser))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            showToast(R.string.log_share_failed)
        }
    }

    // ===================== Remote notices / updates =====================

    fun openAnnouncementCenter() {
        requestRemoteManifest(true, true)
    }

    fun checkRemoteUpdates(userInitiated: Boolean) {
        if (!prefs.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) return
        if (!isRemoteNoticeConfigured()) {
            updateRemoteFrameworkStatus()
            if (userInitiated) {
                showPanelDialog(
                    app.getString(R.string.announcement_service_unconfigured_title),
                    app.getString(R.string.announcement_service_unconfigured_message),
                    DialogTone.INFO,
                    null,
                    app.getString(R.string.done),
                    false,
                )
            }
            return
        }
        if (!userInitiated && updateCheckStarted) return
        requestRemoteManifest(userInitiated, false)
    }

    private fun isRemoteNoticeConfigured(): Boolean {
        val repo = remoteNoticeRepository
        return repo != null && repo.isConfigured()
    }

    private fun updateRemoteFrameworkStatus() {
        if (!isRemoteNoticeConfigured()) {
            announcementStatus = app.getString(R.string.announcement_service_unconfigured)
            updateStatus = app.getString(
                R.string.current_version_click_check, BuildConfig.VERSION_NAME
            )
        }
    }

    private fun setUpdateStatusText(status: String) {
        updateStatus = status
    }

    fun refreshRemoteUpdateCheck() {
        requestRemoteManifest(true, false)
    }

    private fun requestRemoteManifest(userInitiated: Boolean, announcementOnly: Boolean) {
        if (remoteCheckRunning || !isRemoteNoticeConfigured()) return
        AppLogger.info(
            "RemoteNotice",
            "Manifest request started; userInitiated=$userInitiated, announcementOnly=$announcementOnly",
        )
        if (!userInitiated && !announcementOnly) updateCheckStarted = true
        remoteCheckRunning = true
        if (announcementOnly) {
            announcementStatus = app.getString(R.string.announcement_loading)
        } else {
            setUpdateStatusText(app.getString(R.string.checking_updates))
        }
        viewModelScope.launch {
            performRemoteManifestRequest(userInitiated, announcementOnly, 0)
        }
    }

    private suspend fun performRemoteManifestRequest(
        userInitiated: Boolean,
        announcementOnly: Boolean,
        attempt: Int,
    ) {
        val repo = remoteNoticeRepository
        var fetchError: Exception? = null
        val fetchResult = try {
            if (repo == null) null else withContext(Dispatchers.IO) { repo.fetch() }
        } catch (error: Exception) {
            fetchError = error
            AppLogger.error("RemoteNotice", "Manifest request failed", error)
            null
        }
        if (destroyed) return
        if (fetchResult == null) {
            if (attempt < REMOTE_MAX_RETRIES) {
                setUpdateStatusText(app.getString(R.string.update_check_retrying))
                delay(REMOTE_RETRY_DELAY_MS)
                performRemoteManifestRequest(userInitiated, announcementOnly, attempt + 1)
                return
            }
            remoteCheckRunning = false
            announcementStatus = app.getString(R.string.announcement_check_failed)
            setUpdateStatusText(app.getString(R.string.update_check_failed))
            if (userInitiated) {
                showPanelDialog(
                    app.getString(R.string.update_check_failed),
                    app.getString(R.string.update_check_failed_message_detail,
                        safeMessage(fetchError)),
                    DialogTone.WARNING,
                    null,
                    app.getString(R.string.done),
                    false,
                )
            }
            return
        }
        if (fetchResult.source == RemoteNoticeRepository.Source.STALE_CACHE &&
            attempt < REMOTE_MAX_RETRIES
        ) {
            setUpdateStatusText(app.getString(R.string.update_check_retrying))
            AppLogger.warn("RemoteNotice", "Network unavailable; retrying manifest")
            delay(REMOTE_RETRY_DELAY_MS)
            performRemoteManifestRequest(userInitiated, announcementOnly, attempt + 1)
            return
        }
        remoteCheckRunning = false
        val payload = fetchResult.payload
        val announcement = payload?.announcement
        val update = payload?.update
        AppLogger.info(
            "RemoteNotice",
            "Manifest loaded; announcement=" + (announcement != null && announcement.isEnabled) +
                ", latestCode=" + (update?.latestVersionCode ?: 0L),
        )
        applyRemoteManifest(fetchResult, userInitiated, announcementOnly)
    }

    private fun applyRemoteManifest(
        fetchResult: RemoteNoticeRepository.FetchResult,
        userInitiated: Boolean,
        announcementOnly: Boolean,
    ) {
        val payload = fetchResult.payload ?: return
        val now = System.currentTimeMillis()
        val trustedForLocking = fetchResult.isNetworkConfirmed ||
            RemoteNoticeEvaluator.isCacheFreshEnough(
                fetchResult.validatedAtMillis, now, REMOTE_LOCK_CACHE_MAX_AGE_MS
            )
        val securityMode = payload.securityMode
        if (trustedForLocking && isUsableSecurityMode(securityMode)) {
            val sm = securityMode!!
            showCloudSecurityMode(
                SecurityModeData(
                    mode = sm,
                    allowRecheck = true,
                    allowLogExport = sm.isLogExportAllowed,
                    allowRotationRecovery = sm.isRotationRecoveryAllowed,
                ),
                payload.revision,
            )
            return
        }
        dismissCloudSecurityMode()

        val announcement = payload.announcement
        val update = payload.update
        announcementStatus = if (isUsableAnnouncement(announcement)) {
            announcement?.title ?: app.getString(R.string.announcement_empty)
        } else {
            app.getString(R.string.announcement_empty)
        }

        val updateAvailable = trustedForLocking && isUsableUpdate(update)
        if (!updateAvailable && dialogRemoteUpdate != null) {
            dialogRemoteUpdate = null
        }
        if (updateAvailable) {
            setUpdateStatusText(app.getString(
                R.string.update_available_format, displayRemoteVersion(update!!)
            ))
        } else if (!fetchResult.isNetworkConfirmed) {
            setUpdateStatusText(app.getString(R.string.update_cache_status))
        } else {
            setUpdateStatusText(app.getString(
                R.string.latest_version_ready, BuildConfig.VERSION_NAME
            ))
        }

        if (!prefs.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) return

        if (announcementOnly) {
            if (isUsableAnnouncement(announcement)) {
                showRemoteAnnouncement(announcement!!)
            } else {
                showPanelDialog(
                    app.getString(R.string.announcement_center),
                    app.getString(R.string.announcement_empty),
                    DialogTone.INFO,
                    null,
                    app.getString(R.string.done),
                    false,
                )
            }
            return
        }

        if (updateAvailable) {
            val u = update ?: return
            val ignoredVersion = prefs.getLong(KEY_IGNORED_UPDATE_VERSION_CODE, 0L)
            if (userInitiated || isMandatoryUpdate(u) ||
                u.latestVersionCode != ignoredVersion
            ) {
                showRemoteUpdate(u)
            }
            return
        }
        if (!userInitiated && shouldAutomaticallyShow(announcement)) {
            showRemoteAnnouncement(announcement!!)
            return
        }
        if (userInitiated) {
            if (!fetchResult.isNetworkConfirmed) {
                showPanelDialog(
                    app.getString(R.string.update_check_unconfirmed_title),
                    app.getString(R.string.update_check_unconfirmed_message),
                    DialogTone.WARNING,
                    null,
                    app.getString(R.string.done),
                    false,
                )
                return
            }
            showPanelDialog(
                app.getString(R.string.no_update_title),
                app.getString(R.string.no_update_message,
                    BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                DialogTone.SUCCESS,
                null,
                app.getString(R.string.done),
                false,
            )
        }
    }

    private fun isUsableAnnouncement(announcement: RemoteNoticePayload.Announcement?): Boolean {
        return announcement != null &&
            announcement.isEnabled &&
            !TextUtils.isEmpty(announcement.title) &&
            !TextUtils.isEmpty(announcement.content) &&
            RemoteNoticeEvaluator.isActiveWindow(
                announcement.effectiveAt, announcement.expiresAt, System.currentTimeMillis()
            )
    }

    private fun isUsableSecurityMode(mode: RemoteNoticePayload.SecurityMode?): Boolean {
        if (mode == null || !mode.isEnabled || TextUtils.isEmpty(mode.expiresAt)) return false
        val current = BuildConfig.VERSION_CODE.toLong()
        if (mode.minimumVersionCode > 0L && current < mode.minimumVersionCode) return false
        if (mode.maximumVersionCode > 0L && current > mode.maximumVersionCode) return false
        return RemoteNoticeEvaluator.isActiveWindow(
            mode.effectiveAt, mode.expiresAt, System.currentTimeMillis()
        )
    }

    private fun isUsableUpdate(update: RemoteNoticePayload.Update?): Boolean {
        if (update == null ||
            !update.isEnabled ||
            update.isPaused ||
            !RemoteNoticeEvaluator.hasExplicitUpdateAudience(
                update.appliesToMinimumVersionCode, update.appliesToMaximumVersionCode
            ) ||
            !RemoteNoticeEvaluator.isUpdateApplicable(
                BuildConfig.VERSION_CODE.toLong(),
                update.latestVersionCode,
                update.appliesToMinimumVersionCode,
                update.appliesToMaximumVersionCode
            ) ||
            !RemoteNoticeEvaluator.isActiveWindow(
                update.effectiveAt, update.expiresAt, System.currentTimeMillis()
            )
        ) {
            return false
        }
        val uri = try {
            Uri.parse(update.url)
        } catch (_: Exception) {
            return false
        }
        val safeUrl = "https".equals(uri.scheme, ignoreCase = true) &&
            !TextUtils.isEmpty(uri.host)
        if (!safeUrl) {
            AppLogger.warn("RemoteNotice", "Ignored update with invalid HTTPS URL")
        }
        return safeUrl
    }

    private fun shouldAutomaticallyShow(announcement: RemoteNoticePayload.Announcement?): Boolean {
        if (!isUsableAnnouncement(announcement)) return false
        val a = announcement!!
        if (TextUtils.isEmpty(a.id)) return false
        val policy = a.displayPolicy
        if ("every_start".equals(policy, ignoreCase = true)) {
            return !announcementsShownThisSession.contains(a.id)
        }
        if ("daily".equals(policy, ignoreCase = true)) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                .format(Date(System.currentTimeMillis()))
            return a.id != (prefs.getString(KEY_LAST_DAILY_ANNOUNCEMENT_ID, "") ?: "") ||
                today != (prefs.getString(KEY_LAST_DAILY_ANNOUNCEMENT_DATE, "") ?: "")
        }
        return a.id != (prefs.getString(KEY_LAST_ANNOUNCEMENT_ID, "") ?: "")
    }

    private fun showRemoteAnnouncement(announcement: RemoteNoticePayload.Announcement) {
        dialogRemoteNotice = announcement
    }

    fun dismissRemoteNotice() {
        val announcement = dialogRemoteNotice
        if (announcement != null) markAnnouncementShown(announcement)
        dialogRemoteNotice = null
    }

    private fun markAnnouncementShown(announcement: RemoteNoticePayload.Announcement) {
        if (TextUtils.isEmpty(announcement.id)) return
        announcementsShownThisSession.add(announcement.id)
        val policy = announcement.displayPolicy
        if ("every_start".equals(policy, ignoreCase = true)) return
        val editor = prefs.edit()
        if ("daily".equals(policy, ignoreCase = true)) {
            editor.putString(KEY_LAST_DAILY_ANNOUNCEMENT_ID, announcement.id)
            editor.putString(
                KEY_LAST_DAILY_ANNOUNCEMENT_DATE,
                SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(System.currentTimeMillis()))
            )
        } else {
            editor.putString(KEY_LAST_ANNOUNCEMENT_ID, announcement.id)
        }
        editor.apply()
    }

    fun announcementHighlightColorRes(announcement: RemoteNoticePayload.Announcement): Int =
        when (announcement.highlightColor.lowercase()) {
            "red" -> R.color.wuwa_error
            "orange" -> R.color.wuwa_warning
            "green" -> R.color.wuwa_success
            else -> R.color.wuwa_primary
        }

    fun hasAnnouncementAction(announcement: RemoteNoticePayload.Announcement): Boolean =
        !TextUtils.isEmpty(announcement.actionLabel) && isSafeHttpsUrl(announcement.actionUrl)

    fun openAnnouncementAction(announcement: RemoteNoticePayload.Announcement) {
        if (!announcement.isActionConfirm) {
            openExternalLink(announcement.actionUrl ?: "", false)
            return
        }
        val url = announcement.actionUrl ?: ""
        val host = try {
            Uri.parse(url).host ?: ""
        } catch (_: Exception) {
            ""
        }
        showPanelDialog(
            app.getString(R.string.external_link_confirmation_title),
            app.getString(R.string.external_link_confirmation_message, host),
            DialogTone.INFO,
            app.getString(R.string.cancel),
            app.getString(R.string.continue_open),
            false,
            positiveAction = { openExternalLink(url, false) },
        )
    }

    fun openExternalLink(url: String, allowAppSchemes: Boolean) {
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            null
        }
        if (uri == null) {
            showToast(R.string.link_open_failed, url)
            return
        }
        val scheme = uri.scheme ?: ""
        val valid = (scheme == "https" && !TextUtils.isEmpty(uri.host)) ||
            (allowAppSchemes && (scheme == "bilibili" || scheme == "mqqapi"))
        if (!valid) {
            showToast(R.string.link_open_failed, url)
            return
        }
        try {
            app.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            showToast(R.string.link_open_failed, url)
        }
    }

    fun openConfiguredLink(url: String) {
        if (TextUtils.isEmpty(url)) {
            showPanelDialog(
                app.getString(R.string.support_link_unconfigured_title),
                app.getString(R.string.support_link_unconfigured_message),
                DialogTone.WARNING,
                null,
                app.getString(R.string.done),
                false,
            )
            return
        }
        openExternalLink(url, false)
    }

    private fun isSafeHttpsUrl(rawUrl: String?): Boolean {
        if (TextUtils.isEmpty(rawUrl)) return false
        return try {
            val uri = Uri.parse(rawUrl!!.trim())
            "https".equals(uri.scheme, ignoreCase = true) && !TextUtils.isEmpty(uri.host)
        } catch (_: Exception) {
            false
        }
    }

    fun showRemoteUpdate(update: RemoteNoticePayload.Update) {
        dialogRemoteUpdate = RemoteUpdateData(update)
    }

    fun dismissRemoteUpdate() {
        dialogRemoteUpdate = null
    }

    fun ignoreUpdate(update: RemoteNoticePayload.Update) {
        prefs.edit().putLong(KEY_IGNORED_UPDATE_VERSION_CODE, update.latestVersionCode).apply()
        dialogRemoteUpdate = null
    }

    fun openUpdateUrl(update: RemoteNoticePayload.Update) {
        openExternalLink(update.url ?: "", false)
    }

    fun isMandatoryUpdate(update: RemoteNoticePayload.Update?): Boolean {
        return update != null && (update.isForce ||
            BuildConfig.VERSION_CODE.toLong() < update.minimumSupportedVersionCode)
    }

    fun displayRemoteVersion(update: RemoteNoticePayload.Update): String {
        return if (!TextUtils.isEmpty(update.latestVersionName)) update.latestVersionName!!
        else update.latestVersionCode.toString()
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f)
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f))
    }

    fun abbreviateHash(hash: String): String {
        val normalized = (hash ?: "").trim().uppercase(Locale.ROOT)
        if (normalized.length <= 20) return normalized
        return normalized.substring(0, 12) + "…" + normalized.substring(normalized.length - 8)
    }

    fun formatNoticeTimestamp(timestamp: String): String {
        if (TextUtils.isEmpty(timestamp)) return ""
        return try {
            val instant = java.time.Instant.parse(timestamp.trim())
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(instant.toEpochMilli()))
        } catch (_: Exception) {
            try {
                val offset = java.time.OffsetDateTime.parse(timestamp.trim())
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(offset.toInstant().toEpochMilli()))
            } catch (_: Exception) {
                timestamp
            }
        }
    }

    fun announcementTypeLabel(type: String): String = when (type) {
        "important" -> app.getString(R.string.announcement_type_important)
        "maintenance" -> app.getString(R.string.announcement_type_maintenance)
        "version_update" -> app.getString(R.string.announcement_type_version_update)
        else -> app.getString(R.string.announcement_type_normal)
    }

    fun securityModeLevelLabel(level: String): String = when (level) {
        "emergency" -> app.getString(R.string.security_mode_level_emergency)
        "important" -> app.getString(R.string.security_mode_level_important)
        "maintenance" -> app.getString(R.string.security_mode_level_maintenance)
        else -> app.getString(R.string.security_mode_default_title)
    }

    fun openSecurityModeDownload(mode: RemoteNoticePayload.SecurityMode) {
        val url = mode.downloadUrl
        if (isSafeHttpsUrl(url)) {
            openExternalLink(url ?: "", false)
        } else {
            openExternalLink(app.getString(R.string.official_download_url), false)
        }
    }

    fun dismissCloudSecurityMode() {
        if (securityMode == null) return
        securityMode = null
        refreshUi()
        if (!prefs.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) {
            showFirstRun = true
        }
    }

    private fun showCloudSecurityMode(data: SecurityModeData, revision: String) {
        if (RotationControllerService.isRunning()) {
            app.stopService(Intent(app, RotationControllerService::class.java))
        }
        securityMode = data
    }

    // ===================== First run =====================

    private fun showFirstRunNoticeIfNeeded() {
        if (prefs.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) return
        if (securityMode != null || showRotationRecovery) return
        showFirstRun = true
    }

    fun acceptFirstRun() {
        prefs.edit().putBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, true).apply()
        showFirstRun = false
        privilegeManager.refresh()
        checkRemoteUpdates(false)
        if (installedServers.size > 1) {
            viewModelScope.launch {
                delay(400)
                dialogServerChooser = true
            }
        }
    }

    fun rejectFirstRun() {
        // The activity finishes itself.
    }

    fun isFirstRunAccepted(): Boolean =
        prefs.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)

    // ===================== Common dialogs =====================

    fun showPanelDialog(
        title: String,
        message: String,
        tone: DialogTone,
        negativeText: String?,
        positiveText: String,
        destructive: Boolean = false,
        negativeAction: (() -> Unit)? = null,
        positiveAction: (() -> Unit)? = null,
    ) {
        dialogPanel = PanelDialog(
            title = title,
            message = message,
            tone = tone,
            negativeText = negativeText,
            positiveText = positiveText,
            destructive = destructive,
            negativeAction = negativeAction,
            positiveAction = positiveAction,
        )
    }

    fun dismissPanelDialog() {
        dialogPanel = null
    }

    fun showChoicePanel(
        title: String,
        description: String?,
        items: List<ChoiceItem>,
        onChoice: (Int) -> Unit,
    ) {
        dialogChoice = ChoicePanel(
            title = title,
            description = description,
            items = items,
            onChoice = onChoice,
        )
    }

    fun dismissChoicePanel() {
        dialogChoice = null
    }

    fun dismissTextInput() {
        dialogTextInput = null
    }

    fun dismissProgress() {
        dialogProgress = null
    }

    fun showError(message: String) {
        showPanelDialog(
            app.getString(R.string.operation_failed),
            message,
            DialogTone.ERROR,
            null,
            app.getString(R.string.done),
            false,
        )
    }

    fun showToast(resId: Int, vararg args: Any) {
        val text = if (args.isEmpty()) app.getString(resId) else app.getString(resId, *args)
        toastMessage = text to Toast.LENGTH_SHORT
    }

    fun showToast(resId: Int, duration: Int, vararg args: Any) {
        val text = if (args.isEmpty()) app.getString(resId) else app.getString(resId, *args)
        toastMessage = text to duration
    }

    fun consumeToast() {
        toastMessage = null
    }

    private fun safeMessage(error: Throwable?): String {
        if (error == null) return "Unknown error"
        val message = error.message
        return if (message == null || message.trim().isEmpty()) error.javaClass.simpleName else message
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024L * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        }
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    // ===================== External helpers =====================

    fun requestOverlayPermissionHandled(): Boolean = Settings.canDrawOverlays(app)

    fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + app.packageName),
        )
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Surface rotation constants mirrored from android.view.Surface (avoid framework import ambiguity). */
object SurfaceCompat {
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3
}
