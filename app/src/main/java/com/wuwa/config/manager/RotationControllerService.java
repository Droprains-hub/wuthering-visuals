package com.wuwa.config.manager;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.ContextThemeWrapper;
import android.widget.Toast;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.wuwa.config.manager.data.ConfigRepository;
import com.wuwa.config.manager.diagnostics.AppLogger;
import com.wuwa.config.manager.model.GameServer;
import com.wuwa.config.manager.privilege.PrivilegeManager;
import com.wuwa.config.manager.privilege.PrivilegeState;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Game-scoped orientation controller with a small overlay. The user explicitly selects
 * portrait or landscape, and the exact pre-session system rotation preference is restored
 * when the controller exits or the game leaves the foreground.
 */
public final class RotationControllerService extends Service
        implements PrivilegeManager.Listener {
    private static final String TAG = "WuWaRotation";
    private static final String PREFS = "wuwa_config_preferences";
    private static final String KEY_DYNAMIC_COLORS = "dynamic_colors_enabled";
    private static final String KEY_APPEARANCE = "appearance_mode";
    private static final String APPEARANCE_LIGHT = "light";
    private static final String APPEARANCE_DARK = "dark";
    public static final String ACTION_START =
            "com.wuwa.config.manager.action.START_OVERLAY_ROTATION";
    public static final String ACTION_STOP =
            "com.wuwa.config.manager.action.STOP_OVERLAY_ROTATION";
    private static final String EXTRA_PACKAGE = "game_package";
    private static final String CHANNEL_ID = "rotation_controller";
    private static final int NOTIFICATION_ID = 4102;
    private static final long GAME_START_TIMEOUT_MILLIS = 35_000L;
    private static final long GAME_BACKGROUND_GRACE_MILLIS = 4_500L;
    private static final long RESTORE_RETRY_MILLIS = 3_000L;
    private static final int EXACT_RESTORE_RETRIES = 3;

    private static volatile boolean running;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService shellWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wuwa-rotation-shell");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean restoring = new AtomicBoolean();
    private final AtomicBoolean restoreCommandRunning = new AtomicBoolean();
    private final AtomicBoolean preparingSession = new AtomicBoolean();
    private final AtomicBoolean orientationCommandRunning = new AtomicBoolean();

    private PrivilegeManager privilegeManager;
    private ConfigRepository repository;
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private View overlayView;
    private View compactPanel;
    private View expandedPanel;
    private MaterialCardView landscapeCard;
    private MaterialCardView portraitCard;
    private TextView landscapeLabel;
    private TextView portraitLabel;
    private ImageView landscapeIcon;
    private ImageView portraitIcon;
    private TextView overlayStatus;
    private String gamePackage;
    private boolean privilegeReady;
    private boolean monitorQueryRunning;
    private boolean gameSeen;
    private volatile boolean explicitLaunchRequested;
    private long serviceStartedAt;
    private long gameBackgroundSince;
    private int currentRotation = Surface.ROTATION_0;
    private int restoreAttempts;

    public static void start(Context context, String packageName) {
        Intent intent = new Intent(context, RotationControllerService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PACKAGE, packageName);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, RotationControllerService.class)
                .setAction(ACTION_STOP);
        ContextCompat.startForegroundService(context, intent);
    }

    /**
     * Starts the same foreground service used for normal direction control in recovery mode.
     * Keeping restore commands in this service prevents activities, overlays and crash rescue
     * paths from each implementing subtly different global rotation writes.
     */
    public static void requestRecovery(Context context) {
        Intent intent = new Intent(context, RotationControllerService.class)
                .setAction(ACTION_STOP);
        ContextCompat.startForegroundService(context, intent);
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLogger.info("RotationService", "方向控制服务创建");
        running = true;
        createNotificationChannel();
        startAsForeground(getString(R.string.rotation_notification_preparing));
        privilegeManager = new PrivilegeManager(this);
        repository = new ConfigRepository(this, privilegeManager);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        privilegeManager.setListener(this);
        privilegeManager.requestAuthorization();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            restoreAndStop();
            return START_NOT_STICKY;
        }
        String requestedPackage = intent == null ? null : intent.getStringExtra(EXTRA_PACKAGE);
        explicitLaunchRequested = requestedPackage != null;
        gamePackage = requestedPackage == null
                ? repository.getRotationSessionPackage() : requestedPackage;
        AppLogger.info("RotationService", "收到启动命令；package=" + gamePackage +
                "；显式启动=" + explicitLaunchRequested);
        serviceStartedAt = SystemClock.elapsedRealtime();
        if (gamePackage == null) {
            restoreAndStop();
            return START_NOT_STICKY;
        }
        mainHandler.removeCallbacks(foregroundMonitor);
        mainHandler.post(foregroundMonitor);
        if (privilegeReady) continueOrStartSession();
        return START_STICKY;
    }

    @Override
    public void onPrivilegeStateChanged(PrivilegeState state) {
        privilegeReady = state.isReady();
        if (!privilegeReady) return;
        if (restoring.get()) {
            mainHandler.removeCallbacks(restoreRetry);
            mainHandler.post(this::attemptRestore);
        } else if (gamePackage != null) {
            continueOrStartSession();
        }
    }

    private void continueOrStartSession() {
        if (explicitLaunchRequested) {
            prepareSessionAndLaunchGame();
        } else if (repository.isRotationSessionActive()) {
            activateControllerAndMonitor();
        } else {
            Log.w(TAG, "Service restarted without an active rotation session");
            restoreAndStop();
        }
    }

    private void prepareSessionAndLaunchGame() {
        if (!preparingSession.compareAndSet(false, true)) return;
        GameServer server = GameServer.fromPackageName(gamePackage);
        if (server == null) {
            restoreAndStop();
            return;
        }
        shellWorker.execute(() -> {
            try {
                // beginSensorRotationSession restores any stale session before recording the
                // current system state. Never reuse an interrupted session for a new launch.
                repository.forceStop(server);
                repository.beginSensorRotationSession(server);
                ConfigRepository.RotationRecoveryInfo recoveryInfo =
                        repository.getPrivateRotationRecoveryInfo();
                boolean temporarilyDisabledAutoRotation = recoveryInfo != null
                        && recoveryInfo.automaticRotationEnabled;
                AppLogger.event("RotationService", "已保存原始旋转状态并锁定竖屏");
                mainHandler.post(() -> {
                    preparingSession.set(false);
                    if (restoring.get()) return;
                    explicitLaunchRequested = false;
                    if (!showOverlay()) {
                        Log.e(TAG, "Overlay could not be attached");
                        Toast.makeText(
                                this, R.string.rotation_toast_failed, Toast.LENGTH_LONG).show();
                        restoreAndStop();
                        return;
                    }
                    if (temporarilyDisabledAutoRotation) {
                        Toast.makeText(
                                this,
                                R.string.rotation_toast_auto_temporarily_disabled,
                                Toast.LENGTH_LONG).show();
                    }
                    activateControllerAndMonitor();
                    launchGameThroughShell(server);
                });
            } catch (Exception error) {
                AppLogger.error("RotationService", "准备方向控制会话失败", error);
                preparingSession.set(false);
                Log.e(TAG, "Failed to prepare portrait launch", error);
                mainHandler.post(() -> {
                    Toast.makeText(
                            this, R.string.rotation_toast_failed, Toast.LENGTH_LONG).show();
                    restoreAndStop();
                });
            }
        });
    }

    private void activateControllerAndMonitor() {
        if (!showOverlay()) {
            restoreAndStop();
            return;
        }
        serviceStartedAt = SystemClock.elapsedRealtime();
        updateNotification(getString(R.string.rotation_notification_active));
        mainHandler.removeCallbacks(foregroundMonitor);
        mainHandler.postDelayed(foregroundMonitor, 1_500L);
    }

    @SuppressLint("InflateParams")
    private boolean showOverlay() {
        if (overlayView != null) return true;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            // Some OEM builds report false here briefly even after AppOps has already
            // granted SYSTEM_ALERT_WINDOW. WindowManager remains the source of truth:
            // attempt the attach and handle SecurityException below instead of silently
            // cancelling an otherwise valid launch.
            Log.w(TAG, "Settings.canDrawOverlays returned false; attempting overlay attach");
        }
        try {
            SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
            Context overlayBase = getApplicationContext();
            String appearance = preferences.getString(KEY_APPEARANCE, APPEARANCE_LIGHT);
            if (APPEARANCE_LIGHT.equals(appearance) || APPEARANCE_DARK.equals(appearance)) {
                Configuration configuration = new Configuration(
                        overlayBase.getResources().getConfiguration());
                configuration.uiMode = (configuration.uiMode
                        & ~Configuration.UI_MODE_NIGHT_MASK)
                        | (APPEARANCE_DARK.equals(appearance)
                        ? Configuration.UI_MODE_NIGHT_YES
                        : Configuration.UI_MODE_NIGHT_NO);
                overlayBase = overlayBase.createConfigurationContext(configuration);
            }
            Context overlayContext = new ContextThemeWrapper(
                    overlayBase, R.style.Theme_WuWaConfigManager);
            if (preferences.getBoolean(KEY_DYNAMIC_COLORS, false)
                    && DynamicColors.isDynamicColorAvailable()) {
                overlayContext = DynamicColors.wrapContextIfAvailable(overlayContext);
            }
            WindowManager applicationWindowManager = (WindowManager)
                    getApplicationContext().getSystemService(WINDOW_SERVICE);
            if (applicationWindowManager == null) {
                Log.e(TAG, "Application WindowManager is unavailable");
                return false;
            }
            windowManager = applicationWindowManager;
            overlayView = LayoutInflater.from(overlayContext)
                    .inflate(R.layout.overlay_rotation_controller, null, false);
            compactPanel = overlayView.findViewById(R.id.rotation_overlay_compact);
            expandedPanel = overlayView.findViewById(R.id.rotation_overlay_expanded);
            landscapeCard = overlayView.findViewById(R.id.rotation_overlay_landscape_card);
            portraitCard = overlayView.findViewById(R.id.rotation_overlay_portrait_card);
            landscapeLabel = overlayView.findViewById(R.id.rotation_overlay_landscape_label);
            portraitLabel = overlayView.findViewById(R.id.rotation_overlay_portrait_label);
            landscapeIcon = overlayView.findViewById(R.id.rotation_overlay_landscape_icon);
            portraitIcon = overlayView.findViewById(R.id.rotation_overlay_portrait_icon);
            overlayStatus = overlayView.findViewById(R.id.rotation_overlay_status);
            overlayView.findViewById(R.id.rotation_overlay_landscape)
                    .setOnClickListener(view -> applyOrientation(Surface.ROTATION_90));
            overlayView.findViewById(R.id.rotation_overlay_portrait)
                    .setOnClickListener(view -> applyOrientation(Surface.ROTATION_0));
            overlayView.findViewById(R.id.rotation_overlay_exit)
                    .setOnClickListener(view -> restoreAndStop());

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            overlayParams.gravity = Gravity.TOP | Gravity.END;
            overlayParams.x = dp(14);
            overlayParams.y = dp(160);
            compactPanel.setOnTouchListener(new OverlayDragTouchListener(false));
            overlayView.findViewById(R.id.rotation_overlay_drag_header)
                    .setOnTouchListener(new OverlayDragTouchListener(true));
            windowManager.addView(overlayView, overlayParams);
            updateOverlaySelection(currentRotation);
            Log.i(TAG, "Rotation overlay attached");
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Failed to create rotation overlay", error);
            removeOverlay();
            return false;
        }
    }

    private void setOverlayExpanded(boolean expanded) {
        if (overlayView == null) return;
        compactPanel.setVisibility(expanded ? View.GONE : View.VISIBLE);
        expandedPanel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (RuntimeException ignored) {
            // The service may be shutting down while the view is being remeasured.
        }
    }

    private void updateOverlaySelection(int rotation) {
        if (overlayView == null || landscapeCard == null || portraitCard == null) return;
        boolean landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        styleOrientationAction(
                landscapeCard, landscapeLabel, landscapeIcon, landscape);
        styleOrientationAction(
                portraitCard, portraitLabel, portraitIcon, !landscape);
        if (overlayStatus != null) {
            overlayStatus.setText(landscape
                    ? R.string.overlay_status_landscape
                    : R.string.overlay_status_portrait);
        }
    }

    private void styleOrientationAction(
            MaterialCardView card, TextView label, ImageView icon, boolean selected) {
        int background = MaterialColors.getColor(
                card,
                selected
                        ? com.google.android.material.R.attr.colorPrimaryContainer
                        : com.google.android.material.R.attr.colorSurfaceContainer,
                0);
        int foreground = MaterialColors.getColor(
                card,
                selected
                        ? com.google.android.material.R.attr.colorOnPrimaryContainer
                        : com.google.android.material.R.attr.colorOnSurfaceVariant,
                0);
        card.setCardBackgroundColor(background);
        card.setStrokeWidth(selected ? dp(1) : 0);
        if (selected) {
            card.setStrokeColor(MaterialColors.getColor(
                    card, androidx.appcompat.R.attr.colorPrimary, foreground));
        }
        label.setTextColor(foreground);
        icon.setImageTintList(ColorStateList.valueOf(foreground));
    }

    private void applyOrientation(int rotation) {
        if (!privilegeReady || restoring.get()
                || !orientationCommandRunning.compareAndSet(false, true)) return;
        shellWorker.execute(() -> {
            try {
                repository.applySensorRotation(rotation);
                mainHandler.post(() -> {
                    orientationCommandRunning.set(false);
                    currentRotation = rotation;
                    updateOverlaySelection(rotation);
                    setOverlayExpanded(false);
                    updateNotification(getString(rotation == Surface.ROTATION_0
                            ? R.string.rotation_notification_portrait
                            : R.string.rotation_notification_landscape));
                    Toast.makeText(
                            this,
                            rotation == Surface.ROTATION_0
                                    ? R.string.rotation_toast_portrait
                                    : R.string.rotation_toast_landscape,
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    orientationCommandRunning.set(false);
                    Toast.makeText(this, R.string.rotation_toast_failed, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void launchGameThroughShell(GameServer server) {
        shellWorker.execute(() -> {
            if (restoring.get()) return;
            try {
                // Android 10+ can silently block startActivity() from a background service.
                // Root/Shizuku already supplies a privileged shell, so launch deterministically.
                repository.launchGame(server);
                // A few OEM builds re-apply the quick-settings auto-rotate value while the
                // launcher activity changes orientation. Reassert portrait only after the game
                // activity is actually visible; the original value remains in the recovery
                // snapshot and is restored when the controller exits.
                SystemClock.sleep(350L);
                repository.applySensorRotation(Surface.ROTATION_0);
                mainHandler.post(() -> {
                    currentRotation = Surface.ROTATION_0;
                    updateOverlaySelection(currentRotation);
                });
                Log.i(TAG, "Game launch completed for " + server.getPackageName());
                AppLogger.info(
                        "RotationService",
                        "游戏进入前台后已再次确认：自动旋转关闭、方向锁定为竖屏");
            } catch (Exception error) {
                Log.e(TAG, "Privileged game launch failed", error);
                mainHandler.post(() -> {
                    Toast.makeText(
                            this, R.string.rotation_toast_failed, Toast.LENGTH_LONG).show();
                    restoreAndStop();
                });
            }
        });
    }

    private final Runnable foregroundMonitor = new Runnable() {
        @Override
        public void run() {
            if (restoring.get()) return;
            if (!privilegeReady || monitorQueryRunning || gamePackage == null) {
                mainHandler.postDelayed(this, 1_500L);
                return;
            }
            monitorQueryRunning = true;
            shellWorker.execute(() -> {
                boolean foreground = false;
                try {
                    foreground = repository.isGameForeground(gamePackage);
                } catch (Exception ignored) {
                    // Transient Shizuku checks are retried while the service remains active.
                }
                boolean result = foreground;
                mainHandler.post(() -> handleForegroundResult(result));
            });
        }
    };

    private void handleForegroundResult(boolean foreground) {
        monitorQueryRunning = false;
        if (restoring.get()) return;
        long now = SystemClock.elapsedRealtime();
        if (foreground) {
            gameSeen = true;
            gameBackgroundSince = 0L;
        } else if (gameSeen) {
            if (gameBackgroundSince == 0L) gameBackgroundSince = now;
            if (now - gameBackgroundSince >= GAME_BACKGROUND_GRACE_MILLIS) {
                restoreAndStop();
                return;
            }
        } else if (now - serviceStartedAt >= GAME_START_TIMEOUT_MILLIS) {
            restoreAndStop();
            return;
        }
        mainHandler.postDelayed(foregroundMonitor, 1_500L);
    }

    private void restoreAndStop() {
        if (!restoring.compareAndSet(false, true)) return;
        AppLogger.info("RotationService", "开始恢复方向控制前的系统状态");
        restoreAttempts = 0;
        mainHandler.removeCallbacks(foregroundMonitor);
        mainHandler.removeCallbacks(restoreRetry);
        updateNotification(getString(R.string.rotation_notification_restoring));
        attemptRestore();
    }

    private final Runnable restoreRetry = new Runnable() {
        @Override
        public void run() {
            if (!restoring.get()) return;
            if (!privilegeReady && privilegeManager != null) {
                privilegeManager.requestAuthorization();
            }
            attemptRestore();
        }
    };

    private void attemptRestore() {
        if (!restoring.get() || !restoreCommandRunning.compareAndSet(false, true)) return;
        if (!privilegeReady) {
            restoreCommandRunning.set(false);
            if (privilegeManager != null) privilegeManager.requestAuthorization();
            scheduleRestoreRetry();
            return;
        }

        shellWorker.execute(() -> {
            boolean restoreSucceeded = false;
            boolean restoredPendingSession = false;
            Exception lastError = null;
            try {
                restoredPendingSession = repository.recoverInterruptedRotationSessionIfNeeded();
                restoreSucceeded = true;
                AppLogger.event("RotationService", "精确恢复完成；读取到会话=" +
                        restoredPendingSession);
            } catch (Exception exactRestoreError) {
                lastError = exactRestoreError;
                restoreAttempts++;
                Log.e(TAG, "Exact rotation restore attempt " + restoreAttempts + " failed",
                        exactRestoreError);
                AppLogger.error("RotationService", "精确恢复第 " + restoreAttempts +
                        " 次失败", exactRestoreError);
                if (restoreAttempts >= EXACT_RESTORE_RETRIES) {
                    try {
                        // Safety fallback: a usable auto-rotation state is preferable to leaving
                        // the whole device globally locked when the exact marker is damaged.
                        repository.restoreAutomaticRotation();
                        restoreSucceeded = true;
                        AppLogger.warn("RotationService", "已执行自动旋转安全兜底");
                    } catch (Exception fallbackError) {
                        lastError = fallbackError;
                        Log.e(TAG, "Automatic rotation safety fallback failed", fallbackError);
                        AppLogger.error("RotationService", "自动旋转安全兜底失败", fallbackError);
                    }
                }
            }

            boolean success = restoreSucceeded;
            boolean showRestoredToast = restoredPendingSession || restoreAttempts > 0;
            Exception error = lastError;
            mainHandler.post(() -> {
                restoreCommandRunning.set(false);
                if (success) {
                    completeRestore(showRestoredToast);
                    return;
                }
                Log.w(TAG, "Rotation recovery remains pending", error);
                updateNotification(getString(R.string.rotation_notification_restore_failed));
                if (overlayStatus != null) {
                    overlayStatus.setText(R.string.overlay_status_restore_pending);
                }
                privilegeReady = false;
                if (privilegeManager != null) privilegeManager.requestAuthorization();
                scheduleRestoreRetry();
            });
        });
    }

    private void scheduleRestoreRetry() {
        if (!restoring.get()) return;
        mainHandler.removeCallbacks(restoreRetry);
        mainHandler.postDelayed(restoreRetry, RESTORE_RETRY_MILLIS);
    }

    private void completeRestore(boolean showToast) {
        AppLogger.info("RotationService", "方向状态恢复事务结束");
        mainHandler.removeCallbacks(restoreRetry);
        removeOverlay();
        if (showToast) {
            Toast.makeText(this, R.string.rotation_toast_restored, Toast.LENGTH_SHORT).show();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void removeOverlay() {
        if (overlayView == null || windowManager == null) return;
        try {
            windowManager.removeView(overlayView);
        } catch (RuntimeException ignored) {
            // The system may already have removed an overlay during process teardown.
        }
        overlayView = null;
        compactPanel = null;
        expandedPanel = null;
        landscapeCard = null;
        portraitCard = null;
        landscapeLabel = null;
        portraitLabel = null;
        landscapeIcon = null;
        portraitIcon = null;
        overlayStatus = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.rotation_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.rotation_notification_channel_description));
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startAsForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Intent activityIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openApp = PendingIntent.getActivity(
                this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stopIntent = new Intent(this, RotationControllerService.class).setAction(ACTION_STOP);
        PendingIntent stopAction = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tune)
                .setContentTitle(getString(R.string.rotation_notification_title))
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(0, getString(R.string.rotation_notification_stop), stopAction)
                .build();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class OverlayDragTouchListener implements View.OnTouchListener {
        private final boolean collapseOnTap;
        private float downRawX;
        private float downRawY;
        private int downWindowX;
        private int downWindowY;
        private boolean dragging;

        private OverlayDragTouchListener(boolean collapseOnTap) {
            this.collapseOnTap = collapseOnTap;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (overlayParams == null || overlayView == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downWindowX = overlayParams.x;
                    downWindowY = overlayParams.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > dp(6)) dragging = true;
                    if (dragging) {
                        overlayParams.x = Math.max(0, downWindowX - Math.round(dx));
                        overlayParams.y = Math.max(0, downWindowY + Math.round(dy));
                        try {
                            windowManager.updateViewLayout(overlayView, overlayParams);
                        } catch (RuntimeException ignored) {}
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    if (!dragging) setOverlayExpanded(!collapseOnTap);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        AppLogger.warn("RotationService", "任务被移除，立即触发方向恢复");
        // Swiping the task away is an explicit exit path on many OEM systems. Restore before
        // allowing the sticky service to disappear, while keeping the rescue marker if the
        // current privilege connection is temporarily unavailable.
        restoreAndStop();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        AppLogger.info("RotationService", "方向控制服务销毁；恢复中=" + restoring.get());
        running = false;
        boolean recoveryStillPending = repository != null
                && repository.hasPrivateRotationRecoveryState();
        mainHandler.removeCallbacks(foregroundMonitor);
        mainHandler.removeCallbacks(restoreRetry);
        removeOverlay();
        if (privilegeManager != null) privilegeManager.closePreservingUserService();
        shellWorker.shutdownNow();
        if (recoveryStillPending) {
            // onDestroy is only a best-effort callback. Re-queue the persistent recovery command;
            // if the whole process is killed, START_STICKY and the external marker provide the
            // remaining two layers of recovery.
            try {
                requestRecovery(getApplicationContext());
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to re-queue pending rotation recovery", error);
            }
        }
        super.onDestroy();
    }
}
