package com.wuwa.config.manager;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.Manifest;
import android.app.Dialog;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.ClipData;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.Surface;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.view.WindowCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.TextViewCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.wuwa.config.manager.data.ConfigRepository;
import com.wuwa.config.manager.data.ManualBackupInfo;
import com.wuwa.config.manager.data.OperationException;
import com.wuwa.config.manager.data.RemoteNoticePayload;
import com.wuwa.config.manager.data.RemoteNoticeRepository;
import com.wuwa.config.manager.data.RemoteNoticeEvaluator;
import com.wuwa.config.manager.diagnostics.AppLogger;
import com.wuwa.config.manager.diagnostics.LogEntry;
import com.wuwa.config.manager.model.GameServer;
import com.wuwa.config.manager.model.QualityPreset;
import com.wuwa.config.manager.privilege.PrivilegeManager;
import com.wuwa.config.manager.privilege.PrivilegeState;
import com.wuwa.config.manager.ui.AdaptiveNestedScrollView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public final class MainActivity extends AppCompatActivity implements PrivilegeManager.Listener {
    private static final String TAG = "WuwaRemoteNotice";
    private static final String PREFS = "wuwa_config_preferences";
    private static final String KEY_SELECTED_PACKAGE = "selected_package";
    private static final String KEY_SELECTED_PRESET = "selected_preset";
    private static final String KEY_APPEARANCE = "appearance_mode";
    private static final String KEY_DYNAMIC_COLORS = "dynamic_colors_enabled";
    private static final String KEY_FIRST_RUN_NOTICE_ACCEPTED = "first_run_notice_accepted_v3";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_LAST_ANNOUNCEMENT_ID = "last_announcement_id";
    private static final String KEY_LAST_DAILY_ANNOUNCEMENT_ID = "last_daily_announcement_id";
    private static final String KEY_LAST_DAILY_ANNOUNCEMENT_DATE = "last_daily_announcement_date";
    private static final String KEY_IGNORED_UPDATE_VERSION_CODE = "ignored_update_version_code";
    private static final int REMOTE_MAX_RETRIES = 1;
    private static final long REMOTE_RETRY_DELAY_MS = 3_000L;
    private static final long REMOTE_LOCK_CACHE_MAX_AGE_MS = 6L * 60L * 60L * 1_000L;
    private static final String APPEARANCE_SYSTEM = "system";
    private static final String APPEARANCE_LIGHT = "light";
    private static final String APPEARANCE_DARK = "dark";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wuwa-config-worker");
        thread.setDaemon(true);
        return thread;
    });

    private PrivilegeManager privilegeManager;
    private ConfigRepository repository;
    private RemoteNoticeRepository remoteNoticeRepository;
    private SharedPreferences preferences;
    private List<GameServer> installedServers = new ArrayList<>();
    private final Set<String> announcementsShownThisSession = new HashSet<>();
    private GameServer selectedServer;
    private QualityPreset selectedPreset = QualityPreset.MEDIUM;
    private PrivilegeState privilegeState = new PrivilegeState(
            true, false, PrivilegeState.ShizukuStatus.NOT_RUNNING, null);
    private boolean presetsReady;
    private boolean presetsChecked;
    private boolean operationRunning;
    private boolean destroyed;
    private int backupQueryGeneration;
    private int processQueryGeneration;
    private int loadMonitorGeneration;
    private boolean activityResumed;
    private boolean rotationRecoveryRunning;
    private boolean emergencyRotationRecoveryRequested;
    private MaterialButton firstRunRecoveryButton;
    private long previousCpuTotal = -1L;
    private long previousCpuIdle = -1L;
    private long previousGpuActive = -1L;
    private long previousGpuTotal = -1L;
    private Dialog progressDialog;
    private CountDownTimer firstRunTimer;
    private String rootProviderName;
    private boolean rootProviderLookupRunning;
    private boolean remoteCheckRunning;
    private RemoteNoticePayload lastRemotePayload;
    private RemoteNoticeRepository.FetchResult lastRemoteFetchResult;
    private AlertDialog mandatoryUpdateDialog;

    private TextView targetName;
    private TextView targetState;
    private TextView configTargetName;
    private TextView configTargetState;
    private TextView backupTargetName;
    private TextView backupTargetState;
    private MaterialCardView permissionWarningCard;
    private TextView permissionWarningText;
    private MaterialButton permissionRefreshButton;
    private MaterialButton permissionWarningRefreshButton;
    private MaterialButton shizukuReconnectButton;
    private ImageView permissionModeIcon;
    private TextView permissionModeStatus;
    private TextView backupStatus;
    private TextView presetStatus;
    private MaterialButtonToggleGroup presetToggleGroup;
    private TextView presetSelectionDetail;
    private MaterialButton replaceButton;
    private MaterialButton portraitLaunchButton;
    private MaterialButton restoreRotationButton;
    private TextView rotationStatus;
    private TextView presetCardTitle;
    private TextView deviceModel;
    private TextView deviceProcessor;
    private TextView deviceGpu;
    private TextView deviceMemory;
    private TextView deviceSystem;
    private TextView deviceSystemDetail;
    private TextView cpuUsageValue;
    private TextView gpuUsageValue;
    private TextView memoryUsageValue;
    private CircularProgressIndicator cpuUsageProgress;
    private CircularProgressIndicator gpuUsageProgress;
    private LinearProgressIndicator memoryUsageProgress;
    private TextView gameVersionStatus;
    private TextView gameProcessStatus;
    private View homeServerChinaRow;
    private View homeServerBilibiliRow;
    private View homeServerGlobalRow;
    private TextView homeServerChinaVersion;
    private TextView homeServerBilibiliVersion;
    private TextView homeServerGlobalVersion;
    private TextView homeServerChinaStatus;
    private TextView homeServerBilibiliStatus;
    private TextView homeServerGlobalStatus;
    private TextView homeServerChinaCurrent;
    private TextView homeServerBilibiliCurrent;
    private TextView homeServerGlobalCurrent;
    private TextView softwareVersionStatus;
    private TextView homeRotationStatus;
    private MaterialButton manualBackupButton;
    private MaterialButton restoreButton;
    private MaterialButton manageButton;
    private MaterialButton changeStorageButton;
    private MaterialButton openStorageButton;
    private TextView backupLibrarySummary;
    private TextView backupRootPath;
    private TextView automaticBackupRetentionValue;
    private MaterialButtonToggleGroup appearanceToggleGroup;
    private MaterialButtonToggleGroup languageToggleGroup;
    private MaterialSwitch dynamicColorSwitch;
    private TextView permissionStatus;
    private TextView settingsPermissionDetail;
    private TextView announcementStatus;
    private TextView settingsUpdateStatus;
    private TextView logManagerStatus;
    private View homePage;
    private View configPage;
    private View backupPage;
    private View settingsPage;
    private View licensesPage;
    private View currentPage;
    private NavigationBarView bottomNavigation;
    private ActivityResultLauncher<Intent> storageFolderPicker;
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<String> legacyLogPermissionLauncher;
    private GameServer pendingOverlayServer;
    private boolean storageFolderSelectionChangesLocation;
    private View firstRunOverlay;
    private View rotationRecoveryOverlay;
    private TextView rotationRecoveryStatus;
    private View securityModeOverlay;
    private boolean automaticRemoteCheckPerformed;
    private boolean cloudSecurityModeActive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        applyLanguageBeforeCreate();
        migrateAppearancePreferences();
        applyAppearanceBeforeCreate();
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (firstRunOverlay != null || securityModeOverlay != null
                        || rotationRecoveryOverlay != null) return;
                if (licensesPage != null && currentPage == licensesPage) {
                    closeLicensesPage();
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
            }
        });
        storageFolderPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        storageFolderSelectionChangesLocation = false;
                        return;
                    }
                    Uri treeUri = result.getData().getData();
                    if (treeUri != null && storageFolderSelectionChangesLocation) {
                        handleSelectedStorageFolder(treeUri);
                    }
                    storageFolderSelectionChangesLocation = false;
                });
        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    GameServer server = pendingOverlayServer;
                    pendingOverlayServer = null;
                    if (server == null) return;
                    if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
                        startFloatingRotationController(server);
                    } else {
                        showError(getString(R.string.overlay_permission_denied));
                    }
                });
        legacyLogPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        AppLogger.tryEnablePublicLogging();
                        AppLogger.event("Permission", "已授予旧版系统日志存储权限");
                    } else {
                        AppLogger.warn("Permission", "用户拒绝旧版系统日志存储权限");
                        Toast.makeText(this, R.string.log_permission_denied,
                                Toast.LENGTH_LONG).show();
                    }
                    openLogManager();
                });
        if (isDynamicColorEnabled() && DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivityIfAvailable(this);
        }
        setContentView(R.layout.activity_main);
        configureSystemBars();
        bindViews();
        AppLogger.info("Activity", "MainActivity 已创建");

        initializeAppearanceSelector();
        initializePresetSelector();
        remoteNoticeRepository = new RemoteNoticeRepository(
                this,
                getString(R.string.remote_notice_endpoint),
                getString(R.string.remote_notice_fallback_endpoint));
        privilegeManager = new PrivilegeManager(this);
        repository = new ConfigRepository(this, privilegeManager);
        installedServers = detectInstalledServers();
        chooseInitialServer();
        privilegeManager.setListener(this);
        preparePresetsAsync();
        refreshUi();
        showRotationRecoveryPageIfNeeded();
        mainHandler.post(this::showFirstRunNoticeIfNeeded);
        mainHandler.post(() -> checkRemoteUpdates(false));
    }

    private void showRotationRecoveryPageIfNeeded() {
        if (repository == null || rotationRecoveryOverlay != null
                || !repository.hasPrivateRotationRecoveryState()) return;
        FrameLayout activityContent = findViewById(android.R.id.content);
        View content = getLayoutInflater().inflate(
                R.layout.overlay_rotation_recovery, activityContent, false);
        rotationRecoveryOverlay = content;
        rotationRecoveryStatus = content.findViewById(R.id.rotation_recovery_status);
        bottomNavigation.setVisibility(View.GONE);
        activityContent.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
        renderRotationRecoverySnapshot(content);
        content.findViewById(R.id.rotation_recovery_retry)
                .setOnClickListener(view -> requestPendingRotationRecovery());
        requestPendingRotationRecovery();
    }

    private void renderRotationRecoverySnapshot(View content) {
        ConfigRepository.RotationRecoveryInfo info =
                repository.getPrivateRotationRecoveryInfo();
        if (info == null) return;
        boolean landscape = info.currentRotation == Surface.ROTATION_90
                || info.currentRotation == Surface.ROTATION_270;
        TextView snapshot = content.findViewById(R.id.rotation_recovery_snapshot);
        snapshot.setText(getString(
                R.string.rotation_recovery_snapshot,
                getString(info.automaticRotationEnabled
                        ? R.string.rotation_recovery_auto_on
                        : R.string.rotation_recovery_auto_off),
                info.userRotation,
                getString(landscape
                        ? R.string.rotation_recovery_landscape
                        : R.string.rotation_recovery_portrait),
                rotationPhaseLabel(info.phase)));
    }

    private String rotationPhaseLabel(String phase) {
        if (ConfigRepository.ROTATION_PHASE_PREPARING.equals(phase)) {
            return getString(R.string.rotation_recovery_phase_preparing);
        }
        if (ConfigRepository.ROTATION_PHASE_RESTORING.equals(phase)) {
            return getString(R.string.rotation_recovery_phase_restoring);
        }
        return getString(R.string.rotation_recovery_phase_active);
    }

    private void requestPendingRotationRecovery() {
        if (rotationRecoveryOverlay == null) return;
        rotationRecoveryStatus.setText(R.string.rotation_recovery_working);
        try {
            RotationControllerService.requestRecovery(this);
        } catch (RuntimeException error) {
            rotationRecoveryStatus.setText(R.string.rotation_recovery_waiting_permission);
        }
        mainHandler.removeCallbacks(rotationRecoveryPoll);
        mainHandler.postDelayed(rotationRecoveryPoll, 700L);
    }

    private final Runnable rotationRecoveryPoll = new Runnable() {
        @Override
        public void run() {
            if (destroyed || rotationRecoveryOverlay == null || repository == null) return;
            if (!repository.hasPrivateRotationRecoveryState()) {
                rotationRecoveryStatus.setText(R.string.rotation_recovery_complete);
                mainHandler.postDelayed(() -> {
                    if (rotationRecoveryOverlay == null) return;
                    FrameLayout activityContent = findViewById(android.R.id.content);
                    activityContent.removeView(rotationRecoveryOverlay);
                    rotationRecoveryOverlay = null;
                    rotationRecoveryStatus = null;
                    if (securityModeOverlay == null && firstRunOverlay == null) {
                        bottomNavigation.setVisibility(View.VISIBLE);
                    }
                    showFirstRunNoticeIfNeeded();
                }, 450L);
                return;
            }
            renderRotationRecoverySnapshot(rotationRecoveryOverlay);
            if (!RotationControllerService.isRunning()) {
                rotationRecoveryStatus.setText(R.string.rotation_recovery_waiting_permission);
            }
            mainHandler.postDelayed(this, 900L);
        }
    };

    private void showFirstRunNoticeIfNeeded() {
        if (destroyed || firstRunOverlay != null || securityModeOverlay != null
                || rotationRecoveryOverlay != null
                || preferences.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) return;

        FrameLayout activityContent = findViewById(android.R.id.content);
        View content = getLayoutInflater().inflate(
                R.layout.dialog_first_run_overlay, activityContent, false);
        firstRunOverlay = content;
        bottomNavigation.setVisibility(View.GONE);
        activityContent.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            androidx.core.graphics.Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            boolean landscape = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            int horizontalInset = dp(landscape ? 28 : 16);
            int verticalInset = dp(landscape ? 12 : 24);
            view.setPadding(
                    horizontalInset + bars.left,
                    verticalInset + bars.top,
                    horizontalInset + bars.right,
                    verticalInset + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(content);
        content.bringToFront();
        content.setElevation(getResources().getDisplayMetrics().density * 48f);
        TextView pageLabel = content.findViewById(R.id.first_run_page_label);
        TextView pageTitle = content.findViewById(R.id.first_run_page_title);
        TextView agreementInstruction = content.findViewById(
                R.id.first_run_agreement_instruction);
        LinearLayout statementPage = content.findViewById(R.id.first_run_statement_page);
        LinearLayout guidePage = content.findViewById(R.id.first_run_guide_page);
        AdaptiveNestedScrollView noticeScroll = content.findViewById(
                R.id.first_run_notice_scroll);
        MaterialCardView dialogCard = content.findViewById(R.id.first_run_dialog_card);
        LinearLayout noticePanel = content.findViewById(R.id.first_run_notice_panel);
        boolean landscapeDialog = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        if (landscapeDialog) {
            ViewGroup.LayoutParams cardParams = dialogCard.getLayoutParams();
            cardParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            dialogCard.setLayoutParams(cardParams);
            ViewGroup.LayoutParams panelParams = noticePanel.getLayoutParams();
            panelParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            noticePanel.setLayoutParams(panelParams);
            LinearLayout.LayoutParams scrollParams =
                    (LinearLayout.LayoutParams) noticeScroll.getLayoutParams();
            scrollParams.height = 0;
            scrollParams.weight = 1f;
            noticeScroll.setLayoutParams(scrollParams);
        } else {
            int displayHeight = getResources().getDisplayMetrics().heightPixels;
            int adaptiveMaximum = Math.max(
                    dp(100), Math.min(dp(360), displayHeight - dp(430)));
            noticeScroll.setMaxHeightPx(adaptiveMaximum);
        }
        TextView countdownHint = content.findViewById(R.id.first_run_countdown_hint);
        LinearProgressIndicator progress = content.findViewById(R.id.first_run_progress);
        TextInputLayout agreementLayout = content.findViewById(R.id.first_run_agreement_layout);
        TextInputEditText agreementInput = content.findViewById(R.id.first_run_agreement_input);
        MaterialButton reject = content.findViewById(R.id.first_run_reject_button);
        MaterialButton accept = content.findViewById(R.id.first_run_accept_button);
        firstRunRecoveryButton = content.findViewById(
                R.id.first_run_restore_rotation_button);
        firstRunRecoveryButton.setOnClickListener(
                view -> requestEmergencyRotationRecovery());
        final boolean[] showingGuidePage = {false};
        final int[] guideAnswer = {0};
        final int rejectColor = reject.getCurrentTextColor();
        final int primaryColor = MaterialColors.getColor(
                reject, android.R.attr.colorAccent);
        accept.setEnabled(false);
        accept.setText(getString(R.string.first_run_countdown_button, 30));
        firstRunTimer = new CountDownTimer(30_000L, 1_000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                long seconds = Math.max(1L, (millisUntilFinished + 999L) / 1_000L);
                countdownHint.setText(getString(
                        R.string.first_run_countdown_remaining, seconds));
                accept.setText(getString(R.string.first_run_countdown_button, seconds));
                progress.setProgressCompat((int) (30L - seconds), true);
            }

            @Override
            public void onFinish() {
                agreementInput.setEnabled(true);
                agreementInput.setFocusableInTouchMode(true);
                progress.setProgressCompat(30, true);
                countdownHint.setText(R.string.first_run_countdown_complete);
                accept.setText(R.string.first_run_accept);
                accept.setEnabled(true);
            }
        }.start();
        reject.setOnClickListener(view -> {
            if (showingGuidePage[0]) {
                showingGuidePage[0] = false;
                pageLabel.setText(R.string.first_run_page_one_label);
                pageTitle.setText(R.string.first_run_statement_title);
                statementPage.setVisibility(View.VISIBLE);
                guidePage.setVisibility(View.GONE);
                countdownHint.setVisibility(View.VISIBLE);
                progress.setVisibility(View.VISIBLE);
                agreementInstruction.setText(R.string.first_run_agreement_instruction);
                agreementLayout.setHint(R.string.first_run_agreement_hint);
                agreementLayout.setError(null);
                agreementInput.setText(null);
                agreementInput.setInputType(InputType.TYPE_CLASS_TEXT);
                reject.setText(R.string.first_run_reject);
                reject.setTextColor(rejectColor);
                reject.setStrokeColor(ColorStateList.valueOf(rejectColor));
                accept.setText(R.string.first_run_accept);
                noticeScroll.scrollTo(0, 0);
                return;
            }
            if (firstRunTimer != null) firstRunTimer.cancel();
            firstRunTimer = null;
            finishAndRemoveTask();
        });
        accept.setOnClickListener(view -> {
            String value = agreementInput.getText() == null
                    ? "" : agreementInput.getText().toString().trim();
            if (showingGuidePage[0]) {
                if (!String.valueOf(guideAnswer[0]).equals(value)) {
                    agreementLayout.setError(getString(R.string.first_run_guide_required));
                    return;
                }
            } else if (!getString(R.string.first_run_agreement_exact).equals(value)) {
                agreementLayout.setError(getString(R.string.first_run_agreement_required));
                return;
            }
            agreementLayout.setError(null);
            if (!showingGuidePage[0]) {
                showingGuidePage[0] = true;
                pageLabel.setText(R.string.first_run_page_two_label);
                pageTitle.setText(R.string.first_run_notice_title);
                statementPage.setVisibility(View.GONE);
                guidePage.setVisibility(View.VISIBLE);
                countdownHint.setVisibility(View.GONE);
                progress.setVisibility(View.GONE);
                boolean addition = ThreadLocalRandom.current().nextBoolean();
                int first;
                int second;
                String operator;
                if (addition) {
                    first = ThreadLocalRandom.current().nextInt(1, 21);
                    second = ThreadLocalRandom.current().nextInt(1, 21);
                    guideAnswer[0] = first + second;
                    operator = "+";
                } else {
                    first = ThreadLocalRandom.current().nextInt(2, 31);
                    second = ThreadLocalRandom.current().nextInt(1, first + 1);
                    guideAnswer[0] = first - second;
                    operator = "−";
                }
                agreementInstruction.setText(getString(
                        R.string.first_run_guide_instruction, first, operator, second));
                agreementLayout.setHint(R.string.first_run_math_answer_hint);
                agreementInput.setText(null);
                agreementInput.setEnabled(true);
                agreementInput.setInputType(InputType.TYPE_CLASS_NUMBER);
                reject.setText(R.string.first_run_back);
                reject.setTextColor(primaryColor);
                reject.setStrokeColor(ColorStateList.valueOf(primaryColor));
                accept.setText(R.string.first_run_guide_accept);
                noticeScroll.scrollTo(0, 0);
                agreementInput.requestFocus();
                return;
            }
            preferences.edit().putBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, true).apply();
            if (firstRunTimer != null) firstRunTimer.cancel();
            firstRunTimer = null;
            InputMethodManager inputMethodManager =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(content.getWindowToken(), 0);
            }
            activityContent.removeView(content);
            firstRunOverlay = null;
            firstRunRecoveryButton = null;
            bottomNavigation.setVisibility(View.VISIBLE);
            privilegeManager.refresh();
            checkRemoteUpdates(false);
            if (installedServers.size() > 1) mainHandler.post(this::showServerChooser);
        });
        agreementInput.setOnEditorActionListener((view, actionId, event) -> {
            if (!accept.isEnabled()) return false;
            accept.performClick();
            return true;
        });
    }

    private void showCloudSecurityMode(
            RemoteNoticePayload.SecurityMode securityMode, String revision) {
        cloudSecurityModeActive = true;
        if (RotationControllerService.isRunning()) RotationControllerService.stop(this);
        String level = securityModeLevelLabel(securityMode.getLevel());
        String source = TextUtils.isEmpty(revision) ? level : level + " · " + revision;
        String meta = getString(R.string.security_mode_cloud_meta,
                source, formatNoticeTimestamp(securityMode.getExpiresAt()));
        showSecurityModeOverlay(
                TextUtils.isEmpty(securityMode.getTitle())
                        ? getString(R.string.security_mode_default_title)
                        : securityMode.getTitle(),
                meta,
                TextUtils.isEmpty(securityMode.getContent())
                        ? getString(R.string.security_mode_default_content)
                        : securityMode.getContent(),
                securityMode.getDownloadUrl(),
                securityMode.isLogExportAllowed(),
                securityMode.isRotationRecoveryAllowed(),
                true);
    }

    private static final DateTimeFormatter NOTICE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
                    .withZone(ZoneId.systemDefault());

    /** Formats ISO-8601 notice timestamps for display; falls back to the raw value. */
    private String formatNoticeTimestamp(String raw) {
        if (TextUtils.isEmpty(raw)) return raw;
        String value = raw.trim();
        try {
            return NOTICE_TIME_FORMATTER.format(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            try {
                return NOTICE_TIME_FORMATTER.format(
                        OffsetDateTime.parse(value).toInstant());
            } catch (DateTimeParseException invalid) {
                return raw;
            }
        }
    }
    private String securityModeLevelLabel(String level) {
        if ("emergency".equalsIgnoreCase(level)) {
            return getString(R.string.security_mode_level_emergency);
        }
        if ("maintenance".equalsIgnoreCase(level)) {
            return getString(R.string.security_mode_level_maintenance);
        }
        return getString(R.string.security_mode_level_important);
    }

    private void showSecurityModeOverlay(
            CharSequence title,
            CharSequence meta,
            CharSequence body,
            String downloadUrl,
            boolean allowLogExport,
            boolean allowRotationRecovery,
            boolean allowRecheck) {
        FrameLayout activityContent = findViewById(android.R.id.content);
        if (securityModeOverlay != null) activityContent.removeView(securityModeOverlay);
        View content = getLayoutInflater().inflate(
                R.layout.overlay_security_mode, activityContent, false);
        securityModeOverlay = content;
        if (bottomNavigation != null) bottomNavigation.setVisibility(View.GONE);
        activityContent.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            androidx.core.graphics.Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int horizontal = dp(getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE ? 32 : 24);
            view.setPadding(horizontal + bars.left, dp(24) + bars.top,
                    horizontal + bars.right, dp(20) + bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(content);
        content.bringToFront();
        content.setElevation(dp(64));

        ((TextView) content.findViewById(R.id.security_mode_badge)).setText(
                R.string.security_mode_badge);
        ((TextView) content.findViewById(R.id.security_mode_title)).setText(title);
        ((TextView) content.findViewById(R.id.security_mode_meta)).setText(meta);
        ((TextView) content.findViewById(R.id.security_mode_content)).setText(body);

        MaterialButton primary = content.findViewById(R.id.security_mode_primary_button);
        MaterialButton download = content.findViewById(R.id.security_mode_download_button);
        MaterialButton logs = content.findViewById(R.id.security_mode_log_button);
        MaterialButton rotation = content.findViewById(R.id.security_mode_rotation_button);
        MaterialButton exit = content.findViewById(R.id.security_mode_exit_button);

        String safeDownload = isSafeHttpsUrl(downloadUrl)
                ? downloadUrl : getString(R.string.official_download_url);
        primary.setVisibility(allowRecheck ? View.VISIBLE : View.GONE);
        primary.setOnClickListener(view -> requestRemoteManifest(true, false));
        download.setVisibility(isSafeHttpsUrl(safeDownload) ? View.VISIBLE : View.GONE);
        download.setOnClickListener(view -> openExternalLink(safeDownload, false));
        logs.setVisibility(allowLogExport ? View.VISIBLE : View.GONE);
        logs.setOnClickListener(view -> {
            Uri log = AppLogger.getCurrentShareUri(this);
            if (log == null) {
                Toast.makeText(this, R.string.log_share_failed, Toast.LENGTH_LONG).show();
            } else {
                shareLogs(java.util.Collections.singletonList(log));
            }
        });
        rotation.setVisibility(allowRotationRecovery && repository != null
                ? View.VISIBLE : View.GONE);
        rotation.setOnClickListener(view -> requestEmergencyRotationRecovery());
        exit.setOnClickListener(view -> finishAndRemoveTask());
    }

    private void dismissCloudSecurityMode() {
        if (!cloudSecurityModeActive || securityModeOverlay == null) return;
        FrameLayout activityContent = findViewById(android.R.id.content);
        activityContent.removeView(securityModeOverlay);
        securityModeOverlay = null;
        cloudSecurityModeActive = false;
        if (firstRunOverlay == null && bottomNavigation != null) {
            bottomNavigation.setVisibility(View.VISIBLE);
        }
        showFirstRunNoticeIfNeeded();
    }

    private void recoverInterruptedRotationSession() {
        if (rotationRecoveryRunning || RotationControllerService.isRunning()
                || !privilegeState.isReady()) return;
        rotationRecoveryRunning = true;
        worker.execute(() -> {
            try {
                repository.recoverInterruptedRotationSessionIfNeeded();
            } catch (Exception exactRestoreError) {
                try {
                    repository.restoreAutomaticRotation();
                } catch (Exception ignoredAgain) {}
            }
            mainHandler.post(() -> rotationRecoveryRunning = false);
        });
    }

    private void requestEmergencyRotationRecovery() {
        if (rotationRecoveryRunning || emergencyRotationRecoveryRequested) return;
        emergencyRotationRecoveryRequested = true;
        if (firstRunRecoveryButton != null) {
            firstRunRecoveryButton.setEnabled(false);
            firstRunRecoveryButton.setText(R.string.first_run_restore_rotation_connecting);
        }
        if (privilegeState.isReady()) {
            performEmergencyRotationRecovery();
        } else {
            privilegeManager.requestAuthorization();
        }
    }

    private void performEmergencyRotationRecovery() {
        if (rotationRecoveryRunning || !emergencyRotationRecoveryRequested) return;
        rotationRecoveryRunning = true;
        worker.execute(() -> {
            boolean success = false;
            try {
                boolean exactStateFound = repository.recoverInterruptedRotationSessionIfNeeded();
                if (!exactStateFound) repository.restoreAutomaticRotation();
                success = true;
            } catch (Exception exactError) {
                try {
                    repository.restoreAutomaticRotation();
                    success = true;
                } catch (Exception ignored) {}
            }
            boolean restored = success;
            mainHandler.post(() -> {
                rotationRecoveryRunning = false;
                emergencyRotationRecoveryRequested = false;
                if (firstRunRecoveryButton != null) {
                    firstRunRecoveryButton.setEnabled(true);
                    firstRunRecoveryButton.setText(R.string.first_run_restore_rotation);
                }
                Toast.makeText(
                        this,
                        restored ? R.string.first_run_restore_rotation_success
                                : R.string.first_run_restore_rotation_failed,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void bindViews() {
        targetName = findViewById(R.id.target_name);
        targetState = findViewById(R.id.target_state);
        configTargetName = findViewById(R.id.config_target_name);
        configTargetState = findViewById(R.id.config_target_state);
        backupTargetName = findViewById(R.id.backup_target_name);
        backupTargetState = findViewById(R.id.backup_target_state);
        permissionWarningCard = findViewById(R.id.permission_warning_card);
        permissionWarningText = findViewById(R.id.permission_warning_text);
        permissionRefreshButton = findViewById(R.id.permission_refresh_button);
        permissionWarningRefreshButton = findViewById(R.id.permission_warning_refresh_button);
        shizukuReconnectButton = findViewById(R.id.shizuku_reconnect_button);
        permissionModeIcon = findViewById(R.id.permission_mode_icon);
        permissionModeStatus = findViewById(R.id.permission_mode_status);
        backupStatus = findViewById(R.id.backup_status);
        presetStatus = findViewById(R.id.preset_status);
        presetToggleGroup = findViewById(R.id.preset_toggle_group);
        presetSelectionDetail = findViewById(R.id.preset_selection_detail);
        replaceButton = findViewById(R.id.replace_button);
        portraitLaunchButton = findViewById(R.id.portrait_launch_button);
        restoreRotationButton = findViewById(R.id.restore_rotation_button);
        rotationStatus = findViewById(R.id.rotation_status);
        presetCardTitle = findViewById(R.id.preset_card_title);
        deviceModel = findViewById(R.id.device_model);
        deviceProcessor = findViewById(R.id.device_processor);
        deviceGpu = findViewById(R.id.device_gpu);
        deviceMemory = findViewById(R.id.device_memory);
        deviceSystem = findViewById(R.id.device_system);
        deviceSystemDetail = findViewById(R.id.device_system_detail);
        cpuUsageValue = findViewById(R.id.cpu_usage_value);
        gpuUsageValue = findViewById(R.id.gpu_usage_value);
        memoryUsageValue = findViewById(R.id.memory_usage_value);
        cpuUsageProgress = findViewById(R.id.cpu_usage_progress);
        gpuUsageProgress = findViewById(R.id.gpu_usage_progress);
        memoryUsageProgress = findViewById(R.id.memory_usage_progress);
        gameVersionStatus = findViewById(R.id.game_version_status);
        gameProcessStatus = findViewById(R.id.game_process_status);
        homeServerChinaRow = findViewById(R.id.home_server_china_row);
        homeServerBilibiliRow = findViewById(R.id.home_server_bilibili_row);
        homeServerGlobalRow = findViewById(R.id.home_server_global_row);
        homeServerChinaVersion = findViewById(R.id.home_server_china_version);
        homeServerBilibiliVersion = findViewById(R.id.home_server_bilibili_version);
        homeServerGlobalVersion = findViewById(R.id.home_server_global_version);
        homeServerChinaStatus = findViewById(R.id.home_server_china_status);
        homeServerBilibiliStatus = findViewById(R.id.home_server_bilibili_status);
        homeServerGlobalStatus = findViewById(R.id.home_server_global_status);
        homeServerChinaCurrent = findViewById(R.id.home_server_china_current);
        homeServerBilibiliCurrent = findViewById(R.id.home_server_bilibili_current);
        homeServerGlobalCurrent = findViewById(R.id.home_server_global_current);
        softwareVersionStatus = findViewById(R.id.software_version_status);
        homeRotationStatus = findViewById(R.id.home_rotation_status);
        manualBackupButton = findViewById(R.id.manual_backup_button);
        restoreButton = findViewById(R.id.restore_button);
        manageButton = findViewById(R.id.manage_backup_button);
        changeStorageButton = findViewById(R.id.change_storage_button);
        openStorageButton = findViewById(R.id.open_storage_button);
        backupLibrarySummary = findViewById(R.id.backup_library_summary);
        backupRootPath = findViewById(R.id.backup_root_path);
        automaticBackupRetentionValue = findViewById(R.id.automatic_backup_retention_value);
        appearanceToggleGroup = findViewById(R.id.appearance_toggle_group);
        languageToggleGroup = findViewById(R.id.language_toggle_group);
        dynamicColorSwitch = findViewById(R.id.dynamic_color_switch);
        permissionStatus = findViewById(R.id.permission_status);
        settingsPermissionDetail = findViewById(R.id.settings_permission_detail);
        announcementStatus = findViewById(R.id.announcement_status);
        settingsUpdateStatus = findViewById(R.id.settings_update_status);
        logManagerStatus = findViewById(R.id.log_manager_status);
        homePage = findViewById(R.id.home_page);
        configPage = findViewById(R.id.config_page);
        backupPage = findViewById(R.id.backup_page);
        settingsPage = findViewById(R.id.settings_page);
        licensesPage = findViewById(R.id.licenses_page);
        bottomNavigation = findViewById(R.id.bottom_navigation);

        TextView settingsAppIdentity = findViewById(R.id.settings_app_identity);
        settingsAppIdentity.setText(getString(
                R.string.settings_app_identity_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE));
        settingsUpdateStatus.setText(getString(
                R.string.current_version_click_check, BuildConfig.VERSION_NAME));
        populateOpenSourceLicenses();

        homeServerChinaRow.setOnClickListener(
                view -> onHomeServerClicked(GameServer.CHINA_OFFICIAL));
        homeServerBilibiliRow.setOnClickListener(
                view -> onHomeServerClicked(GameServer.CHINA_BILIBILI));
        homeServerGlobalRow.setOnClickListener(
                view -> onHomeServerClicked(GameServer.GLOBAL));
        findViewById(R.id.software_version_item)
                .setOnClickListener(view -> checkRemoteUpdates(true));
        findViewById(R.id.config_target_card).setOnClickListener(view -> showServerChooser());
        findViewById(R.id.backup_target_card).setOnClickListener(view -> showServerChooser());
        permissionRefreshButton.setOnClickListener(view -> privilegeManager.requestAuthorization());
        permissionWarningRefreshButton.setOnClickListener(
                view -> privilegeManager.requestAuthorization());
        shizukuReconnectButton.setOnClickListener(view -> privilegeManager.reconnectShizuku());
        replaceButton.setOnClickListener(view -> runReplace());
        portraitLaunchButton.setOnClickListener(view -> startPortraitLaunch());
        restoreRotationButton.setOnClickListener(view -> restoreAutomaticRotation());
        manualBackupButton.setOnClickListener(view -> runManualBackup());
        restoreButton.setOnClickListener(view -> openRestoreCenter());
        manageButton.setOnClickListener(view -> openManualBackupManager());
        findViewById(R.id.automatic_backup_retention_card)
                .setOnClickListener(view -> showAutomaticBackupRetentionChooser());
        changeStorageButton.setOnClickListener(view -> showChangeStorageDialog());
        openStorageButton.setOnClickListener(view -> openBackupFolder());
        findViewById(R.id.log_manager_entry).setOnClickListener(
                view -> openLogManagerWithPermission());
        findViewById(R.id.announcement_entry)
                .setOnClickListener(view -> openAnnouncementCenter());
        findViewById(R.id.update_check_entry)
                .setOnClickListener(view -> checkRemoteUpdates(true));
        findViewById(R.id.software_description_entry).setOnClickListener(view ->
                showPanelDialog(
                        getString(R.string.software_description_title),
                        getString(R.string.software_description_body),
                        DialogTone.INFO, 0, null, R.string.done, null, false));
        findViewById(R.id.author_entry).setOnClickListener(view ->
                openConfiguredLink(getString(R.string.support_bilibili_url)));
        findViewById(R.id.qq_group_entry).setOnClickListener(view ->
                openConfiguredLink(getString(R.string.support_qq_group_url)));
        findViewById(R.id.open_source_entry).setOnClickListener(view -> showLicensesPage());
        findViewById(R.id.licenses_back_button).setOnClickListener(view -> closeLicensesPage());
        findViewById(R.id.source_code_entry).setOnClickListener(view ->
                openConfiguredLink(getString(R.string.source_repository_url)));
        bottomNavigation.setOnItemSelectedListener(item -> {
            showPage(item.getItemId());
            return true;
        });
        bottomNavigation.setSelectedItemId(R.id.navigation_home);
        bindDeviceInformation();
        initializeLanguageSelector();
    }

    private void showPage(int itemId) {
        View targetPage;
        if (itemId == R.id.navigation_config) targetPage = configPage;
        else if (itemId == R.id.navigation_backup) targetPage = backupPage;
        else if (itemId == R.id.navigation_settings) targetPage = settingsPage;
        else targetPage = homePage;

        bottomNavigation.setVisibility(View.VISIBLE);
        switchPage(targetPage);
        if (itemId == R.id.navigation_home) startSystemLoadMonitor();
        else stopSystemLoadMonitor();
    }

    private void showLicensesPage() {
        stopSystemLoadMonitor();
        bottomNavigation.setVisibility(View.GONE);
        switchPage(licensesPage);
    }

    private void closeLicensesPage() {
        switchPage(settingsPage);
        bottomNavigation.setVisibility(View.VISIBLE);
        if (bottomNavigation.getSelectedItemId() != R.id.navigation_settings) {
            bottomNavigation.setSelectedItemId(R.id.navigation_settings);
        }
    }

    private void populateOpenSourceLicenses() {
        LinearLayout container = findViewById(R.id.licenses_container);
        if (container == null) return;
        container.removeAllViews();

        String[][] licenses = {
                {"AndroidX AppCompat", "1.7.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/appcompat"},
                {"AndroidX Activity", "1.8.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/activity"},
                {"AndroidX Annotation", "1.8.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/annotation"},
                {"AndroidX Arch Core", "2.2.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/arch-core"},
                {"AndroidX CardView", "1.0.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/cardview"},
                {"AndroidX Collection", "1.4.2", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/collection"},
                {"AndroidX Concurrent Futures", "1.1.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/concurrent"},
                {"AndroidX ConstraintLayout", "2.2.1", "The Android Open Source Project", "Apache License 2.0", "https://github.com/androidx/constraintlayout"},
                {"AndroidX Core", "1.16.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/core"},
                {"AndroidX CustomView", "1.2.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/customview"},
                {"AndroidX DrawerLayout", "1.1.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/drawerlayout"},
                {"AndroidX Emoji2", "1.3.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/emoji2"},
                {"AndroidX Fragment", "1.5.4", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/fragment"},
                {"AndroidX Graphics Shapes", "1.0.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/graphics"},
                {"AndroidX Lifecycle", "2.6.2", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/lifecycle"},
                {"AndroidX ProfileInstaller", "1.4.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/profileinstaller"},
                {"AndroidX RecyclerView", "1.2.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/recyclerview"},
                {"AndroidX SavedState", "1.2.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/savedstate"},
                {"AndroidX Startup", "1.1.1", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/startup"},
                {"AndroidX Transition", "1.5.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/transition"},
                {"AndroidX VectorDrawable", "1.1.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/vectordrawable"},
                {"AndroidX ViewPager", "1.0.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/viewpager"},
                {"AndroidX ViewPager2", "1.0.0", "The Android Open Source Project", "Apache License 2.0", "https://developer.android.com/jetpack/androidx/releases/viewpager2"},
                {"Material Components for Android", "1.14.0", "Google", "Apache License 2.0", "https://github.com/material-components/material-components-android"},
                {"Shizuku API & Provider", "13.1.5", "RikkaApps", "MIT License", "https://github.com/RikkaApps/Shizuku-API"},
                {"Kotlin Standard Library", "1.8.22", "JetBrains", "Apache License 2.0", "https://github.com/JetBrains/kotlin"},
                {"kotlinx.coroutines", "1.6.4", "JetBrains", "Apache License 2.0", "https://github.com/Kotlin/kotlinx.coroutines"},
                {"Guava ListenableFuture", "1.0", "Google", "Apache License 2.0", "https://github.com/google/guava"},
                {"Error Prone Annotations", "2.15.0", "Google", "Apache License 2.0", "https://github.com/google/error-prone"},
                {"JSpecify", "1.0.0", "JSpecify", "Apache License 2.0", "https://github.com/jspecify/jspecify"},
                {"JetBrains Annotations", "13.0", "JetBrains", "Apache License 2.0", "https://github.com/JetBrains/java-annotations"},
                {"desugar_jdk_libs", "2.1.5", "Google", "GPL-2.0 with Classpath Exception", "https://github.com/google/desugar_jdk_libs"}
        };

        for (String[] license : licenses) {
            View item = getLayoutInflater().inflate(
                    R.layout.item_open_source_license, container, false);
            ((TextView) item.findViewById(R.id.license_name)).setText(license[0]);
            ((TextView) item.findViewById(R.id.license_version)).setText(license[1]);
            ((TextView) item.findViewById(R.id.license_author)).setText(license[2]);
            ((TextView) item.findViewById(R.id.license_badge)).setText(license[3]);
            item.setOnClickListener(view -> openConfiguredLink(license[4]));
            container.addView(item);
        }
    }

    private void switchPage(View targetPage) {
        if (targetPage == null) return;

        if (currentPage == null) {
            homePage.setVisibility(targetPage == homePage ? View.VISIBLE : View.GONE);
            configPage.setVisibility(targetPage == configPage ? View.VISIBLE : View.GONE);
            backupPage.setVisibility(targetPage == backupPage ? View.VISIBLE : View.GONE);
            settingsPage.setVisibility(targetPage == settingsPage ? View.VISIBLE : View.GONE);
            licensesPage.setVisibility(targetPage == licensesPage ? View.VISIBLE : View.GONE);
            currentPage = targetPage;
        } else if (currentPage != targetPage) {
            View outgoing = currentPage;
            currentPage = targetPage;
            outgoing.animate().cancel();
            targetPage.animate().cancel();
            if (!animationsEnabled()) {
                outgoing.setVisibility(View.GONE);
                outgoing.setAlpha(1f);
                outgoing.setTranslationY(0f);
                targetPage.setVisibility(View.VISIBLE);
                targetPage.setAlpha(1f);
                targetPage.setTranslationY(0f);
            } else {
                float enterDistance = dp(10);
                outgoing.animate()
                        .alpha(0f)
                        .translationY(-dp(6))
                        .setDuration(130L)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(() -> {
                            if (outgoing != currentPage) outgoing.setVisibility(View.GONE);
                            outgoing.setAlpha(1f);
                            outgoing.setTranslationY(0f);
                        })
                        .start();
                targetPage.setVisibility(View.VISIBLE);
                targetPage.setAlpha(0f);
                targetPage.setTranslationY(enterDistance);
                targetPage.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(220L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
        }
    }

    private void bindDeviceInformation() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? getString(R.string.unknown_value) : Build.MODEL.trim();
        if (!manufacturer.isEmpty()
                && !model.toLowerCase(Locale.ROOT).startsWith(manufacturer.toLowerCase(Locale.ROOT))) {
            model = manufacturer + " " + model;
        }
        deviceModel.setText(model);
        String processor;
        if (Build.VERSION.SDK_INT >= 31) {
            processor = (Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL).trim();
        } else {
            processor = Build.HARDWARE == null ? "" : Build.HARDWARE.trim();
        }
        if (processor.isEmpty()) processor = getString(R.string.unknown_value);
        deviceProcessor.setText(processor);
        updateMemoryUsage();
        deviceSystem.setText(getString(R.string.android_version_format, Build.VERSION.RELEASE));
        String architecture = Build.SUPPORTED_ABIS.length == 0
                ? getString(R.string.unknown_value) : Build.SUPPORTED_ABIS[0];
        deviceSystemDetail.setText(getString(
                R.string.android_system_detail, Build.VERSION.SDK_INT, architecture));
        deviceGpu.setText(R.string.status_checking);
        worker.execute(() -> {
            String renderer = readGpuRenderer();
            mainHandler.post(() -> {
                if (!destroyed) deviceGpu.setText(renderer);
            });
        });
        refreshLogSummary();
    }

    private void updateMemoryUsage() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        deviceMemory.setText(getString(
                R.string.device_memory_available_total,
                formatMemoryGigabytes(info.availMem),
                formatMemoryGigabytes(info.totalMem)));
        int usedPercent = info.totalMem <= 0L ? 0 : clampPercent((int) Math.round(
                100d * (info.totalMem - info.availMem) / info.totalMem));
        memoryUsageValue.setText(getString(R.string.load_percent, usedPercent));
        memoryUsageProgress.setProgressCompat(usedPercent, true);
        memoryUsageProgress.setIndicatorColor(loadColor(memoryUsageProgress, usedPercent));
    }

    private String formatMemoryGigabytes(long bytes) {
        double gigabytes = Math.max(0L, bytes) / 1_000_000_000d;
        double rounded = Math.rint(gigabytes);
        if (Math.abs(gigabytes - rounded) < 0.08d) {
            return String.format(Locale.getDefault(), "%.0fGB", rounded);
        }
        return String.format(Locale.getDefault(), "%.1fGB", gigabytes);
    }

    private void startSystemLoadMonitor() {
        if (!activityResumed || destroyed || homePage == null
                || homePage.getVisibility() != View.VISIBLE) return;
        int generation = ++loadMonitorGeneration;
        previousCpuTotal = -1L;
        previousCpuIdle = -1L;
        previousGpuActive = -1L;
        previousGpuTotal = -1L;
        scheduleSystemLoadSample(generation, 0L);
    }

    private void stopSystemLoadMonitor() {
        loadMonitorGeneration++;
    }

    private void scheduleSystemLoadSample(int generation, long delayMillis) {
        mainHandler.postDelayed(() -> {
            if (destroyed || !activityResumed || generation != loadMonitorGeneration
                    || homePage.getVisibility() != View.VISIBLE) return;
            updateMemoryUsage();
            if (!privilegeState.isReady() || repository == null) {
                showLoadUnavailable();
                scheduleSystemLoadSample(generation, 2_000L);
                return;
            }
            worker.execute(() -> {
                try {
                    ConfigRepository.SystemLoadSample sample = repository.sampleSystemLoad();
                    mainHandler.post(() -> {
                        if (destroyed || !activityResumed || generation != loadMonitorGeneration) return;
                        applySystemLoadSample(sample);
                        scheduleSystemLoadSample(generation, 2_000L);
                    });
                } catch (Exception error) {
                    mainHandler.post(() -> {
                        if (destroyed || generation != loadMonitorGeneration) return;
                        showLoadUnavailable();
                        scheduleSystemLoadSample(generation, 3_000L);
                    });
                }
            });
        }, delayMillis);
    }

    private void applySystemLoadSample(ConfigRepository.SystemLoadSample sample) {
        if (previousCpuTotal >= 0L) {
            long totalDelta = sample.cpuTotal - previousCpuTotal;
            long idleDelta = sample.cpuIdle - previousCpuIdle;
            if (totalDelta > 0L) {
                int cpuPercent = clampPercent((int) Math.round(
                        100d * (totalDelta - Math.max(0L, idleDelta)) / totalDelta));
                setLoadGauge(cpuUsageProgress, cpuUsageValue, cpuPercent);
            }
        }
        previousCpuTotal = sample.cpuTotal;
        previousCpuIdle = sample.cpuIdle;

        int gpuPercent = -1;
        if (sample.gpuDirectPercent >= 0) {
            gpuPercent = clampPercent(sample.gpuDirectPercent);
        } else if (previousGpuTotal >= 0L) {
            long activeDelta = sample.gpuActive - previousGpuActive;
            long totalDelta = sample.gpuTotal - previousGpuTotal;
            if (totalDelta > 0L && activeDelta >= 0L) {
                gpuPercent = clampPercent((int) Math.round(100d * activeDelta / totalDelta));
            }
        }
        previousGpuActive = sample.gpuActive;
        previousGpuTotal = sample.gpuTotal;
        if (gpuPercent >= 0) setLoadGauge(gpuUsageProgress, gpuUsageValue, gpuPercent);
    }

    private void setLoadGauge(
            CircularProgressIndicator progress, TextView value, int percent) {
        int color = loadColor(progress, percent);
        progress.setIndicatorColor(color);
        progress.setProgressCompat(percent, true);
        value.setText(getString(R.string.load_percent, percent));
        value.setTextColor(color);
    }

    private void showLoadUnavailable() {
        cpuUsageValue.setText(R.string.load_waiting);
        gpuUsageValue.setText(R.string.load_waiting);
        cpuUsageProgress.setProgressCompat(0, false);
        gpuUsageProgress.setProgressCompat(0, false);
    }

    private int loadColor(View view, int percent) {
        int colorRes = percent >= 80 ? R.color.wuwa_error
                : percent >= 50 ? R.color.wuwa_warning : R.color.wuwa_success;
        // Load severity is semantic and must remain green/orange/red even with dynamic colors.
        return getColor(colorRes);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String readGpuRenderer() {
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) return getString(R.string.unknown_value);
        int[] versions = new int[2];
        if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            return getString(R.string.unknown_value);
        }
        int[] attributes = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        try {
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
                    || count[0] == 0) return getString(R.string.unknown_value);
            int[] surfaceAttributes = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttributes, 0);
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(
                    display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
            if (surface == EGL14.EGL_NO_SURFACE || context == EGL14.EGL_NO_CONTEXT
                    || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return getString(R.string.unknown_value);
            }
            String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
            return TextUtils.isEmpty(renderer) ? getString(R.string.unknown_value) : renderer.trim();
        } finally {
            EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
        }
    }

    private void applyLanguageBeforeCreate() {
        String language = preferences.getString(KEY_LANGUAGE, "zh-CN");
        AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags("en".equals(language) ? "en" : "zh-CN"));
    }

    private void initializeLanguageSelector() {
        String language = preferences.getString(KEY_LANGUAGE, "zh-CN");
        languageToggleGroup.check("en".equals(language)
                ? R.id.language_english_button : R.id.language_chinese_button);
        languageToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String selected = checkedId == R.id.language_english_button ? "en" : "zh-CN";
            if (selected.equals(preferences.getString(KEY_LANGUAGE, "zh-CN"))) return;
            preferences.edit().putString(KEY_LANGUAGE, selected).apply();
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selected));
        });
    }

    private void applyAppearanceBeforeCreate() {
        String mode = getAppearanceMode();
        if (APPEARANCE_LIGHT.equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        } else if (APPEARANCE_DARK.equals(mode)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    private void migrateAppearancePreferences() {
        String mode = preferences.getString(KEY_APPEARANCE, APPEARANCE_LIGHT);
        if ("dynamic".equals(mode)) {
            preferences.edit()
                    .putString(KEY_APPEARANCE, APPEARANCE_SYSTEM)
                    .putBoolean(KEY_DYNAMIC_COLORS, true)
                    .apply();
        } else if ("default".equals(mode)) {
            preferences.edit().putString(KEY_APPEARANCE, APPEARANCE_LIGHT).apply();
        }
    }

    private String getAppearanceMode() {
        String mode = preferences.getString(KEY_APPEARANCE, APPEARANCE_LIGHT);
        if (APPEARANCE_SYSTEM.equals(mode)
                || APPEARANCE_LIGHT.equals(mode)
                || APPEARANCE_DARK.equals(mode)) return mode;
        return APPEARANCE_LIGHT;
    }

    private boolean isDynamicColorEnabled() {
        return preferences.getBoolean(KEY_DYNAMIC_COLORS, false);
    }

    private void configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int surface = MaterialColors.getColor(
                getWindow().getDecorView(),
                com.google.android.material.R.attr.colorSurface,
                getColor(R.color.wuwa_background));
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!dark);
        controller.setAppearanceLightNavigationBars(!dark);

        View root = findViewById(R.id.main_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            androidx.core.graphics.Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void initializeAppearanceSelector() {
        appearanceToggleGroup.check(buttonIdForAppearance(getAppearanceMode()));
        boolean dynamicAvailable = DynamicColors.isDynamicColorAvailable();
        dynamicColorSwitch.setEnabled(dynamicAvailable);
        dynamicColorSwitch.setChecked(dynamicAvailable && isDynamicColorEnabled());
        appearanceToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            String mode = appearanceForButtonId(checkedId);
            if (mode == null || mode.equals(getAppearanceMode())) return;
            preferences.edit().putString(KEY_APPEARANCE, mode).apply();
            recreate();
        });
        dynamicColorSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!dynamicAvailable || checked == isDynamicColorEnabled()) return;
            preferences.edit().putBoolean(KEY_DYNAMIC_COLORS, checked).apply();
            recreate();
        });
    }

    private static int buttonIdForAppearance(String mode) {
        if (APPEARANCE_LIGHT.equals(mode)) return R.id.appearance_light_button;
        if (APPEARANCE_DARK.equals(mode)) return R.id.appearance_dark_button;
        return R.id.appearance_system_button;
    }

    private static String appearanceForButtonId(int buttonId) {
        if (buttonId == R.id.appearance_light_button) return APPEARANCE_LIGHT;
        if (buttonId == R.id.appearance_dark_button) return APPEARANCE_DARK;
        if (buttonId == R.id.appearance_system_button) return APPEARANCE_SYSTEM;
        return null;
    }

    private void initializePresetSelector() {
        selectedPreset = QualityPreset.fromId(
                preferences.getString(KEY_SELECTED_PRESET, null));
        presetToggleGroup.check(buttonIdForPreset(selectedPreset));
        presetToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || operationRunning) return;
            QualityPreset preset = presetForButtonId(checkedId);
            if (preset == null || preset == selectedPreset) return;
            selectedPreset = preset;
            preferences.edit().putString(KEY_SELECTED_PRESET, preset.getId()).apply();
            refreshUi();
        });
    }

    private static int buttonIdForPreset(QualityPreset preset) {
        switch (preset) {
            case LOW:
                return R.id.preset_low_button;
            case HIGH:
                return R.id.preset_high_button;
            case EXTREME:
                return R.id.preset_extreme_button;
            case MEDIUM:
            default:
                return R.id.preset_medium_button;
        }
    }

    private static QualityPreset presetForButtonId(int buttonId) {
        if (buttonId == R.id.preset_low_button) return QualityPreset.LOW;
        if (buttonId == R.id.preset_medium_button) return QualityPreset.MEDIUM;
        if (buttonId == R.id.preset_high_button) return QualityPreset.HIGH;
        if (buttonId == R.id.preset_extreme_button) return QualityPreset.EXTREME;
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLogger.debug("Activity", "MainActivity onResume");
        activityResumed = true;
        startSystemLoadMonitor();
        if (logManagerStatus != null) refreshLogSummary();
        if (!preferences.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) return;
        checkRemoteUpdates(false);
        if (privilegeManager == null || operationRunning) return;

        installedServers = detectInstalledServers();
        if (selectedServer != null && !installedServers.contains(selectedServer)) {
            selectServer(installedServers.isEmpty() ? null : installedServers.get(0));
        }
        refreshUi();
        privilegeManager.refresh();
        refreshGameRuntimeStatus();
        mainHandler.postDelayed(() -> {
            if (!destroyed && repository != null && !repository.isRotationSessionActive()) {
                rotationStatus.setText(R.string.rotation_status_auto);
            }
        }, 900L);
    }

    @Override
    public void onPrivilegeStateChanged(PrivilegeState state) {
        if (destroyed) return;
        privilegeState = state;
            AppLogger.event("Permission", "权限状态变化：ready=" + state.isReady() +
                "，root=" + state.isRootAvailable() +
                "，shizuku=" + state.getShizukuStatus() +
                (TextUtils.isEmpty(state.getDetail()) ? "" : "，detail=" + state.getDetail()));
        if (state.isRootAvailable()) {
            detectRootProviderAsync();
        } else {
            rootProviderName = null;
            rootProviderLookupRunning = false;
        }
        refreshUi();
        if (state.isReady()) {
            if (emergencyRotationRecoveryRequested) {
                performEmergencyRotationRecovery();
            } else {
                recoverInterruptedRotationSession();
            }
            refreshBackupStatus();
            refreshGameRuntimeStatus();
        } else if (emergencyRotationRecoveryRequested && !state.isCheckingRoot()
                && state.getShizukuStatus() != PrivilegeState.ShizukuStatus.CONNECTING
                && state.getShizukuStatus() != PrivilegeState.ShizukuStatus.WAITING_PERMISSION) {
            emergencyRotationRecoveryRequested = false;
            if (firstRunRecoveryButton != null) {
                firstRunRecoveryButton.setEnabled(true);
                firstRunRecoveryButton.setText(R.string.first_run_restore_rotation);
            }
            Toast.makeText(
                    this, R.string.first_run_restore_rotation_permission,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void chooseInitialServer() {
        if (installedServers.isEmpty()) {
            selectedServer = null;
            return;
        }
        GameServer saved = GameServer.fromPackageName(
                preferences.getString(KEY_SELECTED_PACKAGE, null));
        if (saved != null && installedServers.contains(saved)) {
            selectedServer = saved;
            return;
        }

        selectedServer = installedServers.get(0);
        persistSelectedServer(selectedServer);
        if (installedServers.size() > 1
                && preferences.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) {
            mainHandler.post(this::showServerChooser);
        }
    }

    private List<GameServer> detectInstalledServers() {
        List<GameServer> result = new ArrayList<>();
        PackageManager packageManager = getPackageManager();
        for (GameServer server : GameServer.values()) {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    packageManager.getPackageInfo(
                            server.getPackageName(), PackageManager.PackageInfoFlags.of(0));
                } else {
                    //noinspection deprecation
                    packageManager.getPackageInfo(server.getPackageName(), 0);
                }
                result.add(server);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not installed for the current Android user/profile.
            }
        }
        return result;
    }

    private void showServerChooser() {
        View content = getLayoutInflater().inflate(
                R.layout.dialog_server_chooser, null, false);
        LinearLayout list = content.findViewById(R.id.server_list_container);
        MaterialButton confirm = content.findViewById(R.id.server_chooser_confirm_button);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();
        content.findViewById(R.id.server_chooser_cancel_button)
                .setOnClickListener(view -> dialog.dismiss());

        GameServer[] servers = GameServer.values();
        GameServer[] pending = {installedServers.contains(selectedServer)
                ? selectedServer
                : (installedServers.isEmpty() ? null : installedServers.get(0))};
        List<MaterialCardView> cards = new ArrayList<>();
        List<View> indicators = new ArrayList<>();
        List<ImageView> selectedIcons = new ArrayList<>();

        for (GameServer server : servers) {
            boolean installed = installedServers.contains(server);
            View row = getLayoutInflater().inflate(R.layout.item_server, list, false);
            MaterialCardView card = row.findViewById(R.id.server_item_card);
            View indicator = row.findViewById(R.id.server_item_indicator);
            ImageView selectedIcon = row.findViewById(R.id.server_item_selected_icon);
            TextView name = row.findViewById(R.id.server_item_name);
            TextView version = row.findViewById(R.id.server_item_version);
            MaterialCardView statusCard = row.findViewById(R.id.server_item_status_card);
            TextView status = row.findViewById(R.id.server_item_status);

            name.setText(serverDisplayName(server));
            version.setText(installed
                    ? getString(R.string.game_version_short, getGameVersion(server))
                    : getString(R.string.server_version_unavailable));
            status.setText(installed ? R.string.server_installed : R.string.server_not_installed);
            int installedColor = MaterialColors.getColor(
                    status,
                    com.google.android.material.R.attr.colorTertiary,
                    getColor(R.color.wuwa_success));
            int unavailableColor = MaterialColors.getColor(
                    status,
                    androidx.appcompat.R.attr.colorError,
                    getColor(R.color.wuwa_error));
            int installedContainer = MaterialColors.getColor(
                    statusCard,
                    com.google.android.material.R.attr.colorTertiaryContainer,
                    getColor(R.color.wuwa_success_container));
            int unavailableContainer = MaterialColors.getColor(
                    statusCard,
                    com.google.android.material.R.attr.colorErrorContainer,
                    getColor(R.color.wuwa_error_container));
            statusCard.setCardBackgroundColor(installed ? installedContainer : unavailableContainer);
            status.setTextColor(installed ? installedColor : unavailableColor);
            card.setAlpha(installed ? 1f : 0.78f);
            cards.add(card);
            indicators.add(indicator);
            selectedIcons.add(selectedIcon);
            list.addView(row);
        }

        Runnable renderSelection = () -> {
            int selectedContainer = MaterialColors.getColor(
                    content,
                    com.google.android.material.R.attr.colorPrimaryContainer,
                    getColor(R.color.wuwa_primary_container));
            int normalContainer = MaterialColors.getColor(
                    content,
                    com.google.android.material.R.attr.colorSurfaceContainer,
                    getColor(R.color.wuwa_surface_container));
            int primary = MaterialColors.getColor(
                    content,
                    androidx.appcompat.R.attr.colorPrimary,
                    getColor(R.color.wuwa_primary));
            int outline = MaterialColors.getColor(
                    content,
                    com.google.android.material.R.attr.colorOutlineVariant,
                    getColor(R.color.wuwa_outline_variant));
            for (int index = 0; index < servers.length; index++) {
                boolean selected = servers[index] == pending[0];
                cards.get(index).setCardBackgroundColor(selected ? selectedContainer : normalContainer);
                cards.get(index).setStrokeColor(selected ? primary : outline);
                cards.get(index).setStrokeWidth(selected ? dp(2) : dp(1));
                indicators.get(index).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
                // Keep the trailing column reserved on every row so selection never
                // makes the server name/version/status columns jump horizontally.
                selectedIcons.get(index).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            }
            confirm.setEnabled(pending[0] != null);
        };
        for (int index = 0; index < servers.length; index++) {
            int serverIndex = index;
            GameServer server = servers[index];
            cards.get(index).setOnClickListener(view -> {
                if (!installedServers.contains(server)) {
                    Snackbar.make(
                                    content,
                                    getString(R.string.server_not_installed_message,
                                            serverDisplayName(server)),
                                    Snackbar.LENGTH_LONG)
                            .show();
                    return;
                }
                pending[0] = servers[serverIndex];
                renderSelection.run();
            });
        }
        confirm.setOnClickListener(view -> {
            if (pending[0] == null) return;
            selectServer(pending[0]);
            dialog.dismiss();
        });
        renderSelection.run();
        dialog.show();
        animateDialogEntrance(dialog);
        capDialogScrollArea(content.findViewById(R.id.server_list_scroll), 0.44f, 320);
        animateListChildren(list);
    }

    private void selectServer(GameServer server) {
        selectedServer = server;
        AppLogger.info("Server", "当前目标=" +
                (server == null ? "none" : server.getPackageName()));
        if (server != null) persistSelectedServer(server);
        backupQueryGeneration++;
        refreshUi();
        refreshBackupStatus();
        refreshGameRuntimeStatus();
    }

    private void detectRootProviderAsync() {
        if (rootProviderLookupRunning || !TextUtils.isEmpty(rootProviderName)
                || privilegeManager == null) return;
        rootProviderLookupRunning = true;
        worker.execute(() -> {
            String detected = privilegeManager.detectRootProvider();
            mainHandler.post(() -> {
                rootProviderLookupRunning = false;
                if (destroyed || !privilegeState.isRootAvailable()) return;
                rootProviderName = TextUtils.isEmpty(detected) ? "Root" : detected;
                refreshUi();
            });
        });
    }

    private void onHomeServerClicked(GameServer server) {
        if (operationRunning) return;
        if (!installedServers.contains(server)) {
            Snackbar.make(
                    homePage,
                    getString(R.string.server_not_installed_message, serverDisplayName(server)),
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        selectServer(server);
    }

    private void persistSelectedServer(GameServer server) {
        preferences.edit().putString(KEY_SELECTED_PACKAGE, server.getPackageName()).apply();
    }

    private void preparePresetsAsync() {
        worker.execute(() -> {
            boolean ready = repository.preparePresets();
            mainHandler.post(() -> {
                if (destroyed) return;
                presetsChecked = true;
                presetsReady = ready;
                refreshUi();
                refreshBackupStatus();
            });
        });
    }

    private void refreshUi() {
        if (destroyed || targetName == null) return;

        if (selectedServer == null) {
            setTargetLabels(R.string.current_target_none, R.string.current_target_none_detail);
            gameVersionStatus.setText(R.string.game_version_unknown);
            presetCardTitle.setText(R.string.replace_card_title);
        } else {
            String gameVersion = getGameVersion(selectedServer);
            targetName.setText(serverDisplayName(selectedServer));
            targetState.setText(R.string.game_process_checking);
            configTargetName.setText(serverDisplayName(selectedServer));
            configTargetState.setText(R.string.tap_to_select_server);
            backupTargetName.setText(serverDisplayName(selectedServer));
            backupTargetState.setText(R.string.tap_to_select_server);
            gameVersionStatus.setText(getString(
                    R.string.game_version_format, gameVersion, serverDisplayName(selectedServer)));
            presetCardTitle.setText(getString(
                    R.string.preset_version_title, gameVersion, BuildConfig.PRESET_REVISION));
        }
        renderHomeServerRows();
        softwareVersionStatus.setText(getString(
                R.string.software_version_short, BuildConfig.VERSION_NAME));
        boolean rotationActive = RotationControllerService.isRunning();
        setStatus(
                homeRotationStatus,
                rotationActive
                        ? R.string.direction_control_active
                        : R.string.direction_control_idle,
                rotationActive ? R.color.wuwa_success : R.color.wuwa_on_surface_variant);
        findViewById(R.id.server_selector_card).setEnabled(!operationRunning);
        findViewById(R.id.config_target_card).setEnabled(!operationRunning);
        findViewById(R.id.backup_target_card).setEnabled(!operationRunning);
        backupRootPath.setText(repository == null
                ? ConfigRepository.DEFAULT_STORAGE_ROOT : repository.getStorageRoot());
        refreshAutomaticBackupRetentionUi();

        boolean checking = privilegeState.isCheckingRoot()
                || privilegeState.getShizukuStatus() == PrivilegeState.ShizukuStatus.CONNECTING;
        boolean ready = privilegeState.isReady();
        permissionWarningCard.setVisibility(!ready && !checking ? View.VISIBLE : View.GONE);
        permissionRefreshButton.setEnabled(!operationRunning && !checking);
        permissionWarningRefreshButton.setEnabled(!operationRunning && !checking);
        shizukuReconnectButton.setEnabled(!operationRunning);

        boolean selectedPresetReady = presetsReady && repository.presetsReady(selectedPreset);
        boolean canReplace = !operationRunning && ready && selectedServer != null
                && selectedPresetReady;
        replaceButton.setEnabled(canReplace);
        replaceButton.setAlpha(canReplace ? 1f : 0.45f);
        restoreButton.setEnabled(!operationRunning && ready && selectedServer != null);
        manualBackupButton.setEnabled(!operationRunning && ready && selectedServer != null);
        manageButton.setEnabled(!operationRunning && ready && selectedServer != null);
        findViewById(R.id.automatic_backup_retention_card).setEnabled(!operationRunning);
        portraitLaunchButton.setEnabled(!operationRunning && ready && selectedServer != null);
        restoreRotationButton.setEnabled(!operationRunning && ready);
        changeStorageButton.setEnabled(!operationRunning && ready);
        openStorageButton.setEnabled(!operationRunning);
        for (int i = 0; i < presetToggleGroup.getChildCount(); i++) {
            presetToggleGroup.getChildAt(i).setEnabled(!operationRunning);
        }

        if (!presetsChecked) {
            setStatus(presetStatus, R.string.status_checking, R.color.wuwa_on_surface_variant);
            presetSelectionDetail.setText(R.string.preset_checking_detail);
            presetSelectionDetail.setTextColor(resolveStatusColor(
                    presetSelectionDetail, R.color.wuwa_on_surface_variant));
        } else if (selectedPresetReady) {
            boolean emptyTest = repository.hasEmptyPresetFile(selectedPreset);
            presetStatus.setText(getString(
                    emptyTest ? R.string.preset_status_test : R.string.preset_status_ready,
                    presetDisplayName(selectedPreset)));
            presetStatus.setTextColor(resolveStatusColor(
                    presetStatus, emptyTest ? R.color.wuwa_primary : R.color.wuwa_success));
            presetSelectionDetail.setText(getString(
                    emptyTest ? R.string.preset_selected_test : R.string.preset_selected_ready,
                    presetDisplayName(selectedPreset)));
            presetSelectionDetail.setTextColor(resolveStatusColor(
                    presetSelectionDetail,
                    emptyTest ? R.color.wuwa_primary : R.color.wuwa_on_surface_variant));
        } else {
            setStatus(presetStatus, R.string.status_missing, R.color.wuwa_error);
            presetSelectionDetail.setText(R.string.preset_missing);
            presetSelectionDetail.setTextColor(resolveStatusColor(
                    presetSelectionDetail, R.color.wuwa_error));
        }

        if (privilegeState.isRootAvailable()) {
            String provider = TextUtils.isEmpty(rootProviderName) ? "Root" : rootProviderName;
            showReadyPermission(
                    getString(R.string.permission_root_provider_format, provider),
                    R.string.permission_root);
        } else if (privilegeState.isShizukuReady()) {
            showReadyPermission(
                    getString(R.string.permission_shizuku_authorized),
                    R.string.permission_shizuku);
        } else if (checking) {
            permissionModeIcon.setImageResource(R.drawable.ic_shield);
            tint(permissionModeIcon, R.color.wuwa_on_surface_variant);
            setStatus(permissionModeStatus, R.string.status_checking, R.color.wuwa_on_surface_variant);
            setBottomPermissionStatus(R.drawable.ic_shield, R.string.permission_checking,
                    R.color.wuwa_on_surface_variant);
        } else {
            permissionModeIcon.setImageResource(R.drawable.ic_warning);
            tint(permissionModeIcon, R.color.wuwa_error);
            setStatus(permissionModeStatus, R.string.status_not_ready, R.color.wuwa_error);
            setBottomPermissionStatus(R.drawable.ic_warning, R.string.permission_not_ready,
                    R.color.wuwa_error);
            if (TextUtils.isEmpty(privilegeState.getDetail())) {
                permissionWarningText.setText(R.string.permission_required);
            } else {
                permissionWarningText.setText(getString(
                        R.string.permission_with_detail,
                        privilegeState.getDetail()));
            }
        }
        settingsPermissionDetail.setText(TextUtils.isEmpty(privilegeState.getDetail())
                ? getString(R.string.permission_settings_description)
                : privilegeState.getDetail());
    }

    private void renderHomeServerRows() {
        renderHomeServerRow(
                GameServer.CHINA_OFFICIAL,
                homeServerChinaRow,
                homeServerChinaVersion,
                homeServerChinaStatus,
                homeServerChinaCurrent);
        renderHomeServerRow(
                GameServer.CHINA_BILIBILI,
                homeServerBilibiliRow,
                homeServerBilibiliVersion,
                homeServerBilibiliStatus,
                homeServerBilibiliCurrent);
        renderHomeServerRow(
                GameServer.GLOBAL,
                homeServerGlobalRow,
                homeServerGlobalVersion,
                homeServerGlobalStatus,
                homeServerGlobalCurrent);
    }

    private void renderHomeServerRow(
            GameServer server,
            View row,
            TextView versionView,
            TextView statusView,
            TextView currentView) {
        boolean installed = installedServers.contains(server);
        versionView.setText(installed
                ? getString(R.string.game_version_short, getGameVersion(server))
                : getString(R.string.server_version_unavailable));
        setStatus(
                statusView,
                installed ? R.string.server_installed : R.string.server_not_installed,
                installed ? R.color.wuwa_success : R.color.wuwa_error);
        currentView.setVisibility(installed && selectedServer == server
                ? View.VISIBLE : View.INVISIBLE);
        row.setEnabled(!operationRunning);
        row.setAlpha(installed ? 1f : 0.72f);
    }

    private void setTargetLabels(int nameResource, int stateResource) {
        setTargetLabels(getString(nameResource), getString(stateResource));
    }

    private void setTargetLabels(String name, String state) {
        targetName.setText(name);
        configTargetName.setText(name);
        backupTargetName.setText(name);
        targetState.setText(state);
        configTargetState.setText(state);
        backupTargetState.setText(state);
    }

    @Override
    protected void onPause() {
        AppLogger.debug("Activity", "MainActivity onPause");
        activityResumed = false;
        stopSystemLoadMonitor();
        super.onPause();
    }

    private String getGameVersion(GameServer server) {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = getPackageManager().getPackageInfo(
                        server.getPackageName(), PackageManager.PackageInfoFlags.of(0));
            } else {
                //noinspection deprecation
                info = getPackageManager().getPackageInfo(server.getPackageName(), 0);
            }
            String version = info.versionName;
            if (TextUtils.isEmpty(version)) return getString(R.string.unknown_value);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(\\d+)\\.(\\d+)")
                    .matcher(version);
            return matcher.find() ? matcher.group(1) + "." + matcher.group(2) : version;
        } catch (PackageManager.NameNotFoundException error) {
            return getString(R.string.unknown_value);
        }
    }

    private String serverDisplayName(GameServer server) {
        if (server == null) return getString(R.string.current_target_none);
        switch (server) {
            case CHINA_OFFICIAL:
                return getString(R.string.server_china_official);
            case CHINA_BILIBILI:
                return getString(R.string.server_china_bilibili);
            case GLOBAL:
            default:
                return getString(R.string.server_global);
        }
    }

    private String presetDisplayName(QualityPreset preset) {
        if (preset == null) return getString(R.string.preset_medium);
        switch (preset) {
            case LOW:
                return getString(R.string.preset_low);
            case HIGH:
                return getString(R.string.preset_high);
            case EXTREME:
                return getString(R.string.preset_extreme);
            case MEDIUM:
            default:
                return getString(R.string.preset_medium);
        }
    }

    private void refreshGameRuntimeStatus() {
        final GameServer server = selectedServer;
        final int generation = ++processQueryGeneration;
        if (server == null) {
            gameProcessStatus.setText(R.string.game_process_not_installed);
            return;
        }
        if (!privilegeState.isReady()) {
            gameProcessStatus.setText(R.string.game_process_permission_required);
            return;
        }
        gameProcessStatus.setText(R.string.game_process_checking);
        worker.execute(() -> {
            try {
                int count = repository.getRunningProcessCount(server);
                mainHandler.post(() -> {
                    if (destroyed || generation != processQueryGeneration
                            || server != selectedServer) return;
                    gameProcessStatus.setText(count == 0
                            ? getString(R.string.game_process_stopped)
                            : getString(R.string.game_process_running, count));
                    targetState.setText(count == 0
                            ? getString(R.string.game_process_stopped)
                            : getString(R.string.game_running_summary, count));
                    gameProcessStatus.setTextColor(resolveStatusColor(
                            gameProcessStatus,
                            count == 0 ? R.color.wuwa_on_surface_variant : R.color.wuwa_success));
                });
            } catch (OperationException error) {
                mainHandler.post(() -> {
                    if (destroyed || generation != processQueryGeneration) return;
                    gameProcessStatus.setText(R.string.game_process_unavailable);
                    gameProcessStatus.setTextColor(resolveStatusColor(
                            gameProcessStatus, R.color.wuwa_error));
                });
            }
        });
    }

    private void showReadyPermission(CharSequence modeText, int bottomStatusText) {
        permissionModeIcon.setImageResource(R.drawable.ic_check);
        tint(permissionModeIcon, R.color.wuwa_success);
        permissionModeStatus.setText(modeText);
        permissionModeStatus.setTextColor(resolveStatusColor(
                permissionModeStatus, R.color.wuwa_success));
        setBottomPermissionStatus(R.drawable.ic_check, bottomStatusText, R.color.wuwa_success);
    }

    private void setBottomPermissionStatus(int iconResource, int textResource, int colorResource) {
        permissionStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(iconResource, 0, 0, 0);
        TextViewCompat.setCompoundDrawableTintList(permissionStatus,
                ColorStateList.valueOf(resolveStatusColor(permissionStatus, colorResource)));
        setStatus(permissionStatus, textResource, colorResource);
    }

    private void refreshBackupStatus() {
        final GameServer server = selectedServer;
        if (server == null || !privilegeState.isReady() || operationRunning) return;

        final int generation = ++backupQueryGeneration;
        setStatus(backupStatus, R.string.backup_checking, R.color.wuwa_on_surface_variant);
        backupLibrarySummary.setText(R.string.backup_library_checking);
        worker.execute(() -> {
            try {
                boolean backedUp = repository.hasValidBackup(server);
                int manualCount = repository.listManualBackups(server).size();
                mainHandler.post(() -> {
                    if (destroyed || generation != backupQueryGeneration || server != selectedServer) return;
                    int backupCount = manualCount + (backedUp ? 1 : 0);
                    if (backupCount > 0) {
                        backupStatus.setText(getString(R.string.backup_count, backupCount));
                        backupStatus.setTextColor(resolveStatusColor(
                                backupStatus, R.color.wuwa_success));
                    } else {
                        setStatus(backupStatus, R.string.backup_missing, R.color.wuwa_error);
                    }
                    backupLibrarySummary.setText(getString(
                            R.string.backup_library_summary_format,
                            getString(backedUp ? R.string.backup_ready : R.string.backup_missing),
                            manualCount));
                });
            } catch (OperationException error) {
                mainHandler.post(() -> {
                    if (destroyed || generation != backupQueryGeneration || server != selectedServer) return;
                    setStatus(backupStatus, R.string.backup_unreadable, R.color.wuwa_error);
                    backupLibrarySummary.setText(R.string.backup_unreadable);
                });
            }
        });
    }

    private void runReplace() {
        GameServer server = selectedServer;
        if (!validateOperation(server, true)) return;
        QualityPreset preset = selectedPreset;
        if (repository.hasEmptyPresetFile(preset)) {
            showPanelDialog(
                    getString(R.string.preset_empty_warning_title),
                    getString(R.string.preset_empty_warning_message, presetDisplayName(preset)),
                    DialogTone.WARNING,
                    R.string.cancel,
                    null,
                    R.string.continue_test,
                    () -> startReplace(server, preset),
                    false);
            return;
        }
        startReplace(server, preset);
    }

    private void startReplace(GameServer server, QualityPreset preset) {
        AppLogger.info("Replace", "请求替换画质；服务器=" + server.getPackageName() +
                "；预设=" + preset.name());
        runOperation(
                getString(R.string.progress_replace, presetDisplayName(preset)),
                () -> repository.replace(server, preset),
                true);
    }

    private void runRestore() {
        GameServer server = selectedServer;
        if (!validateOperation(server, false)) return;
        AppLogger.info("Restore", "请求恢复游戏原配置；服务器=" + server.getPackageName());
        runOperation(getString(R.string.progress_restore), () -> repository.restore(server));
    }

    private void runManualBackup() {
        GameServer server = selectedServer;
        if (!validateOperation(server, false)) return;
        AppLogger.info("Backup", "开始创建手动备份；服务器=" + server.getPackageName());

        operationRunning = true;
        backupQueryGeneration++;
        refreshUi();
        showProgress(getString(R.string.progress_manual_backup));
        worker.execute(() -> {
            try {
                String snapshot = repository.createManualBackup(server);
                AppLogger.event("Backup", "手动备份成功；快照=" + snapshot);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    refreshBackupStatus();
                    showPanelDialog(
                            getString(R.string.dialog_completed),
                            getString(R.string.manual_backup_success, snapshot),
                            DialogTone.SUCCESS,
                            0,
                            null,
                            R.string.done,
                            null,
                            false);
                });
            } catch (Exception error) {
                AppLogger.error("Backup", "手动备份失败", error);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    showError(safeMessage(error));
                    refreshBackupStatus();
                });
            }
        });
    }

    private void openManualBackupList() {
        GameServer server = selectedServer;
        if (!validateOperation(server, false)) return;
        showProgress(getString(R.string.progress_scan_backup));
        worker.execute(() -> {
            try {
                List<ManualBackupInfo> snapshots = repository.listManualBackups(server);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    dismissProgress();
                    showManualBackupList(server, snapshots);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    dismissProgress();
                    showError(safeMessage(error));
                });
            }
        });
    }

    private void showManualBackupList(
            GameServer server, List<ManualBackupInfo> snapshots) {
        if (snapshots.isEmpty()) {
            showPanelDialog(
                    getString(R.string.manual_backup_list_title),
                    getString(R.string.manual_backup_list_empty),
                    DialogTone.INFO,
                    0,
                    null,
                    R.string.done,
                    null,
                    false);
            return;
        }

        String[] labels = new String[snapshots.size()];
        String[] details = new String[snapshots.size()];
        int[] icons = new int[snapshots.size()];
        for (int i = 0; i < snapshots.size(); i++) {
            ManualBackupInfo snapshot = snapshots.get(i);
            labels[i] = displayNameForSnapshot(snapshot);
            details[i] = getString(
                    R.string.manual_snapshot_typed_detail,
                    snapshotTypeLabel(snapshot),
                    formatSnapshotName(snapshot.getSnapshotName()),
                    formatBytes(snapshot.getSizeBytes()));
            icons[i] = R.drawable.ic_restore;
        }
        showChoicePanel(
                getString(R.string.manual_backup_list_title),
                getString(R.string.restore_center_description),
                labels,
                details,
                icons,
                which -> confirmManualRestore(server, snapshots.get(which)));
    }

    private void confirmManualRestore(GameServer server, ManualBackupInfo snapshot) {
        showPanelDialog(
                getString(R.string.manual_restore_confirm_title),
                getString(
                        R.string.manual_restore_confirm_message,
                        formatSnapshotName(snapshot.getSnapshotName())),
                DialogTone.WARNING,
                R.string.cancel,
                null,
                R.string.restore_snapshot,
                () -> runOperation(
                        getString(R.string.progress_restore_snapshot),
                        () -> repository.restoreManualBackup(
                                server, snapshot.getSnapshotName())),
                false);
    }

    private void openRestoreCenter() {
        GameServer server = selectedServer;
        if (!validateOperation(server, false)) return;
        showProgress(getString(R.string.progress_scan_backup));
        worker.execute(() -> {
            try {
                boolean hasInitial = repository.hasValidBackup(server);
                List<ManualBackupInfo> snapshots = repository.listManualBackups(server);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    dismissProgress();
                    showRestoreCenter(server, hasInitial, snapshots);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    dismissProgress();
                    showError(safeMessage(error));
                });
            }
        });
    }

    private void showRestoreCenter(
            GameServer server, boolean hasInitial, List<ManualBackupInfo> snapshots) {
        if (!hasInitial && snapshots.isEmpty()) {
            showPanelDialog(
                    getString(R.string.restore_center_title),
                    getString(R.string.restore_center_empty),
                    DialogTone.INFO,
                    0,
                    null,
                    R.string.done,
                    null,
                    false);
            return;
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_restore_center, null, false);
        LinearLayout list = content.findViewById(R.id.restore_backup_list);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();
        content.findViewById(R.id.restore_center_close_button)
                .setOnClickListener(view -> dialog.dismiss());

        if (hasInitial) {
            View row = createRestoreRow(
                    list,
                    getString(R.string.initial_protection_backup),
                    getString(R.string.initial_protection_detail));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                confirmInitialRestore(server);
            });
            list.addView(row);
        }
        for (ManualBackupInfo snapshot : snapshots) {
            String title = displayNameForSnapshot(snapshot);
            String detail = getString(
                    R.string.manual_snapshot_typed_detail,
                    snapshotTypeLabel(snapshot),
                    formatSnapshotName(snapshot.getSnapshotName()),
                    formatBytes(snapshot.getSizeBytes()));
            View row = createRestoreRow(list, title, detail);
            row.setOnClickListener(view -> {
                dialog.dismiss();
                confirmManualRestore(server, snapshot);
            });
            list.addView(row);
        }
        dialog.show();
        animateDialogEntrance(dialog);
        capDialogScrollArea(content.findViewById(R.id.restore_backup_scroll), 0.46f, 380);
        animateListChildren(list);
    }

    private View createRestoreRow(LinearLayout parent, String title, String detail) {
        View row = getLayoutInflater().inflate(R.layout.item_restore_backup, parent, false);
        ((TextView) row.findViewById(R.id.restore_item_name)).setText(title);
        ((TextView) row.findViewById(R.id.restore_item_detail)).setText(detail);
        return row;
    }

    private void confirmInitialRestore(GameServer server) {
        showPanelDialog(
                getString(R.string.initial_backup_title),
                getString(R.string.initial_backup_description),
                DialogTone.WARNING,
                R.string.cancel,
                null,
                R.string.restore_default,
                () -> runOperation(
                        getString(R.string.progress_restore),
                        () -> repository.restore(server)),
                false);
    }

    private void openManualBackupManager() {
        GameServer server = selectedServer;
        if (!validateOperation(server, false)) return;
        showProgress(getString(R.string.progress_scan_backup));
        worker.execute(() -> {
            try {
                boolean hasOriginal = repository.hasValidBackup(server);
                List<ManualBackupInfo> snapshots = repository.listManualBackups(server);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    dismissProgress();
                    showManualBackupManager(server, hasOriginal, snapshots);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    dismissProgress();
                    showError(safeMessage(error));
                });
            }
        });
    }

    private void showManualBackupManager(
            GameServer server, boolean hasOriginal, List<ManualBackupInfo> snapshots) {
        if (!hasOriginal && snapshots.isEmpty()) {
            showPanelDialog(
                    getString(R.string.manual_manager_title),
                    getString(R.string.manual_backup_list_empty),
                    DialogTone.INFO,
                    0,
                    null,
                    R.string.done,
                    null,
                    false);
            return;
        }

        View content = getLayoutInflater().inflate(
                R.layout.dialog_manual_backup_manager, null, false);
        LinearLayout list = content.findViewById(R.id.manual_backup_manager_list);
        MaterialCardView originalCard = content.findViewById(R.id.original_backup_manage_card);
        View selectionControls = content.findViewById(R.id.manual_backup_selection_controls);
        TextView emptyText = content.findViewById(R.id.manual_backup_empty_text);
        View backupScroll = content.findViewById(R.id.manual_backup_scroll);
        MaterialButton enterMultiSelect = content.findViewById(R.id.enter_multi_select_button);
        MaterialButton selectAll = content.findViewById(R.id.select_all_backups_button);
        MaterialButton deleteSelected = content.findViewById(R.id.delete_selected_backups_button);
        MaterialButton exitMultiSelect = content.findViewById(R.id.exit_multi_select_button);
        MaterialButton restoreOriginal = content.findViewById(R.id.restore_original_backup_button);
        MaterialButton closeButton = content.findViewById(R.id.manual_backup_close_button);
        Set<String> selected = new HashSet<>();
        List<MaterialCheckBox> checkBoxes = new ArrayList<>();
        List<View> normalActions = new ArrayList<>();
        final boolean[] selectionMode = {false};

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();
        closeButton.setOnClickListener(view -> dialog.dismiss());

        originalCard.setVisibility(hasOriginal ? View.VISIBLE : View.GONE);
        restoreOriginal.setOnClickListener(view -> {
            dialog.dismiss();
            confirmInitialRestore(server);
        });
        selectionControls.setVisibility(snapshots.isEmpty() ? View.GONE : View.VISIBLE);
        emptyText.setVisibility(snapshots.isEmpty() ? View.VISIBLE : View.GONE);
        backupScroll.setVisibility(snapshots.isEmpty() ? View.GONE : View.VISIBLE);

        Runnable updateSelection = () -> {
            int count = selected.size();
            deleteSelected.setEnabled(count > 0);
            deleteSelected.setAlpha(count > 0 ? 1f : 0.45f);
            deleteSelected.setText(count > 0
                    ? getString(R.string.selected_count, count)
                    : getString(R.string.delete_selected));
            selectAll.setText(count == snapshots.size()
                    ? R.string.clear_selection : R.string.select_all);
        };

        Runnable updateMode = () -> {
            boolean multi = selectionMode[0];
            enterMultiSelect.setVisibility(multi ? View.GONE : View.VISIBLE);
            selectAll.setVisibility(multi ? View.VISIBLE : View.GONE);
            deleteSelected.setVisibility(multi ? View.VISIBLE : View.GONE);
            exitMultiSelect.setVisibility(multi ? View.VISIBLE : View.GONE);
            for (MaterialCheckBox checkBox : checkBoxes) {
                checkBox.setVisibility(multi ? View.VISIBLE : View.GONE);
                if (!multi) checkBox.setChecked(false);
            }
            for (View action : normalActions) {
                action.setVisibility(multi ? View.GONE : View.VISIBLE);
            }
            if (!multi) selected.clear();
            updateSelection.run();
        };

        for (ManualBackupInfo snapshot : snapshots) {
            View row = getLayoutInflater().inflate(
                    R.layout.item_manual_backup_manage, list, false);
            MaterialCheckBox checkBox = row.findViewById(R.id.manual_backup_checkbox);
            TextView name = row.findViewById(R.id.manual_backup_name);
            TextView detail = row.findViewById(R.id.manual_backup_detail);
            MaterialButton rename = row.findViewById(R.id.rename_manual_backup_button);
            MaterialButton delete = row.findViewById(R.id.delete_manual_backup_button);
            name.setText(displayNameForSnapshot(snapshot));
            detail.setText(getString(
                    R.string.manual_snapshot_typed_detail,
                    snapshotTypeLabel(snapshot),
                    formatSnapshotName(snapshot.getSnapshotName()),
                    formatBytes(snapshot.getSizeBytes())));
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(snapshot.getSnapshotName());
                else selected.remove(snapshot.getSnapshotName());
                updateSelection.run();
            });
            rename.setOnClickListener(view -> {
                dialog.dismiss();
                showRenameBackupDialog(server, snapshot);
            });
            delete.setOnClickListener(view -> {
                dialog.dismiss();
                confirmDeleteManualBackups(
                        server, java.util.Collections.singletonList(snapshot.getSnapshotName()));
            });
            row.setOnClickListener(view -> {
                if (selectionMode[0]) checkBox.setChecked(!checkBox.isChecked());
            });
            checkBoxes.add(checkBox);
            normalActions.add(rename);
            normalActions.add(delete);
            list.addView(row);
        }

        enterMultiSelect.setOnClickListener(view -> {
            selectionMode[0] = true;
            updateMode.run();
        });
        exitMultiSelect.setOnClickListener(view -> {
            selectionMode[0] = false;
            updateMode.run();
        });
        selectAll.setOnClickListener(view -> {
            boolean check = selected.size() != snapshots.size();
            for (MaterialCheckBox checkBox : checkBoxes) checkBox.setChecked(check);
        });
        deleteSelected.setOnClickListener(view -> {
            if (selected.isEmpty()) {
                Toast.makeText(this, R.string.select_backup_first, Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> toDelete = new ArrayList<>(selected);
            dialog.dismiss();
            confirmDeleteManualBackups(server, toDelete);
        });
        updateMode.run();
        dialog.show();
        animateDialogEntrance(dialog);
        animateListChildren(list);
    }

    private void showRenameBackupDialog(GameServer server, ManualBackupInfo snapshot) {
        View content = getLayoutInflater().inflate(R.layout.dialog_text_input, null, false);
        TextView title = content.findViewById(R.id.dialog_text_input_title);
        TextView message = content.findViewById(R.id.dialog_text_input_message);
        TextInputLayout layout = content.findViewById(R.id.dialog_text_input_layout);
        TextInputEditText input = content.findViewById(R.id.dialog_text_input);
        MaterialButton cancel = content.findViewById(R.id.dialog_text_input_cancel);
        MaterialButton save = content.findViewById(R.id.dialog_text_input_save);
        title.setText(R.string.rename_backup_title);
        message.setVisibility(View.GONE);
        layout.setHint(getString(R.string.name));
        layout.setCounterMaxLength(30);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
        input.setText(displayNameForSnapshot(snapshot));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            String name = input.getText() == null ? "" : input.getText().toString().trim();
            if (name.isEmpty()) {
                layout.setError(getString(R.string.name));
                return;
            }
            dialog.dismiss();
            renameManualBackupAsync(server, snapshot, name);
        });
        dialog.show();
        animateDialogEntrance(dialog);
        input.requestFocus();
    }

    private void renameManualBackupAsync(
            GameServer server, ManualBackupInfo snapshot, String name) {
        operationRunning = true;
        refreshUi();
        showProgress(getString(R.string.rename_backup_title));
        worker.execute(() -> {
            try {
                repository.renameManualBackup(server, snapshot.getSnapshotName(), name);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    Toast.makeText(this, R.string.rename_success, Toast.LENGTH_LONG).show();
                    refreshBackupStatus();
                    openManualBackupManager();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    showError(safeMessage(error));
                });
            }
        });
    }

    private void confirmDeleteManualBackups(GameServer server, List<String> snapshotNames) {
        showPanelDialog(
                getString(R.string.delete_selected_title),
                getString(R.string.delete_selected_message, snapshotNames.size()),
                DialogTone.ERROR,
                R.string.cancel,
                null,
                R.string.delete_backup,
                () -> deleteManualBackupsAsync(server, snapshotNames),
                true);
    }

    private void deleteManualBackupsAsync(GameServer server, List<String> snapshotNames) {
        operationRunning = true;
        refreshUi();
        showProgress(getString(R.string.progress_delete_backup));
        worker.execute(() -> {
            try {
                repository.deleteManualBackups(server, snapshotNames);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    Toast.makeText(
                            this, R.string.manual_backups_deleted, Toast.LENGTH_LONG).show();
                    refreshBackupStatus();
                    openManualBackupManager();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    showError(safeMessage(error));
                });
            }
        });
    }

    private String displayNameForSnapshot(ManualBackupInfo snapshot) {
        return snapshot.hasCustomName()
                ? snapshot.getDisplayName()
                : formatSnapshotName(snapshot.getSnapshotName());
    }

    private String snapshotTypeLabel(ManualBackupInfo snapshot) {
        return getString(snapshot.isAutomatic()
                ? R.string.backup_type_automatic : R.string.backup_type_manual);
    }

    private void refreshAutomaticBackupRetentionUi() {
        if (automaticBackupRetentionValue == null || repository == null) return;
        automaticBackupRetentionValue.setText(retentionLabel(
                repository.getAutomaticBackupRetentionLimit()));
    }

    private CharSequence retentionLabel(int limit) {
        if (limit == 5) return getString(R.string.automatic_backup_retention_5);
        if (limit == 20) return getString(R.string.automatic_backup_retention_20);
        if (limit == 0) return getString(R.string.automatic_backup_retention_unlimited);
        return getString(R.string.automatic_backup_retention_10);
    }

    private void showAutomaticBackupRetentionChooser() {
        if (operationRunning || repository == null) return;
        int current = repository.getAutomaticBackupRetentionLimit();
        int[] values = {5, 10, 20, 0};
        CharSequence[] labels = new CharSequence[values.length];
        CharSequence[] details = new CharSequence[values.length];
        int[] icons = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = retentionLabel(values[i]);
            details[i] = values[i] == current ? getString(R.string.current_selection) : null;
            icons[i] = R.drawable.ic_backup_add;
        }
        showChoicePanel(
                getString(R.string.automatic_backup_retention_title),
                getString(R.string.automatic_backup_retention_dialog_description),
                labels,
                details,
                icons,
                which -> {
                    repository.setAutomaticBackupRetentionLimit(values[which]);
                    refreshAutomaticBackupRetentionUi();
                    Snackbar.make(
                                    backupPage,
                                    R.string.automatic_backup_retention_saved,
                                    Snackbar.LENGTH_LONG)
                            .setAnchorView(bottomNavigation)
                            .show();
                });
    }

    private void showChangeStorageDialog() {
        if (!privilegeState.isReady()) {
            showError(getString(R.string.permission_required));
            return;
        }
        showChoicePanel(
                getString(R.string.change_storage_method_title),
                getString(R.string.change_storage_message),
                new CharSequence[]{
                        getString(R.string.choose_storage_folder),
                        getString(R.string.enter_storage_path)
                },
                null,
                new int[]{R.drawable.ic_folder, R.drawable.ic_edit},
                which -> {
                    if (which == 0) launchStorageFolderPicker(null, true);
                    else showManualStoragePathDialog();
                });
    }

    private void showManualStoragePathDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_text_input, null, false);
        TextView title = content.findViewById(R.id.dialog_text_input_title);
        TextView message = content.findViewById(R.id.dialog_text_input_message);
        TextInputLayout layout = content.findViewById(R.id.dialog_text_input_layout);
        TextInputEditText input = content.findViewById(R.id.dialog_text_input);
        MaterialButton cancel = content.findViewById(R.id.dialog_text_input_cancel);
        MaterialButton save = content.findViewById(R.id.dialog_text_input_save);
        title.setText(R.string.change_storage_title);
        message.setText(R.string.change_storage_message);
        layout.setHint(getString(R.string.storage_path_hint));
        layout.setCounterMaxLength(220);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(220)});
        input.setText(repository.getStorageRoot());

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();
        cancel.setOnClickListener(view -> dialog.dismiss());
        save.setOnClickListener(view -> {
            String path = input.getText() == null ? "" : input.getText().toString().trim();
            if (!path.startsWith("/storage/emulated/0/") || path.contains("..")) {
                layout.setError(getString(R.string.storage_path_invalid));
                return;
            }
            dialog.dismiss();
            changeStorageAsync(path);
        });
        dialog.show();
        animateDialogEntrance(dialog);
        input.requestFocus();
    }

    private void launchStorageFolderPicker(Uri initialUri, boolean changeLocation) {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (Build.VERSION.SDK_INT >= 26 && initialUri != null) {
            picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }
        try {
            storageFolderSelectionChangesLocation = changeLocation;
            storageFolderPicker.launch(picker);
        } catch (Exception error) {
            storageFolderSelectionChangesLocation = false;
            Snackbar.make(bottomNavigation, R.string.folder_open_failed, Snackbar.LENGTH_LONG)
                    .setAnchorView(bottomNavigation)
                    .show();
        }
    }

    private void handleSelectedStorageFolder(Uri treeUri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
            // Some file managers provide a usable tree URI without persistable grants.
        }

        final String documentId;
        try {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception error) {
            showError(getString(R.string.storage_folder_unsupported));
            return;
        }
        if (!documentId.startsWith("primary:")
                || documentId.length() <= "primary:".length()) {
            showError(getString(R.string.storage_folder_unsupported));
            return;
        }
        String relativePath = documentId.substring("primary:".length());
        if (relativePath.contains("..") || relativePath.contains("\n")
                || relativePath.contains("\r")) {
            showError(getString(R.string.storage_folder_unsupported));
            return;
        }
        changeStorageAsync("/storage/emulated/0/" + relativePath);
    }

    private void changeStorageAsync(String path) {
        operationRunning = true;
        refreshUi();
        showProgress(getString(R.string.change_storage_title));
        worker.execute(() -> {
            try {
                repository.setStorageRoot(path);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    backupQueryGeneration++;
                    Toast.makeText(this, R.string.storage_changed, Toast.LENGTH_LONG).show();
                    refreshBackupStatus();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    showError(safeMessage(error));
                });
            }
        });
    }

    private void openBackupFolder() {
        String root = repository.getStorageRoot();
        Uri documentUri = storageRootToDocumentUri(root);
        if (documentUri != null) {
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(documentUri, "vnd.android.document/directory")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                startActivity(view);
                return;
            } catch (Exception ignored) {
                // Fall through to the system folder picker when direct directory viewing
                // is unsupported by the installed file manager.
            }
        }
        launchStorageFolderPicker(documentUri, false);
    }

    private static Uri storageRootToDocumentUri(String root) {
        String prefix = "/storage/emulated/0/";
        if (!root.startsWith(prefix)) return null;
        String documentId = "primary:" + root.substring(prefix.length());
        Uri treeUri = DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents", documentId);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
    }

    private boolean validateOperation(GameServer server, boolean needsPreset) {
        if (server == null) {
            showError(getString(R.string.no_game));
            return false;
        }
        if (!privilegeState.isReady()) {
            showError(getString(R.string.permission_required));
            return false;
        }
        if (needsPreset && !presetsReady) {
            showError(getString(R.string.preset_missing));
            return false;
        }
        return true;
    }

    private void runOperation(String progressMessage, ThrowingAction action) {
        runOperation(progressMessage, action, false);
    }

    private void runOperation(
            String progressMessage,
            ThrowingAction action,
            boolean promptPortraitLaunchStep) {
        AppLogger.info("Operation", "开始：" + progressMessage);
        operationRunning = true;
        backupQueryGeneration++;
        refreshUi();
        showProgress(progressMessage);
        worker.execute(() -> {
            try {
                action.run();
                AppLogger.event("Operation", "成功：" + progressMessage);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    refreshBackupStatus();
                    showSuccessDialog(promptPortraitLaunchStep);
                });
            } catch (Exception error) {
                AppLogger.error("Operation", "失败：" + progressMessage, error);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    showError(safeMessage(error));
                    refreshBackupStatus();
                });
            }
        });
    }

    private void finishOperation() {
        operationRunning = false;
        dismissProgress();
        refreshUi();
    }

    private void showSuccessDialog(boolean promptPortraitLaunchStep) {
        if (!promptPortraitLaunchStep) {
            showPanelDialog(
                    getString(R.string.dialog_completed),
                    getString(R.string.operation_success),
                    DialogTone.SUCCESS,
                    R.string.done,
                    null,
                    0,
                    null,
                    false);
            return;
        }
        showPanelDialog(
                getString(R.string.replace_step_one_complete_title),
                getString(R.string.replace_step_two_prompt),
                DialogTone.SUCCESS,
                R.string.understood,
                null,
                0,
                null,
                false);
    }

    private void startPortraitLaunch() {
        GameServer server = selectedServer;
        if (!validateOperation(server, false)) return;
        AppLogger.info("Rotation", "请求竖屏启动；服务器=" + server.getPackageName());
        showPanelDialog(
                getString(R.string.portrait_launch_confirm_title),
                getString(R.string.portrait_launch_confirm_message),
                DialogTone.INFO,
                R.string.cancel,
                null,
                R.string.continue_action,
                () -> requestOverlayAndLaunch(server),
                false);
    }

    private void requestOverlayAndLaunch(GameServer server) {
        if (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)) {
            startFloatingRotationController(server);
            return;
        }
        pendingOverlayServer = server;
        showPanelDialog(
                getString(R.string.overlay_permission_title),
                getString(R.string.overlay_permission_message),
                DialogTone.INFO,
                R.string.cancel,
                () -> pendingOverlayServer = null,
                R.string.open_overlay_settings,
                () -> {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    overlayPermissionLauncher.launch(intent);
                },
                false);
    }

    private void startFloatingRotationController(GameServer server) {
        try {
            AppLogger.info("Rotation", "启动悬浮方向控制服务；服务器=" +
                    server.getPackageName());
            RotationControllerService.start(this, server.getPackageName());
            rotationStatus.setText(R.string.rotation_status_overlay_active);
            Toast.makeText(
                    this, R.string.rotation_overlay_starting, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            AppLogger.error("Rotation", "启动悬浮方向控制服务失败", error);
            showError(getString(R.string.rotation_service_start_failed, safeMessage(error)));
        }
    }

    private void restoreAutomaticRotation() {
        if (!privilegeState.isReady()) {
            showError(getString(R.string.permission_required));
            return;
        }
        if (repository.isRotationSessionActive()) {
            rotationStatus.setText(R.string.rotation_status_restoring);
            RotationControllerService.requestRecovery(this);
            Toast.makeText(this, R.string.rotation_recovery_status, Toast.LENGTH_LONG).show();
            return;
        }
        runRotationAction(
                getString(R.string.progress_restore_rotation),
                repository::restoreAutomaticRotation,
                () -> {
                    stopService(new Intent(this, RotationControllerService.class));
                    rotationStatus.setText(R.string.rotation_status_auto);
                    Toast.makeText(this, R.string.rotation_restored, Toast.LENGTH_LONG).show();
                });
    }

    private void runRotationAction(
            String progressMessage, ThrowingAction action, Runnable onSuccess) {
        AppLogger.info("Rotation", "开始执行：" + progressMessage);
        operationRunning = true;
        refreshUi();
        showProgress(progressMessage);
        worker.execute(() -> {
            try {
                action.run();
                AppLogger.event("Rotation", "执行成功：" + progressMessage);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    onSuccess.run();
                });
            } catch (Exception error) {
                AppLogger.error("Rotation", "执行失败：" + progressMessage, error);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    finishOperation();
                    showError(safeMessage(error));
                });
            }
        });
    }

    private void openLogManagerWithPermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                        this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            showPanelDialog(
                    getString(R.string.log_diagnostics_title),
                    getString(R.string.log_permission_required),
                    DialogTone.INFO,
                    R.string.cancel,
                    this::openLogManager,
                    R.string.continue_action,
                    () -> legacyLogPermissionLauncher.launch(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    false);
            return;
        }
        AppLogger.tryEnablePublicLogging();
        openLogManager();
    }

    private void refreshLogSummary() {
        if (logManagerStatus == null) return;
        worker.execute(() -> {
            List<LogEntry> logs = AppLogger.listLogs(this);
            long totalBytes = 0L;
            for (LogEntry entry : logs) totalBytes += entry.sizeBytes;
            long finalTotalBytes = totalBytes;
            mainHandler.post(() -> {
                if (destroyed || logManagerStatus == null) return;
                logManagerStatus.setText(getString(
                        R.string.log_manager_status_format,
                        logs.size(), formatBytes(finalTotalBytes)));
            });
        });
    }

    private void openLogManager() {
        worker.execute(() -> {
            List<LogEntry> logs = AppLogger.listLogs(this);
            mainHandler.post(() -> {
                if (destroyed) return;
                showLogManagerDialog(logs);
                refreshLogSummary();
            });
        });
    }

    private void showLogManagerDialog(List<LogEntry> logs) {
        View content = getLayoutInflater().inflate(R.layout.dialog_log_manager, null, false);
        LinearLayout list = content.findViewById(R.id.log_list);
        View empty = content.findViewById(R.id.log_list_empty);
        View scroll = content.findViewById(R.id.log_list_scroll);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();

        if (logs.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            scroll.setVisibility(View.GONE);
        } else {
            SimpleDateFormat timeFormat = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            for (LogEntry entry : logs) {
                View row = getLayoutInflater().inflate(
                        R.layout.item_log_file, list, false);
                TextView name = row.findViewById(R.id.log_file_name);
                TextView detail = row.findViewById(R.id.log_file_detail);
                MaterialButton share = row.findViewById(R.id.log_share_button);
                MaterialButton delete = row.findViewById(R.id.log_delete_button);
                name.setText(entry.currentSession
                        ? getString(R.string.log_current_session, entry.displayName)
                        : entry.displayName);
                detail.setText(getString(
                        R.string.log_file_detail_format,
                        timeFormat.format(new Date(entry.modifiedTimeMillis)),
                        formatBytes(entry.sizeBytes)));
                share.setOnClickListener(view -> shareLogs(
                        java.util.Collections.singletonList(entry.contentUri)));
                if (entry.currentSession) {
                    delete.setEnabled(false);
                    delete.setAlpha(0.38f);
                    delete.setOnClickListener(view -> Toast.makeText(
                            this, R.string.log_current_protected,
                            Toast.LENGTH_SHORT).show());
                } else {
                    delete.setOnClickListener(view -> {
                        dialog.dismiss();
                        confirmDeleteLog(entry);
                    });
                }
                list.addView(row);
            }
        }

        content.findViewById(R.id.share_current_log_button).setOnClickListener(view -> {
            Uri current = AppLogger.getCurrentShareUri(this);
            if (current == null) {
                Toast.makeText(this, R.string.log_share_failed, Toast.LENGTH_LONG).show();
            } else {
                shareLogs(java.util.Collections.singletonList(current));
            }
        });
        MaterialButton shareAll = content.findViewById(R.id.share_all_logs_button);
        shareAll.setEnabled(!logs.isEmpty());
        shareAll.setOnClickListener(view -> {
            List<Uri> uris = new ArrayList<>();
            for (LogEntry entry : logs) uris.add(entry.contentUri);
            shareLogs(uris);
        });
        content.findViewById(R.id.log_manager_close)
                .setOnClickListener(view -> dialog.dismiss());
        dialog.show();
        animateDialogEntrance(dialog);
        capDialogScrollArea(scroll, 0.34f, 260);
        animateListChildren(list);
    }

    private void confirmDeleteLog(LogEntry entry) {
        showPanelDialog(
                getString(R.string.delete_log_title),
                getString(R.string.delete_log_message, entry.displayName),
                DialogTone.WARNING,
                R.string.cancel,
                this::openLogManager,
                R.string.delete_log,
                () -> worker.execute(() -> {
                    boolean deleted = AppLogger.deleteLog(this, entry);
                    AppLogger.info("Logs", "删除日志 " + entry.displayName +
                            "，结果=" + deleted);
                    mainHandler.post(() -> {
                        if (destroyed) return;
                        Toast.makeText(this,
                                deleted ? R.string.log_deleted : R.string.log_delete_failed,
                                Toast.LENGTH_SHORT).show();
                        openLogManager();
                    });
                }),
                true);
    }

    private void shareLogs(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            Toast.makeText(this, R.string.log_share_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent share;
            if (uris.size() == 1) {
                share = new Intent(Intent.ACTION_SEND)
                        .putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                share = new Intent(Intent.ACTION_SEND_MULTIPLE)
                        .putParcelableArrayListExtra(
                                Intent.EXTRA_STREAM, new ArrayList<>(uris));
            }
            share.setType("text/plain");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clipData = ClipData.newRawUri("Wuwa CFBP log", uris.get(0));
            for (int i = 1; i < uris.size(); i++) {
                clipData.addItem(new ClipData.Item(uris.get(i)));
            }
            share.setClipData(clipData);
            AppLogger.info("Logs", "分享日志，数量=" + uris.size());
            startActivity(Intent.createChooser(
                    share, getString(R.string.log_share_chooser)));
        } catch (RuntimeException error) {
            AppLogger.error("Logs", "分享日志失败", error);
            Toast.makeText(this, R.string.log_share_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void openAnnouncementCenter() {
        if (!isRemoteNoticeConfigured()) {
            showPanelDialog(
                    getString(R.string.announcement_service_unconfigured_title),
                    getString(R.string.announcement_service_unconfigured_message),
                    DialogTone.INFO, 0, null, R.string.done, null, false);
            return;
        }
        RemoteNoticePayload.Announcement announcement = lastRemotePayload == null
                ? null : lastRemotePayload.getAnnouncement();
        if (isUsableAnnouncement(announcement)) {
            showRemoteAnnouncement(announcement);
        } else {
            requestRemoteManifest(true, true);
        }
    }

    private void checkRemoteUpdates(boolean userInitiated) {
        if (!preferences.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) return;
        if (!isRemoteNoticeConfigured()) {
            updateRemoteFrameworkStatus();
            if (userInitiated) {
                showPanelDialog(
                        getString(R.string.announcement_service_unconfigured_title),
                        getString(R.string.announcement_service_unconfigured_message),
                        DialogTone.INFO, 0, null, R.string.done, null, false);
            }
            return;
        }
        if (!userInitiated && automaticRemoteCheckPerformed) return;
        requestRemoteManifest(userInitiated, false);
    }

    private boolean isRemoteNoticeConfigured() {
        return remoteNoticeRepository != null && remoteNoticeRepository.isConfigured();
    }

    private void updateRemoteFrameworkStatus() {
        if (announcementStatus == null || settingsUpdateStatus == null) return;
        if (!isRemoteNoticeConfigured()) {
            announcementStatus.setText(R.string.announcement_service_unconfigured);
            setUpdateStatusText(getString(
                    R.string.current_version_click_check, BuildConfig.VERSION_NAME));
        }
    }

    private void setUpdateStatusText(CharSequence status) {
        if (settingsUpdateStatus != null) settingsUpdateStatus.setText(status);
    }

    private void requestRemoteManifest(boolean userInitiated, boolean announcementOnly) {
        if (remoteCheckRunning || !isRemoteNoticeConfigured()) return;
        AppLogger.info("RemoteNotice", "Manifest request started; userInitiated="
                + userInitiated + ", announcementOnly=" + announcementOnly);
        if (!userInitiated && !announcementOnly) automaticRemoteCheckPerformed = true;
        remoteCheckRunning = true;
        if (announcementOnly) {
            announcementStatus.setText(R.string.announcement_loading);
        } else {
            setUpdateStatusText(getString(R.string.checking_updates));
        }
        performRemoteManifestRequest(userInitiated, announcementOnly, 0);
    }

    private void performRemoteManifestRequest(
            boolean userInitiated, boolean announcementOnly, int attempt) {
        worker.execute(() -> {
            try {
                RemoteNoticeRepository.FetchResult fetchResult = remoteNoticeRepository.fetch();
                mainHandler.post(() -> {
                    if (destroyed) return;
                    if (fetchResult.getSource() == RemoteNoticeRepository.Source.STALE_CACHE
                            && attempt < REMOTE_MAX_RETRIES) {
                        setUpdateStatusText(getString(R.string.update_check_retrying));
                        AppLogger.warn("RemoteNotice", "Network unavailable; retrying manifest");
                        mainHandler.postDelayed(() -> performRemoteManifestRequest(
                                userInitiated, announcementOnly, attempt + 1),
                                REMOTE_RETRY_DELAY_MS);
                        return;
                    }
                    remoteCheckRunning = false;
                    RemoteNoticePayload payload = fetchResult.getPayload();
                    lastRemotePayload = payload;
                    lastRemoteFetchResult = fetchResult;
                    RemoteNoticePayload.Announcement announcement = payload.getAnnouncement();
                    RemoteNoticePayload.Update update = payload.getUpdate();
                    Log.i(TAG, "Manifest loaded: announcement="
                            + (announcement != null && announcement.isEnabled())
                            + ", id=" + (announcement == null ? "" : announcement.getId())
                            + ", latestCode=" + (update == null ? 0L : update.getLatestVersionCode()));
                    AppLogger.info("RemoteNotice", "Manifest loaded; announcement="
                            + (announcement != null && announcement.isEnabled())
                            + ", id=" + (announcement == null ? "" : announcement.getId())
                            + ", latestCode=" + (update == null ? 0L
                            : update.getLatestVersionCode()));
                    applyRemoteManifest(fetchResult, userInitiated, announcementOnly);
                });
            } catch (Exception error) {
                Log.w(TAG, "Manifest request failed", error);
                AppLogger.error("RemoteNotice", "Manifest request failed", error);
                mainHandler.post(() -> {
                    if (destroyed) return;
                    if (attempt < REMOTE_MAX_RETRIES) {
                        setUpdateStatusText(getString(R.string.update_check_retrying));
                        mainHandler.postDelayed(() -> performRemoteManifestRequest(
                                userInitiated, announcementOnly, attempt + 1),
                                REMOTE_RETRY_DELAY_MS);
                        return;
                    }
                    remoteCheckRunning = false;
                    announcementStatus.setText(R.string.announcement_check_failed);
                    setUpdateStatusText(getString(R.string.update_check_failed));
                    if (userInitiated) {
                        showPanelDialog(
                                getString(R.string.update_check_failed),
                                getString(R.string.update_check_failed_message_detail,
                                        safeMessage(error)),
                                DialogTone.WARNING, 0, null, R.string.done, null, false);
                    }
                });
            }
        });
    }

    private void applyRemoteManifest(
            RemoteNoticeRepository.FetchResult fetchResult,
            boolean userInitiated,
            boolean announcementOnly) {
        RemoteNoticePayload payload = fetchResult.getPayload();
        long now = System.currentTimeMillis();
        boolean trustedForLocking = fetchResult.isNetworkConfirmed()
                || RemoteNoticeEvaluator.isCacheFreshEnough(
                        fetchResult.getValidatedAtMillis(), now,
                        REMOTE_LOCK_CACHE_MAX_AGE_MS);
        RemoteNoticePayload.SecurityMode securityMode = payload.getSecurityMode();
        if (trustedForLocking && isUsableSecurityMode(securityMode)) {
            showCloudSecurityMode(securityMode, payload.getRevision());
            return;
        }
        dismissCloudSecurityMode();
        RemoteNoticePayload.Announcement announcement = payload.getAnnouncement();
        RemoteNoticePayload.Update update = payload.getUpdate();
        announcementStatus.setText(isUsableAnnouncement(announcement)
                ? announcement.getTitle() : getString(R.string.announcement_empty));

        boolean updateAvailable = trustedForLocking && isUsableUpdate(update);
        if (!updateAvailable && mandatoryUpdateDialog != null) {
            mandatoryUpdateDialog.dismiss();
            mandatoryUpdateDialog = null;
        }
        if (updateAvailable) {
            String version = displayRemoteVersion(update);
            setUpdateStatusText(getString(R.string.update_available_format, version));
        } else if (!fetchResult.isNetworkConfirmed()) {
            setUpdateStatusText(getString(R.string.update_cache_status));
        } else {
            setUpdateStatusText(getString(
                    R.string.latest_version_ready, BuildConfig.VERSION_NAME));
        }

        if (!preferences.getBoolean(KEY_FIRST_RUN_NOTICE_ACCEPTED, false)) return;

        if (announcementOnly) {
            if (isUsableAnnouncement(announcement)) {
                showRemoteAnnouncement(announcement);
            } else {
                showPanelDialog(
                        getString(R.string.announcement_center),
                        getString(R.string.announcement_empty),
                        DialogTone.INFO, 0, null, R.string.done, null, false);
            }
            return;
        }

        if (updateAvailable) {
            long ignoredVersion = preferences.getLong(KEY_IGNORED_UPDATE_VERSION_CODE, 0L);
            if (userInitiated || isMandatoryUpdate(update)
                    || update.getLatestVersionCode() != ignoredVersion) {
                showRemoteUpdate(update);
            }
            return;
        }
        if (!userInitiated && shouldAutomaticallyShow(announcement)) {
            showRemoteAnnouncement(announcement);
            return;
        }
        if (userInitiated) {
            if (!fetchResult.isNetworkConfirmed()) {
                showPanelDialog(
                        getString(R.string.update_check_unconfirmed_title),
                        getString(R.string.update_check_unconfirmed_message),
                        DialogTone.WARNING, 0, null, R.string.done, null, false);
                return;
            }
            showPanelDialog(
                    getString(R.string.no_update_title),
                    getString(R.string.no_update_message,
                            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    DialogTone.SUCCESS, 0, null, R.string.done, null, false);
        }
    }

    private boolean isUsableAnnouncement(RemoteNoticePayload.Announcement announcement) {
        return announcement != null
                && announcement.isEnabled()
                && !TextUtils.isEmpty(announcement.getTitle())
                && !TextUtils.isEmpty(announcement.getContent())
                && RemoteNoticeEvaluator.isActiveWindow(
                        announcement.getEffectiveAt(),
                        announcement.getExpiresAt(),
                        System.currentTimeMillis());
    }

    private boolean isUsableSecurityMode(RemoteNoticePayload.SecurityMode securityMode) {
        if (securityMode == null || !securityMode.isEnabled()
                || TextUtils.isEmpty(securityMode.getExpiresAt())) return false;
        long current = BuildConfig.VERSION_CODE;
        if (securityMode.getMinimumVersionCode() > 0L
                && current < securityMode.getMinimumVersionCode()) return false;
        if (securityMode.getMaximumVersionCode() > 0L
                && current > securityMode.getMaximumVersionCode()) return false;
        return RemoteNoticeEvaluator.isActiveWindow(
                securityMode.getEffectiveAt(), securityMode.getExpiresAt(),
                System.currentTimeMillis());
    }

    private boolean isUsableUpdate(RemoteNoticePayload.Update update) {
        if (update == null
                || !update.isEnabled()
                || update.isPaused()
                || !RemoteNoticeEvaluator.hasExplicitUpdateAudience(
                        update.getAppliesToMinimumVersionCode(),
                        update.getAppliesToMaximumVersionCode())
                || !RemoteNoticeEvaluator.isUpdateApplicable(
                        BuildConfig.VERSION_CODE,
                        update.getLatestVersionCode(),
                        update.getAppliesToMinimumVersionCode(),
                        update.getAppliesToMaximumVersionCode())
                || !RemoteNoticeEvaluator.isActiveWindow(
                        update.getEffectiveAt(), update.getExpiresAt(), System.currentTimeMillis())) {
            return false;
        }
        Uri uri = Uri.parse(update.getUrl());
        boolean safeUrl = "https".equalsIgnoreCase(uri.getScheme()) && !TextUtils.isEmpty(uri.getHost());
        if (!safeUrl) {
            AppLogger.warn("RemoteNotice", "Ignored update with invalid HTTPS URL");
        }
        return safeUrl;
    }

    private boolean shouldAutomaticallyShow(RemoteNoticePayload.Announcement announcement) {
        if (!isUsableAnnouncement(announcement)) return false;
        String policy = announcement.getDisplayPolicy();
        if (TextUtils.isEmpty(announcement.getId())) return false;
        if ("every_start".equalsIgnoreCase(policy)) {
            return !announcementsShownThisSession.contains(announcement.getId());
        }
        if ("daily".equalsIgnoreCase(policy)) {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
            return !announcement.getId().equals(
                    preferences.getString(KEY_LAST_DAILY_ANNOUNCEMENT_ID, ""))
                    || !today.equals(preferences.getString(
                            KEY_LAST_DAILY_ANNOUNCEMENT_DATE, ""));
        }
        return !announcement.getId().equals(
                preferences.getString(KEY_LAST_ANNOUNCEMENT_ID, ""));
    }

    private void showRemoteAnnouncement(RemoteNoticePayload.Announcement announcement) {
        String metadata = TextUtils.isEmpty(announcement.getPublishedAt())
                ? getString(R.string.remote_announcement_label)
                : getString(R.string.published_format,
                        formatNoticeTimestamp(announcement.getPublishedAt()));
        boolean hasAction = !TextUtils.isEmpty(announcement.getActionLabel())
                && isSafeHttpsUrl(announcement.getActionUrl());
        AlertDialog dialog = buildRemoteNoticeDialog(
                announcementIcon(announcement.getType()),
                announcement.getTitle(),
                metadata,
                announcementTypeLabel(announcement.getType()),
                styledAnnouncementBody(announcement),
                false,
                hasAction ? announcement.getActionLabel()
                        : getString(R.string.announcement_got_it),
                hasAction ? () -> openAnnouncementAction(announcement) : null,
                hasAction ? R.string.close : 0,
                null);
        dialog.setOnDismissListener(ignored -> {
            markAnnouncementShown(announcement);
        });
    }

    private void markAnnouncementShown(RemoteNoticePayload.Announcement announcement) {
        if (TextUtils.isEmpty(announcement.getId())) return;
        announcementsShownThisSession.add(announcement.getId());
        String policy = announcement.getDisplayPolicy();
        if ("every_start".equalsIgnoreCase(policy)) return;
        SharedPreferences.Editor editor = preferences.edit();
        if ("daily".equalsIgnoreCase(policy)) {
            editor.putString(KEY_LAST_DAILY_ANNOUNCEMENT_ID, announcement.getId());
            editor.putString(KEY_LAST_DAILY_ANNOUNCEMENT_DATE,
                    new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()));
        } else {
            editor.putString(KEY_LAST_ANNOUNCEMENT_ID, announcement.getId());
        }
        editor.apply();
    }

    private int announcementIcon(String type) {
        if ("important".equalsIgnoreCase(type) || "maintenance".equalsIgnoreCase(type)) {
            return R.drawable.ic_warning;
        }
        if ("version_update".equalsIgnoreCase(type)) return R.drawable.ic_update;
        return R.drawable.ic_campaign;
    }

    private String announcementTypeLabel(String type) {
        if ("important".equalsIgnoreCase(type)) return getString(R.string.announcement_type_important);
        if ("maintenance".equalsIgnoreCase(type)) return getString(R.string.announcement_type_maintenance);
        if ("version_update".equalsIgnoreCase(type)) return getString(R.string.announcement_type_version_update);
        return getString(R.string.announcement_type_normal);
    }

    @SuppressLint("InlinedApi")
    private CharSequence styledAnnouncementBody(RemoteNoticePayload.Announcement announcement) {
        String content = announcement.getContent();
        String highlight = announcement.getHighlightText();
        if (TextUtils.isEmpty(highlight) || !content.contains(highlight)) return content;
        int color;
        switch (announcement.getHighlightColor().toLowerCase(Locale.ROOT)) {
            case "red":
                color = MaterialColors.getColor(this,
                        android.R.attr.colorError, getColor(R.color.wuwa_error));
                break;
            case "orange":
                color = ContextCompat.getColor(this, R.color.wuwa_warning);
                break;
            case "green":
                color = ContextCompat.getColor(this, R.color.wuwa_success);
                break;
            default:
                color = MaterialColors.getColor(this,
                        android.R.attr.colorAccent, getColor(R.color.wuwa_primary));
                break;
        }
        SpannableString styled = new SpannableString(content);
        int start = 0;
        while ((start = content.indexOf(highlight, start)) >= 0) {
            int end = start + highlight.length();
            styled.setSpan(new ForegroundColorSpan(color), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end;
        }
        return styled;
    }

    private boolean isSafeHttpsUrl(String rawUrl) {
        if (TextUtils.isEmpty(rawUrl)) return false;
        Uri uri = Uri.parse(rawUrl.trim());
        return "https".equalsIgnoreCase(uri.getScheme()) && !TextUtils.isEmpty(uri.getHost());
    }

    private void openAnnouncementAction(RemoteNoticePayload.Announcement announcement) {
        if (!announcement.isActionConfirm()) {
            openExternalLink(announcement.getActionUrl(), false);
            return;
        }
        Uri uri = Uri.parse(announcement.getActionUrl());
        showPanelDialog(
                getString(R.string.external_link_confirmation_title),
                getString(R.string.external_link_confirmation_message, uri.getHost()),
                DialogTone.INFO,
                R.string.cancel,
                null,
                R.string.continue_open,
                () -> openExternalLink(announcement.getActionUrl(), false),
                false);
    }

    private void showRemoteUpdate(RemoteNoticePayload.Update update) {
        String version = displayRemoteVersion(update);
        boolean mandatory = isMandatoryUpdate(update);
        if (mandatory && mandatoryUpdateDialog != null
                && mandatoryUpdateDialog.isShowing()) return;
        String title = TextUtils.isEmpty(update.getTitle())
                ? getString(R.string.update_available_format, version) : update.getTitle();
        String content = TextUtils.isEmpty(update.getContent())
                ? getString(R.string.update_available_format, version) : update.getContent();
        View panel = getLayoutInflater().inflate(R.layout.dialog_remote_update, null, false);
        ((TextView) panel.findViewById(R.id.remote_update_title)).setText(title);
        ((TextView) panel.findViewById(R.id.remote_update_badge)).setText(
                mandatory ? R.string.required_update : R.string.optional_update);
        ((TextView) panel.findViewById(R.id.remote_update_current_version)).setText(
                getString(R.string.version_with_code,
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        ((TextView) panel.findViewById(R.id.remote_update_target_version)).setText(
                getString(R.string.version_with_code,
                        version, update.getLatestVersionCode()));
        ((TextView) panel.findViewById(R.id.remote_update_content)).setText(content);
        setOptionalUpdateDetail(panel, R.id.remote_update_published,
                TextUtils.isEmpty(update.getPublishedAt()) ? ""
                        : getString(R.string.update_published_detail,
                                formatNoticeTimestamp(update.getPublishedAt())));
        setOptionalUpdateDetail(panel, R.id.remote_update_size,
                update.getApkSizeBytes() <= 0L ? ""
                        : getString(R.string.update_size_detail,
                                formatFileSize(update.getApkSizeBytes())));
        setOptionalUpdateDetail(panel, R.id.remote_update_channel,
                TextUtils.isEmpty(update.getDownloadChannel()) ? ""
                        : getString(R.string.update_channel_detail,
                                update.getDownloadChannel()));
        setOptionalUpdateDetail(panel, R.id.remote_update_sha256,
                TextUtils.isEmpty(update.getSha256()) ? ""
                        : getString(R.string.update_sha256_detail,
                                abbreviateHash(update.getSha256())));

        MaterialButton ignore = panel.findViewById(R.id.remote_update_ignore);
        MaterialButton secondary = panel.findViewById(R.id.remote_update_secondary);
        MaterialButton primary = panel.findViewById(R.id.remote_update_primary);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(panel)
                .setCancelable(!mandatory)
                .create();
        dialog.setCanceledOnTouchOutside(!mandatory);
        ignore.setVisibility(mandatory ? View.GONE : View.VISIBLE);
        ignore.setOnClickListener(view -> {
            preferences.edit().putLong(KEY_IGNORED_UPDATE_VERSION_CODE,
                    update.getLatestVersionCode()).apply();
            dialog.dismiss();
        });
        secondary.setText(mandatory ? R.string.refresh_update_status : R.string.later);
        secondary.setOnClickListener(view -> {
            if (mandatory) {
                requestRemoteManifest(true, false);
            } else {
                dialog.dismiss();
            }
        });
        primary.setOnClickListener(view -> {
            openExternalLink(update.getUrl(), false);
            if (!mandatory) dialog.dismiss();
        });
        dialog.setOnDismissListener(ignored -> {
            if (mandatoryUpdateDialog == dialog) mandatoryUpdateDialog = null;
        });
        dialog.show();
        capDialogScrollArea(panel.findViewById(R.id.remote_update_scroll), 0.34f, 260);
        if (mandatory) mandatoryUpdateDialog = dialog;
        animateDialogEntrance(dialog);
    }

    private boolean isMandatoryUpdate(RemoteNoticePayload.Update update) {
        return update != null && (update.isForce()
                || BuildConfig.VERSION_CODE < update.getMinimumSupportedVersionCode());
    }

    private void setOptionalUpdateDetail(View panel, int viewId, String value) {
        TextView view = panel.findViewById(viewId);
        if (TextUtils.isEmpty(value)) {
            view.setVisibility(View.GONE);
        } else {
            view.setText(value);
            view.setVisibility(View.VISIBLE);
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024f * 1024f));
    }

    private String abbreviateHash(String hash) {
        String normalized = hash == null ? "" : hash.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() <= 20) return normalized;
        return normalized.substring(0, 12) + "…" + normalized.substring(normalized.length() - 8);
    }

    private String displayRemoteVersion(RemoteNoticePayload.Update update) {
        return TextUtils.isEmpty(update.getLatestVersionName())
                ? String.valueOf(update.getLatestVersionCode())
                : update.getLatestVersionName();
    }

    private AlertDialog buildRemoteNoticeDialog(
            int iconResource,
            CharSequence title,
            CharSequence metadata,
            CharSequence badge,
            CharSequence body,
            boolean mandatory,
            CharSequence positiveText,
            Runnable positiveAction,
            int negativeText,
            Runnable negativeAction) {
        View content = getLayoutInflater().inflate(
                R.layout.dialog_remote_notice, null, false);
        ((ImageView) content.findViewById(R.id.remote_notice_icon))
                .setImageResource(iconResource);
        ((TextView) content.findViewById(R.id.remote_notice_title)).setText(title);
        ((TextView) content.findViewById(R.id.remote_notice_meta)).setText(metadata);
        TextView badgeView = content.findViewById(R.id.remote_notice_badge);
        if (TextUtils.isEmpty(badge)) {
            badgeView.setVisibility(View.GONE);
        } else {
            badgeView.setText(badge);
            badgeView.setVisibility(View.VISIBLE);
        }
        ((TextView) content.findViewById(R.id.remote_notice_content)).setText(body);
        MaterialButton positive = content.findViewById(R.id.remote_notice_positive);
        MaterialButton negative = content.findViewById(R.id.remote_notice_negative);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setCancelable(!mandatory)
                .create();
        dialog.setCanceledOnTouchOutside(!mandatory);
        positive.setText(positiveText);
        positive.setOnClickListener(view -> {
            if (positiveAction != null) positiveAction.run();
            if (!mandatory || positiveAction == null) dialog.dismiss();
        });
        if (negativeText == 0) {
            negative.setVisibility(View.GONE);
        } else {
            negative.setText(negativeText);
            negative.setOnClickListener(view -> {
                if (negativeAction != null) negativeAction.run();
                dialog.dismiss();
            });
        }
        dialog.show();
        capDialogScrollArea(content.findViewById(R.id.remote_notice_scroll), 0.42f, 360);
        Window window = dialog.getWindow();
        if (window != null) {
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            if (width > height) {
                int maxWidth = (int) (720 * getResources().getDisplayMetrics().density);
                window.setLayout(Math.min((int) (width * 0.86f), maxWidth),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }
        animateDialogEntrance(dialog);
        return dialog;
    }

    private void openConfiguredLink(String url) {
        if (TextUtils.isEmpty(url == null ? "" : url.trim())) {
            showPanelDialog(
                    getString(R.string.support_link_unconfigured_title),
                    getString(R.string.support_link_unconfigured_message),
                    DialogTone.INFO, 0, null, R.string.done, null, false);
            return;
        }
        openExternalLink(url, true);
    }

    private boolean openExternalLink(String rawUrl, boolean allowAppSchemes) {
        if (TextUtils.isEmpty(rawUrl)) {
            showPanelDialog(
                    getString(R.string.support_link_unconfigured_title),
                    getString(R.string.support_link_unconfigured_message),
                    DialogTone.INFO, 0, null, R.string.done, null, false);
            return false;
        }
        Uri uri = Uri.parse(rawUrl.trim());
        String scheme = uri.getScheme();
        boolean safeWeb = "https".equalsIgnoreCase(scheme);
        boolean safeApp = allowAppSchemes && ("bilibili".equalsIgnoreCase(scheme)
                || "mqqapi".equalsIgnoreCase(scheme));
        if (!safeWeb && !safeApp) {
            showPanelDialog(
                    getString(R.string.link_open_failed),
                    getString(R.string.link_open_failed),
                    DialogTone.WARNING, 0, null, R.string.done, null, false);
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            return true;
        } catch (RuntimeException error) {
            showPanelDialog(
                    getString(R.string.link_open_failed),
                    getString(R.string.link_open_failed),
                    DialogTone.WARNING, 0, null, R.string.done, null, false);
            return false;
        }
    }

    @SuppressLint("InlinedApi")
    private AlertDialog showPanelDialog(
            CharSequence title,
            CharSequence message,
            DialogTone tone,
            int negativeText,
            Runnable negativeAction,
            int positiveText,
            Runnable positiveAction,
            boolean destructive) {
        View content = getLayoutInflater().inflate(R.layout.dialog_message_panel, null, false);
        MaterialCardView iconCard = content.findViewById(R.id.dialog_panel_icon_card);
        ImageView icon = content.findViewById(R.id.dialog_panel_icon);
        TextView titleView = content.findViewById(R.id.dialog_panel_title);
        TextView messageView = content.findViewById(R.id.dialog_panel_message);
        View messageScroll = content.findViewById(R.id.dialog_panel_message_scroll);
        MaterialButton negative = content.findViewById(R.id.dialog_panel_negative);
        MaterialButton positive = content.findViewById(R.id.dialog_panel_positive);

        titleView.setText(title);
        if (TextUtils.isEmpty(message)) {
            messageScroll.setVisibility(View.GONE);
        } else {
            messageView.setText(message);
        }

        int iconResource;
        int accent;
        int container;
        switch (tone) {
            case SUCCESS:
                iconResource = R.drawable.ic_check;
                accent = resolveStatusColor(content, R.color.wuwa_success);
                container = resolveStatusColor(content, R.color.wuwa_success_container);
                break;
            case WARNING:
                iconResource = R.drawable.ic_warning;
                accent = resolveStatusColor(content, R.color.wuwa_warning);
                container = MaterialColors.getColor(
                        content,
                        com.google.android.material.R.attr.colorSecondaryContainer,
                        getColor(R.color.wuwa_surface_container_high));
                break;
            case ERROR:
                iconResource = R.drawable.ic_warning;
                accent = MaterialColors.getColor(
                        content, android.R.attr.colorError,
                        getColor(R.color.wuwa_error));
                container = MaterialColors.getColor(
                        content, com.google.android.material.R.attr.colorErrorContainer,
                        getColor(R.color.wuwa_error_container));
                break;
            case INFO:
            default:
                iconResource = R.drawable.ic_info;
                accent = MaterialColors.getColor(
                        content, androidx.appcompat.R.attr.colorPrimary,
                        getColor(R.color.wuwa_primary));
                container = MaterialColors.getColor(
                        content, com.google.android.material.R.attr.colorPrimaryContainer,
                        getColor(R.color.wuwa_primary_container));
                break;
        }
        icon.setImageResource(iconResource);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        iconCard.setCardBackgroundColor(container);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();
        if (negativeText == 0) {
            negative.setVisibility(View.GONE);
        } else {
            negative.setText(negativeText);
            negative.setOnClickListener(view -> {
                dialog.dismiss();
                if (negativeAction != null) negativeAction.run();
            });
        }
        positive.setText(positiveText == 0 ? R.string.done : positiveText);
        if (destructive) {
            int error = MaterialColors.getColor(
                    content, android.R.attr.colorError,
                    getColor(R.color.wuwa_error));
            int onError = MaterialColors.getColor(
                    content, com.google.android.material.R.attr.colorOnError,
                    getColor(R.color.wuwa_on_primary));
            positive.setBackgroundTintList(ColorStateList.valueOf(error));
            positive.setTextColor(onError);
        }
        positive.setOnClickListener(view -> {
            dialog.dismiss();
            if (positiveAction != null) positiveAction.run();
        });
        dialog.show();
        animateDialogEntrance(dialog);
        return dialog;
    }

    private AlertDialog showChoicePanel(
            CharSequence title,
            CharSequence description,
            CharSequence[] labels,
            CharSequence[] details,
            int[] icons,
            ChoiceAction choiceAction) {
        View content = getLayoutInflater().inflate(R.layout.dialog_choice_panel, null, false);
        TextView titleView = content.findViewById(R.id.dialog_choice_title);
        TextView descriptionView = content.findViewById(R.id.dialog_choice_description);
        LinearLayout list = content.findViewById(R.id.dialog_choice_list);
        titleView.setText(title);
        if (TextUtils.isEmpty(description)) {
            descriptionView.setVisibility(View.GONE);
        } else {
            descriptionView.setText(description);
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .create();
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            View row = getLayoutInflater().inflate(R.layout.item_dialog_choice, list, false);
            ((TextView) row.findViewById(R.id.dialog_choice_label)).setText(labels[i]);
            TextView detail = row.findViewById(R.id.dialog_choice_detail);
            if (details != null && i < details.length && !TextUtils.isEmpty(details[i])) {
                detail.setText(details[i]);
                detail.setVisibility(View.VISIBLE);
            }
            if (icons != null && i < icons.length && icons[i] != 0) {
                ((ImageView) row.findViewById(R.id.dialog_choice_icon)).setImageResource(icons[i]);
            }
            row.setOnClickListener(view -> {
                dialog.dismiss();
                choiceAction.onChoice(index);
            });
            list.addView(row);
        }
        content.findViewById(R.id.dialog_choice_cancel)
                .setOnClickListener(view -> dialog.dismiss());
        dialog.show();
        animateDialogEntrance(dialog);
        capDialogScrollArea(content.findViewById(R.id.dialog_choice_scroll), 0.50f, 380);
        animateListChildren(list);
        return dialog;
    }

    private void showProgress(String message) {
        dismissProgress();
        View content = getLayoutInflater().inflate(R.layout.dialog_progress, null, false);
        TextView label = content.findViewById(R.id.progress_message);
        label.setText(message);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setCancelable(false)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        progressDialog = dialog;
        dialog.show();
        animateDialogEntrance(dialog);
    }

    private void dismissProgress() {
        if (progressDialog == null) return;
        progressDialog.dismiss();
        progressDialog = null;
    }

    private void showError(String message) {
        AppLogger.error("UI", "向用户显示错误：" + message, null);
        boolean connectionProblem = message.contains("Shizuku")
                || message.contains("Root")
                || message.contains("权限")
                || message.contains("共享存储");
        showPanelDialog(
                getString(R.string.operation_failed),
                message,
                DialogTone.ERROR,
                connectionProblem ? R.string.done : 0,
                null,
                connectionProblem ? R.string.open_permission_settings : R.string.done,
                connectionProblem
                        ? () -> bottomNavigation.setSelectedItemId(R.id.navigation_settings)
                        : null,
                false);
    }

    private boolean animationsEnabled() {
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        try {
            return Settings.Global.getFloat(
                    getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private void animateDialogEntrance(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return;
        applyAdaptiveDialogWindow(dialog);
        if (!animationsEnabled()) return;
        View decor = dialog.getWindow().getDecorView();
        decor.animate().cancel();
        decor.setAlpha(0f);
        decor.setScaleX(0.97f);
        decor.setScaleY(0.97f);
        decor.post(() -> decor.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200L)
                .setInterpolator(new DecelerateInterpolator())
                .start());
    }

    /**
     * Give every custom dialog the same safe width instead of relying on the
     * device/OEM AlertDialog minimum width. The latter varies considerably with
     * display zoom, font scale and vendor themes and was the source of several
     * narrow or over-wide layouts on user devices.
     */
    private void applyAdaptiveDialogWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        float widthDp = screenWidth / getResources().getDisplayMetrics().density;
        int horizontalMargin = dp(widthDp < 360f ? 12 : 20);
        int maxWidth = dp(screenWidth > screenHeight ? 720 : 560);
        int availableWidth = Math.max(dp(240), screenWidth - horizontalMargin * 2);
        window.setLayout(Math.min(availableWidth, maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Caps variable-length dialog content to the current screen and keeps it scrollable. */
    private void capDialogScrollArea(View scrollView, float screenFraction, int maxHeightDp) {
        if (scrollView == null || scrollView.getVisibility() == View.GONE) return;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int cap = Math.min(dp(maxHeightDp), Math.max(dp(120), (int) (screenHeight * screenFraction)));
        scrollView.post(() -> {
            if (destroyed || scrollView.getVisibility() == View.GONE) return;
            ViewGroup.LayoutParams params = scrollView.getLayoutParams();
            int measured = scrollView.getMeasuredHeight();
            if (measured <= 0 || measured > cap || params.height > cap) {
                params.height = cap;
                scrollView.setLayoutParams(params);
            }
        });
    }

    private void animateListChildren(LinearLayout list) {
        if (!animationsEnabled() || list == null) return;
        int count = list.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = list.getChildAt(i);
            child.animate().cancel();
            child.setAlpha(0f);
            child.setTranslationY(dp(8));
            long delay = Math.min(i, 8) * 35L;
            child.postDelayed(() -> child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start(), delay);
        }
    }

    private void setStatus(TextView view, int textResource, int colorResource) {
        view.setText(textResource);
        view.setTextColor(resolveStatusColor(view, colorResource));
    }

    private void tint(ImageView view, int colorResource) {
        view.setImageTintList(ColorStateList.valueOf(resolveStatusColor(view, colorResource)));
    }

    private int resolveStatusColor(View view, int colorResource) {
        if (colorResource == R.color.wuwa_primary) {
            return MaterialColors.getColor(
                    view, androidx.appcompat.R.attr.colorPrimary, getColor(colorResource));
        }
        if (colorResource == R.color.wuwa_on_surface_variant) {
            return MaterialColors.getColor(view,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    getColor(colorResource));
        }
        if (colorResource == R.color.wuwa_error) {
            return MaterialColors.getColor(
                    view, androidx.appcompat.R.attr.colorError, getColor(colorResource));
        }
        if (colorResource == R.color.wuwa_success) {
            return MaterialColors.getColor(
                    view, com.google.android.material.R.attr.colorTertiary, getColor(colorResource));
        }
        if (colorResource == R.color.wuwa_success_container) {
            return MaterialColors.getColor(
                    view,
                    com.google.android.material.R.attr.colorTertiaryContainer,
                    getColor(colorResource));
        }
        return getColor(colorResource);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes / 1024d;
        if (value < 1024d) return String.format(Locale.getDefault(), "%.1f KB", value);
        value /= 1024d;
        if (value < 1024d) return String.format(Locale.getDefault(), "%.1f MB", value);
        value /= 1024d;
        return String.format(Locale.getDefault(), "%.2f GB", value);
    }

    private static String formatSnapshotName(String snapshotName) {
        if (snapshotName == null || snapshotName.length() != 19) return snapshotName;
        return snapshotName.substring(0, 4) + "-"
                + snapshotName.substring(4, 6) + "-"
                + snapshotName.substring(6, 8) + " "
                + snapshotName.substring(9, 11) + ":"
                + snapshotName.substring(11, 13) + ":"
                + snapshotName.substring(13, 15);
    }

    @Override
    protected void onDestroy() {
        AppLogger.info("Activity", "MainActivity onDestroy；方向服务=" +
                RotationControllerService.isRunning());
        destroyed = true;
        mainHandler.removeCallbacks(rotationRecoveryPoll);
        stopSystemLoadMonitor();
        if (firstRunTimer != null) firstRunTimer.cancel();
        firstRunTimer = null;
        dismissProgress();
        worker.shutdownNow();
        if (privilegeManager != null) {
            if (RotationControllerService.isRunning()) {
                privilegeManager.closePreservingUserService();
            } else {
                privilegeManager.close();
            }
        }
        super.onDestroy();
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private interface ChoiceAction {
        void onChoice(int index);
    }

    private enum DialogTone {
        INFO,
        SUCCESS,
        WARNING,
        ERROR
    }
}
