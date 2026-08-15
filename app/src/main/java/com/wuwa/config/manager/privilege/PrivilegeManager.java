package com.wuwa.config.manager.privilege;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.wuwa.config.manager.BuildConfig;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import rikka.shizuku.Shizuku;

public final class PrivilegeManager implements AutoCloseable {
    public interface Listener {
        void onPrivilegeStateChanged(PrivilegeState state);
    }

    private static final int SHIZUKU_PERMISSION_REQUEST = 6102;
    private static final int ROOT_CHECK_TIMEOUT_SECONDS = 12;
    private static final long SHIZUKU_BIND_TIMEOUT_MILLIS = 10_000L;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService rootChecker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "wuwa-root-check");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger refreshGeneration = new AtomicInteger();

    private volatile boolean checkingRoot = true;
    private volatile boolean rootAvailable;
    private volatile PrivilegeState.ShizukuStatus shizukuStatus =
            PrivilegeState.ShizukuStatus.NOT_RUNNING;
    private volatile String detail;
    private volatile IShellService shizukuService;
    private volatile boolean permissionRequested;
    private volatile boolean closed;
    private volatile CountDownLatch serviceConnectionLatch;
    private volatile boolean rebuildingShizuku;
    private final AtomicInteger bindGeneration = new AtomicInteger();
    private Listener listener;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            this::onShizukuBinderReceived;
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        bindGeneration.incrementAndGet();
        shizukuService = null;
        shizukuStatus = PrivilegeState.ShizukuStatus.NOT_RUNNING;
        permissionRequested = false;
        detail = "Shizuku 服务已停止。请先在 Shizuku 中重新启动服务，再点击重新连接。";
        notifyState();
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST) return;
                permissionRequested = false;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindShizukuService();
                } else {
                    shizukuStatus = PrivilegeState.ShizukuStatus.DENIED;
                    detail = "Shizuku 授权被拒绝";
                    notifyState();
                }
            };
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            bindGeneration.incrementAndGet();
            IShellService service = IShellService.Stub.asInterface(binder);
            if (service == null || !binder.pingBinder()) {
                shizukuStatus = PrivilegeState.ShizukuStatus.ERROR;
                detail = "Shizuku Shell 服务连接无效";
            } else {
                shizukuService = service;
                shizukuStatus = PrivilegeState.ShizukuStatus.READY;
                detail = "Shizuku 已连接并授权";
            }
            rebuildingShizuku = false;
            CountDownLatch latch = serviceConnectionLatch;
            if (latch != null) latch.countDown();
            notifyState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bindGeneration.incrementAndGet();
            shizukuService = null;
            if (rebuildingShizuku) return;
            shizukuStatus = PrivilegeState.ShizukuStatus.ERROR;
            detail = "Shizuku Shell 服务已断开。请点击重新连接后再执行文件操作。";
            CountDownLatch latch = serviceConnectionLatch;
            if (latch != null) latch.countDown();
            notifyState();
        }
    };

    private final Shizuku.UserServiceArgs userServiceArgs;
    private final boolean removeUserServiceOnClose;

    public PrivilegeManager(Context context) {
        this(context, true);
    }

    public PrivilegeManager(Context context, boolean removeUserServiceOnClose) {
        appContext = context.getApplicationContext();
        this.removeUserServiceOnClose = removeUserServiceOnClose;
        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(appContext.getPackageName(), ShellUserService.class.getName()))
                .tag("wuwa_config_shell")
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionResultListener);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        notifyState();
    }

    public PrivilegeState getState() {
        return new PrivilegeState(checkingRoot, rootAvailable, shizukuStatus, detail);
    }

    /** Probes the current state without opening a system authorization prompt. */
    public void refresh() {
        refresh(false);
    }

    /**
     * Probes Root first and, when Root is unavailable, requests Shizuku authorization.
     * Call this only after a user action that clearly needs privileged file access.
     */
    public void requestAuthorization() {
        refresh(true);
    }

    private void refresh(boolean requestShizukuIfNeeded) {
        if (closed) return;
        int generation = refreshGeneration.incrementAndGet();
        permissionRequested = false;
        checkingRoot = true;
        detail = "正在检测 Root 权限";
        notifyState();

        rootChecker.execute(() -> {
            boolean granted = probeRoot();
            mainHandler.post(() -> {
                if (closed || generation != refreshGeneration.get()) return;
                checkingRoot = false;
                rootAvailable = granted;
                if (granted) {
                    detail = "Root 已授权";
                    observeOrBindAuthorizedShizuku(false);
                } else {
                    detail = "Root 不可用，正在检测 Shizuku";
                    observeOrBindAuthorizedShizuku(requestShizukuIfNeeded);
                }
                notifyState();
            });
        });
    }

    /** Clears a stuck permission/binding attempt and establishes a fresh UserService session. */
    public void reconnectShizuku() {
        if (closed) return;
        permissionRequested = false;
        bindGeneration.incrementAndGet();
        shizukuService = null;
        try {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
        } catch (RuntimeException ignored) {
            // A missing/dead session is expected when recovering from a broken connection.
        }
        shizukuStatus = PrivilegeState.ShizukuStatus.CONNECTING;
        detail = "正在重新建立 Shizuku 连接";
        notifyState();
        mainHandler.postDelayed(() -> {
            if (!closed) observeOrBindAuthorizedShizuku(true);
        }, 250L);
    }

    public ShellResult execute(String command, byte[] stdin, int timeoutSeconds) throws Exception {
        if (rootAvailable) {
            try {
                Process process = new ProcessBuilder("su", "-c", command).start();
                ShellResult result = ShellProcessRunner.run(process, stdin, timeoutSeconds);
                if (!looksLikeRootLoss(result)) return result;
                rootAvailable = false;
                detail = "Root 权限已失效";
                notifyState();
                IShellService fallback = shizukuService;
                if (fallback != null) {
                    return executeWithShizuku(fallback, command, stdin, timeoutSeconds);
                }
                throw new SecurityException("Root 权限已失效，请检查授权状态");
            } catch (IOException error) {
                rootAvailable = false;
                notifyState();
                IShellService fallback = shizukuService;
                if (fallback != null) return executeWithShizuku(fallback, command, stdin, timeoutSeconds);
                throw new SecurityException("Root 权限已失效，请检查授权状态", error);
            }
        }

        IShellService service = shizukuService;
        if (service != null && shizukuStatus == PrivilegeState.ShizukuStatus.READY) {
            ShellResult result = executeWithShizuku(service, command, stdin, timeoutSeconds);
            if (hasDisconnectedStorage(result) && rebuildShizukuSessionBlocking()) {
                IShellService recovered = shizukuService;
                if (recovered != null) {
                    return executeWithShizuku(recovered, command, stdin, timeoutSeconds);
                }
            }
            return result;
        }
        throw new SecurityException("权限不足，请检查 Shizuku/Root 状态");
    }

    private static boolean looksLikeRootLoss(ShellResult result) {
        if (result.isSuccess()) return false;
        String detail = (result.getStderr() + "\n" + result.getStdout())
                .toLowerCase(Locale.ROOT);
        return detail.contains("permission denied")
                || detail.contains("not allowed")
                || detail.contains("su: inaccessible")
                || detail.contains("su: not found");
    }

    private static boolean hasDisconnectedStorage(ShellResult result) {
        String output = (result.getStderr() + "\n" + result.getStdout())
                .toLowerCase(Locale.ROOT);
        return output.contains("transport endpoint is not connected");
    }

    /** Recreates the Shizuku process so it receives a fresh shared-storage mount namespace. */
    private boolean rebuildShizukuSessionBlocking() {
        if (Looper.myLooper() == Looper.getMainLooper() || closed) return false;
        CountDownLatch latch = new CountDownLatch(1);
        serviceConnectionLatch = latch;
        mainHandler.post(() -> {
            if (closed) {
                latch.countDown();
                return;
            }
            bindGeneration.incrementAndGet();
            shizukuService = null;
            rebuildingShizuku = true;
            shizukuStatus = PrivilegeState.ShizukuStatus.CONNECTING;
            detail = "共享存储连接已中断，正在自动重建 Shizuku 会话";
            notifyState();
            try {
                Shizuku.unbindUserService(
                        userServiceArgs, serviceConnection, removeUserServiceOnClose);
            } catch (RuntimeException ignored) {}
            mainHandler.postDelayed(() -> {
                if (closed || serviceConnectionLatch != latch) {
                    latch.countDown();
                    return;
                }
                try {
                    Shizuku.bindUserService(userServiceArgs, serviceConnection);
                } catch (RuntimeException error) {
                    rebuildingShizuku = false;
                    shizukuStatus = PrivilegeState.ShizukuStatus.ERROR;
                    detail = "Shizuku 自动重连失败：" + safeMessage(error);
                    latch.countDown();
                    notifyState();
                }
            }, 300L);
        });
        try {
            return latch.await(SHIZUKU_BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    && shizukuService != null
                    && shizukuStatus == PrivilegeState.ShizukuStatus.READY;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (serviceConnectionLatch == latch) serviceConnectionLatch = null;
            if (shizukuService == null) rebuildingShizuku = false;
        }
    }

    public boolean isUsingRoot() {
        return rootAvailable;
    }

    /**
     * Identifies the active root implementation without depending on the manager application's
     * package name. Magisk can hide/repackage its manager, while KernelSU and APatch may be used
     * without keeping their manager UI installed, so the privileged runtime directories and
     * command line tools are more reliable signals.
     */
    public String detectRootProvider() {
        if (!rootAvailable) return "";
        String command =
                "if [ -d /data/adb/ksu ] || command -v ksud >/dev/null 2>&1; then\n" +
                "  echo KernelSU\n" +
                "elif [ -d /data/adb/ap ] || command -v apd >/dev/null 2>&1; then\n" +
                "  echo APatch\n" +
                "elif [ -d /data/adb/magisk ] || command -v magisk >/dev/null 2>&1; then\n" +
                "  echo Magisk\n" +
                "else\n" +
                "  echo Root\n" +
                "fi\n";
        try {
            ShellResult result = execute(command, null, 8);
            String provider = result.getStdout().trim();
            if (result.isSuccess() && !provider.isEmpty()) {
                int lineBreak = provider.indexOf('\n');
                return lineBreak >= 0 ? provider.substring(0, lineBreak).trim() : provider;
            }
        } catch (Exception ignored) {
            // Generic Root is still accurate when the implementation cannot be identified.
        }
        return "Root";
    }

    private ShellResult executeWithShizuku(
            IShellService service, String command, byte[] stdin, int timeoutSeconds) throws Exception {
        try {
            String[] raw = service.execute(command, stdin, timeoutSeconds);
            if (raw == null || raw.length < 3) {
                throw new IOException("Shizuku 返回了无效的 Shell 结果");
            }
            int exitCode;
            try {
                exitCode = Integer.parseInt(raw[0]);
            } catch (NumberFormatException error) {
                throw new IOException("Shizuku 返回了无效的退出码", error);
            }
            return new ShellResult(exitCode, raw[1], raw[2]);
        } catch (RemoteException error) {
            shizukuService = null;
            shizukuStatus = PrivilegeState.ShizukuStatus.ERROR;
            detail = "Shizuku 连接中断。请在设置页点击“重连 Shizuku”后重试。";
            notifyState();
            mainHandler.post(() -> {
                if (!closed) observeOrBindAuthorizedShizuku(false);
            });
            throw new IOException("Shizuku 连接中断，本次操作未完成。请重新连接后再试。", error);
        }
    }

    private boolean probeRoot() {
        try {
            Process process = new ProcessBuilder("su", "-c", "id -u").start();
            ShellResult result = ShellProcessRunner.run(
                    process, null, ROOT_CHECK_TIMEOUT_SECONDS);
            return result.isSuccess() && result.getStdout().trim().equals("0");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void onShizukuBinderReceived() {
        mainHandler.post(() -> {
            if (!closed) observeOrBindAuthorizedShizuku(!checkingRoot && !rootAvailable);
        });
    }

    private void observeOrBindAuthorizedShizuku(boolean requestIfNeeded) {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuService = null;
                shizukuStatus = PrivilegeState.ShizukuStatus.NOT_RUNNING;
                if (!rootAvailable) detail = "Shizuku 未启动";
                return;
            }
            if (Shizuku.isPreV11()) {
                shizukuStatus = PrivilegeState.ShizukuStatus.ERROR;
                if (!rootAvailable) detail = "Shizuku 版本过低，请升级至 v11 或更高版本";
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindShizukuService();
                return;
            }

            shizukuService = null;
            if (!requestIfNeeded) {
                shizukuStatus = PrivilegeState.ShizukuStatus.WAITING_PERMISSION;
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                shizukuStatus = PrivilegeState.ShizukuStatus.DENIED;
                detail = "Shizuku 授权被拒绝。请在 Shizuku 的“已授权应用”中允许本 App，" +
                        "然后返回并点击重新连接。";
            } else if (!permissionRequested) {
                permissionRequested = true;
                shizukuStatus = PrivilegeState.ShizukuStatus.WAITING_PERMISSION;
                detail = "等待 Shizuku 授权";
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
            }
        } catch (RuntimeException error) {
            shizukuService = null;
            shizukuStatus = PrivilegeState.ShizukuStatus.ERROR;
            if (!rootAvailable) {
                detail = "Shizuku 检测失败：" + safeMessage(error);
            }
        } finally {
            notifyState();
        }
    }

    private void bindShizukuService() {
        if (shizukuService != null) {
            shizukuStatus = PrivilegeState.ShizukuStatus.READY;
            notifyState();
            return;
        }
        if (shizukuStatus == PrivilegeState.ShizukuStatus.CONNECTING) return;
        shizukuStatus = PrivilegeState.ShizukuStatus.CONNECTING;
        detail = "正在连接 Shizuku Shell 服务";
        notifyState();
        int generation = bindGeneration.incrementAndGet();
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
            mainHandler.postDelayed(() -> {
                if (closed || generation != bindGeneration.get()
                        || shizukuService != null) return;
                shizukuStatus = PrivilegeState.ShizukuStatus.ERROR;
                detail = "Shizuku Shell 服务连接超时。请确认 Shizuku 正在运行，" +
                        "然后点击“重连 Shizuku”。";
                notifyState();
            }, SHIZUKU_BIND_TIMEOUT_MILLIS);
        } catch (RuntimeException error) {
            bindGeneration.incrementAndGet();
            shizukuStatus = PrivilegeState.ShizukuStatus.ERROR;
            detail = "Shizuku Shell 服务连接失败：" + safeMessage(error);
            notifyState();
        }
    }

    private void notifyState() {
        Listener current = listener;
        if (current == null || closed) return;
        PrivilegeState state = getState();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            current.onPrivilegeStateChanged(state);
        } else {
            mainHandler.post(() -> {
                Listener latest = listener;
                if (latest != null && !closed) latest.onPrivilegeStateChanged(getState());
            });
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        closeInternal(removeUserServiceOnClose);
    }

    public void closePreservingUserService() {
        closeInternal(false);
    }

    private void closeInternal(boolean removeUserService) {
        if (closed) return;
        closed = true;
        refreshGeneration.incrementAndGet();
        bindGeneration.incrementAndGet();
        listener = null;
        rootChecker.shutdownNow();
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
        } catch (RuntimeException ignored) {}
        try {
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (RuntimeException ignored) {}
        try {
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (RuntimeException ignored) {}
        try {
            if (shizukuService != null) {
                Shizuku.unbindUserService(
                        userServiceArgs, serviceConnection, removeUserService);
            }
        } catch (RuntimeException ignored) {}
        shizukuService = null;
    }
}
