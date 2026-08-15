package com.wuwa.config.manager.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.wuwa.config.manager.model.GameServer;
import com.wuwa.config.manager.model.QualityPreset;
import com.wuwa.config.manager.diagnostics.AppLogger;
import com.wuwa.config.manager.privilege.PrivilegeManager;
import com.wuwa.config.manager.privilege.ShellEscaper;
import com.wuwa.config.manager.privilege.ShellResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;

public final class ConfigRepository {
    public static final String ENGINE_FILE = "Engine.ini";
    public static final String DEVICE_PROFILE_FILE = "DeviceProfiles.ini";
    private static final String LEGACY_DEVICE_PROFILE_FILE = "DeviceProfile.ini";
    public static final String BACKUP_MARKER = ".backup_success";
    private static final String AUTOMATIC_BACKUP_MARKER = ".automatic_snapshot";
    public static final String DEFAULT_STORAGE_ROOT =
            "/storage/emulated/0/Download/Wuwa CFBP";
    private static final String DISPLAY_NAME_FILE = ".display_name";
    private static final String STORAGE_PREFS = "wuwa_config_preferences";
    private static final String KEY_STORAGE_ROOT = "backup_storage_root";
    private static final String KEY_AUTOMATIC_BACKUP_RETENTION =
            "automatic_backup_retention";
    public static final int DEFAULT_AUTOMATIC_BACKUP_RETENTION = 10;
    private static final String KEY_ROTATION_SESSION_ACTIVE = "rotation_session_active";
    private static final String KEY_ROTATION_SESSION_PACKAGE = "rotation_session_package";
    private static final String KEY_ROTATION_ORIGINAL_ACCELEROMETER =
            "rotation_original_accelerometer";
    private static final String KEY_ROTATION_ORIGINAL_USER = "rotation_original_user";
    private static final String KEY_ROTATION_ORIGINAL_CURRENT =
            "rotation_original_current";
    private static final String KEY_ROTATION_ORIGINAL_IGNORE_REQUEST =
            "rotation_original_ignore_request";
    private static final String KEY_ROTATION_ORIGINAL_FIXED_MODE =
            "rotation_original_fixed_mode";
    private static final String KEY_ROTATION_SESSION_PHASE = "rotation_session_phase";
    private static final String KEY_ROTATION_CAPTURED_AT = "rotation_captured_at";
    private static final String ROTATION_RECOVERY_MARKER =
            DEFAULT_STORAGE_ROOT + "/.rotation_recovery_state";
    private static final String ROTATION_RECOVERY_MAGIC = "WUWA_ROTATION_V3";
    private static final String V2_ROTATION_RECOVERY_MAGIC = "WUWA_ROTATION_V2";
    private static final String LEGACY_ROTATION_RECOVERY_MAGIC = "WUWA_ROTATION_V1";
    public static final String ROTATION_PHASE_PREPARING = "preparing";
    public static final String ROTATION_PHASE_ACTIVE = "active";
    public static final String ROTATION_PHASE_RESTORING = "restoring";
    private static final String PRESET_PLACEHOLDER_MARKER =
            "WUWA_CONFIG_PRESET_PLACEHOLDER";

    private static final int NORMAL_TIMEOUT_SECONDS = 30;
    private static final int COPY_TIMEOUT_SECONDS = 300;
    private static final int BINDER_CHUNK_BYTES = 192 * 1024;
    private static final long MAX_PRESET_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_CONFIG_TREE_KB = 256L * 1024L;

    private final Context appContext;
    private final PrivilegeManager privilegeManager;
    private final File presetDirectory;
    private final OfficialConfigBundle officialConfigBundle;
    private final SharedPreferences preferences;

    public ConfigRepository(Context context, PrivilegeManager privilegeManager) {
        this.appContext = context.getApplicationContext();
        this.privilegeManager = privilegeManager;
        this.presetDirectory = new File(appContext.getFilesDir(), "preset");
        this.officialConfigBundle = new OfficialConfigBundle(appContext);
        this.preferences = appContext.getSharedPreferences(STORAGE_PREFS, Context.MODE_PRIVATE);
    }

    public String getStorageRoot() {
        String root = normalizeStorageRoot(preferences.getString(
                KEY_STORAGE_ROOT, DEFAULT_STORAGE_ROOT));
        // Never trust a path persisted by an older version. A malformed or now-disallowed
        // location must not become the target of a recursive shell operation.
        return isSafeStorageRoot(root) ? root : DEFAULT_STORAGE_ROOT;
    }

    public void setStorageRoot(String requestedRoot) throws OperationException {
        String root = normalizeStorageRoot(requestedRoot);
        if (!isSafeStorageRoot(root)) {
            throw new OperationException(
                    "存储位置必须是 /storage/emulated/0/ 下的具体文件夹，且不能包含 ..");
        }
        execute(
                "ROOT=" + q(root) + "\n" +
                "mkdir -p \"$ROOT\" || { echo WUWA_STORAGE_CREATE_FAILED >&2; exit 61; }\n" +
                "[ -d \"$ROOT\" ] || { echo WUWA_STORAGE_CREATE_FAILED >&2; exit 61; }\n",
                null,
                NORMAL_TIMEOUT_SECONDS,
                "创建备份存储目录");
        preferences.edit().putString(KEY_STORAGE_ROOT, root).apply();
    }

    /** Copies all bundled quality tiers into the app-private working directory. */
    public boolean preparePresets() {
        if (!presetDirectory.isDirectory() && !presetDirectory.mkdirs()) {
            invalidateWorkingPresets();
            return false;
        }

        try {
            for (QualityPreset preset : QualityPreset.values()) {
                String assetDirectory = preset.getAssetDirectory();
                List<String> bundled = Arrays.asList(nonNullAssets(assetDirectory));
                if (!bundled.contains(ENGINE_FILE) || !bundled.contains(DEVICE_PROFILE_FILE)) {
                    throw new IOException("预设文件缺失：" + preset.getDisplayName());
                }

                File workingDirectory = getPresetDirectory(preset);
                if (!workingDirectory.isDirectory() && !workingDirectory.mkdirs()) {
                    throw new IOException("无法创建工作预设目录：" + preset.getDisplayName());
                }
                copyAssetAtomically(
                        assetDirectory + "/" + ENGINE_FILE,
                        new File(workingDirectory, ENGINE_FILE));
                copyAssetAtomically(
                        assetDirectory + "/" + DEVICE_PROFILE_FILE,
                        new File(workingDirectory, DEVICE_PROFILE_FILE));
                new File(workingDirectory, LEGACY_DEVICE_PROFILE_FILE).delete();
            }
            return allPresetsReady() && officialConfigBundle.prepare();
        } catch (IOException error) {
            invalidateWorkingPresets();
            return false;
        }
    }

    public boolean presetsReady(QualityPreset preset) {
        File directory = getPresetDirectory(preset);
        return usablePreset(new File(directory, ENGINE_FILE))
                && usablePreset(new File(directory, DEVICE_PROFILE_FILE));
    }

    public boolean hasEmptyPresetFile(QualityPreset preset) {
        File directory = getPresetDirectory(preset);
        File engine = new File(directory, ENGINE_FILE);
        File device = new File(directory, DEVICE_PROFILE_FILE);
        return presetsReady(preset) && (engine.length() == 0L || device.length() == 0L);
    }

    public boolean hasValidBackup(GameServer server) throws OperationException {
        return officialConfigBundle.isReady();
    }

    private boolean hasLegacyValidBackup(GameServer server) throws OperationException {
        String backup = getBackupDirectory(server);
        String legacy = server.getLegacyBackupDirectory();
        ShellResult result = execute(
                "BACKUP=" + q(backup) + "\n" +
                "LEGACY=" + q(legacy) + "\n" +
                legacyBackupMigrationScript() +
                "if [ -f \"$BACKUP/" + BACKUP_MARKER + "\" ]; then " +
                "printf '1'; else printf '0'; fi",
                null,
                NORMAL_TIMEOUT_SECONDS,
                "检查备份状态");
        return "1".equals(result.getStdout().trim());
    }

    public void replace(GameServer server, QualityPreset preset) throws OperationException {
        requirePresets(preset);
        String automaticSnapshot = createBackupSnapshot(server, true);
        writeBackupDisplayName(
                server,
                automaticSnapshot,
                "替换为" + preset.getDisplayName() + "档前自动备份",
                true);

        if (privilegeManager.isUsingRoot()) {
            replaceFromPrivateDirectoryWithRoot(server, preset);
        } else {
            replaceThroughShizukuStaging(server, preset);
        }

        // Retention is deliberately best-effort and runs only after a successful replacement.
        // A cleanup problem must never turn a completed config replacement into a false failure.
        try {
            pruneAutomaticBackups(server);
        } catch (OperationException ignored) {
            // The next successful replacement will retry cleanup.
        }
    }

    public void restore(GameServer server) throws OperationException {
        requireOfficialConfig();
        if (privilegeManager.isUsingRoot()) {
            restoreOfficialFromPrivateDirectoryWithRoot(server);
        } else {
            restoreOfficialThroughShizukuStaging(server);
        }
    }

    private void restoreLegacyProtectedBackup(GameServer server) throws OperationException {
        String target = server.getConfigDirectory();
        String backup = getBackupDirectory(server);
        String legacy = server.getLegacyBackupDirectory();
        String script =
                "TARGET=" + q(target) + "\n" +
                "BACKUP=" + q(backup) + "\n" +
                "LEGACY=" + q(legacy) + "\n" +
                legacyBackupMigrationScript() +
                "if [ ! -d \"$BACKUP\" ] || [ ! -f \"$BACKUP/" + BACKUP_MARKER + "\" ]; then\n" +
                "  echo WUWA_NO_BACKUP >&2; exit 50\n" +
                "fi\n" +
                "if [ ! -d \"$TARGET\" ]; then\n" +
                "  echo WUWA_TARGET_MISSING >&2; exit 40\n" +
                "fi\n" +
                safeCopyPreflight("$BACKUP", "$TARGET") +
                contentOnlyTreeCopy("$BACKUP", "$TARGET", true,
                        "WUWA_RESTORE_COPY_FAILED") +
                "if [ -z \"$(find \"$BACKUP\" -mindepth 1 -type f " +
                "! -name '" + BACKUP_MARKER + "' -print 2>/dev/null | head -n 1)\" ]; then " +
                "echo WUWA_NO_BACKUP >&2; exit 50; fi\n";
        execute(script, null, COPY_TIMEOUT_SECONDS, "恢复官方默认");
    }

    public String createManualBackup(GameServer server) throws OperationException {
        return createBackupSnapshot(server, false);
    }

    private String createBackupSnapshot(GameServer server, boolean automatic)
            throws OperationException {
        String timestamp = new SimpleDateFormat(
                "yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        String target = server.getConfigDirectory();
        String parent = getManualBackupDirectory(server);
        String snapshot = parent + "/" + timestamp;
        String script =
                "TARGET=" + q(target) + "\n" +
                "PARENT=" + q(parent) + "\n" +
                "SNAPSHOT=" + q(snapshot) + "\n" +
                "if [ ! -d \"$TARGET\" ]; then echo WUWA_TARGET_MISSING >&2; exit 40; fi\n" +
                "if [ -z \"$(ls -A \"$TARGET\" 2>/dev/null)\" ]; then " +
                "echo WUWA_TARGET_EMPTY >&2; exit 41; fi\n" +
                safeCopyPreflight("$TARGET", "$SNAPSHOT") +
                "mkdir -p \"$PARENT\" || { echo WUWA_MANUAL_BACKUP_PREPARE_FAILED >&2; exit 46; }\n" +
                "mkdir \"$SNAPSHOT\" || { echo WUWA_MANUAL_BACKUP_PREPARE_FAILED >&2; exit 46; }\n" +
                contentOnlyTreeCopy("$TARGET", "$SNAPSHOT", false,
                        "WUWA_MANUAL_BACKUP_COPY_FAILED") +
                ": > \"$SNAPSHOT/" + BACKUP_MARKER + "\" || {\n" +
                "  rm -rf -- \"$SNAPSHOT\"\n" +
                "  echo WUWA_MANUAL_BACKUP_MARK_FAILED >&2; exit 48\n" +
                "}\n" +
                (automatic
                        ? ": > \"$SNAPSHOT/" + AUTOMATIC_BACKUP_MARKER + "\" || {\n" +
                        "  rm -rf -- \"$SNAPSHOT\"\n" +
                        "  echo WUWA_MANUAL_BACKUP_MARK_FAILED >&2; exit 48\n" +
                        "}\n"
                        : "");
        execute(script, null, COPY_TIMEOUT_SECONDS, "创建手动备份");
        return timestamp;
    }

    public List<ManualBackupInfo> listManualBackups(GameServer server)
            throws OperationException {
        String directory = getManualBackupDirectory(server);
        String script =
                "DIR=" + q(directory) + "\n" +
                "[ -d \"$DIR\" ] || exit 0\n" +
                "for item in \"$DIR\"/*; do\n" +
                "  [ -d \"$item\" ] || continue\n" +
                "  [ -f \"$item/" + BACKUP_MARKER + "\" ] || continue\n" +
                "  name=${item##*/}\n" +
                "  kb=$(du -sk \"$item\" 2>/dev/null | awk '{print $1}')\n" +
                "  [ -n \"$kb\" ] || kb=0\n" +
                "  automatic=0\n" +
                "  [ -f \"$item/" + AUTOMATIC_BACKUP_MARKER + "\" ] && automatic=1\n" +
                "  printf '%s|%s|%s\\n' \"$name\" \"$kb\" \"$automatic\"\n" +
                "done\n";
        ShellResult result = execute(
                script, null, NORMAL_TIMEOUT_SECONDS, "读取手动备份列表");
        List<ManualBackupInfo> snapshots = new ArrayList<>();
        String output = result.getStdout().trim();
        if (output.isEmpty()) return snapshots;
        for (String line : output.split("\\r?\\n")) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 3 || !isSafeSnapshotName(fields[0])) continue;
            long kiloBytes;
            try {
                kiloBytes = Long.parseLong(fields[1]);
            } catch (NumberFormatException ignored) {
                kiloBytes = 0L;
            }
            String displayName = readManualBackupDisplayName(server, fields[0]);
            boolean automatic = "1".equals(fields[2])
                    || isLegacyAutomaticBackupName(displayName);
            snapshots.add(0, new ManualBackupInfo(
                    fields[0], displayName, kiloBytes * 1024L, automatic));
        }
        return snapshots;
    }

    public void restoreManualBackup(GameServer server, String snapshotName)
            throws OperationException {
        if (!isSafeSnapshotName(snapshotName)) {
            throw new OperationException("手动备份名称无效，已拒绝恢复");
        }
        String target = server.getConfigDirectory();
        String snapshot = getManualBackupDirectory(server) + "/" + snapshotName;
        String script =
                "TARGET=" + q(target) + "\n" +
                "SNAPSHOT=" + q(snapshot) + "\n" +
                "if [ ! -d \"$SNAPSHOT\" ] || " +
                "[ ! -f \"$SNAPSHOT/" + BACKUP_MARKER + "\" ]; then\n" +
                "  echo WUWA_NO_MANUAL_BACKUP >&2; exit 52\n" +
                "fi\n" +
                "if [ ! -d \"$TARGET\" ]; then echo WUWA_TARGET_MISSING >&2; exit 40; fi\n" +
                safeCopyPreflight("$SNAPSHOT", "$TARGET") +
                contentOnlyTreeCopy("$SNAPSHOT", "$TARGET", true,
                        "WUWA_RESTORE_COPY_FAILED") +
                "if [ -z \"$(find \"$SNAPSHOT\" -mindepth 1 -type f " +
                "! -name '" + BACKUP_MARKER + "' ! -name '" + DISPLAY_NAME_FILE +
                "' ! -name '" + AUTOMATIC_BACKUP_MARKER +
                "' -print 2>/dev/null | head -n 1)\" ]; then " +
                "echo WUWA_NO_MANUAL_BACKUP >&2; exit 52; fi\n";
        execute(script, null, COPY_TIMEOUT_SECONDS, "恢复手动备份");
    }

    public void renameManualBackup(
            GameServer server, String snapshotName, String displayName)
            throws OperationException {
        writeBackupDisplayName(server, snapshotName, displayName, false);
    }

    private void writeBackupDisplayName(
            GameServer server,
            String snapshotName,
            String displayName,
            boolean preserveAutomaticMarker)
            throws OperationException {
        if (!isSafeSnapshotName(snapshotName)) {
            throw new OperationException("手动备份标识无效，已拒绝重命名");
        }
        String cleaned = displayName == null ? "" : displayName.trim();
        byte[] bytes = cleaned.getBytes(StandardCharsets.UTF_8);
        if (cleaned.isEmpty() || cleaned.length() > 30 || bytes.length > 120
                || cleaned.matches(".*[\\r\\n\\u0000-\\u001F].*")) {
            throw new OperationException("备份名称需为 1–30 个字符，且不能包含换行或控制字符");
        }
        String snapshot = getManualBackupDirectory(server) + "/" + snapshotName;
        String nameFile = snapshot + "/" + DISPLAY_NAME_FILE;
        execute(
                "SNAPSHOT=" + q(snapshot) + "\n" +
                "[ -f \"$SNAPSHOT/" + BACKUP_MARKER + "\" ] || " +
                "{ echo WUWA_NO_MANUAL_BACKUP >&2; exit 52; }\n" +
                (preserveAutomaticMarker
                        ? ""
                        : "rm -f -- \"$SNAPSHOT/" + AUTOMATIC_BACKUP_MARKER + "\" || " +
                        "{ echo WUWA_MANUAL_BACKUP_MARK_FAILED >&2; exit 48; }\n") +
                "umask 077; cat > " + q(nameFile),
                bytes,
                NORMAL_TIMEOUT_SECONDS,
                "重命名手动备份");
    }

    public int getAutomaticBackupRetentionLimit() {
        int value = preferences.getInt(
                KEY_AUTOMATIC_BACKUP_RETENTION, DEFAULT_AUTOMATIC_BACKUP_RETENTION);
        return isAllowedAutomaticBackupRetention(value)
                ? value : DEFAULT_AUTOMATIC_BACKUP_RETENTION;
    }

    public void setAutomaticBackupRetentionLimit(int limit) {
        if (!isAllowedAutomaticBackupRetention(limit)) {
            throw new IllegalArgumentException("Unsupported automatic backup retention: " + limit);
        }
        preferences.edit().putInt(KEY_AUTOMATIC_BACKUP_RETENTION, limit).apply();
    }

    public int pruneAutomaticBackups(GameServer server) throws OperationException {
        int limit = getAutomaticBackupRetentionLimit();
        if (limit == 0) return 0;

        List<ManualBackupInfo> snapshots = listManualBackups(server);
        List<String> excess = new ArrayList<>();
        int automaticCount = 0;
        for (ManualBackupInfo snapshot : snapshots) {
            if (!snapshot.isAutomatic()) continue;
            automaticCount++;
            if (automaticCount > limit) excess.add(snapshot.getSnapshotName());
        }
        if (!excess.isEmpty()) deleteManualBackups(server, excess);
        return excess.size();
    }

    private static boolean isAllowedAutomaticBackupRetention(int limit) {
        return limit == 0 || limit == 5 || limit == 10 || limit == 20;
    }

    private static boolean isLegacyAutomaticBackupName(String displayName) {
        return displayName != null
                && displayName.matches("^替换为(低|中|高|极致)档前自动备份$");
    }

    public void deleteManualBackups(GameServer server, List<String> snapshotNames)
            throws OperationException {
        if (snapshotNames == null || snapshotNames.isEmpty()) return;
        String root = getManualBackupDirectory(server);
        StringBuilder targets = new StringBuilder();
        for (String name : snapshotNames) {
            if (!isSafeSnapshotName(name)) {
                throw new OperationException("手动备份标识无效，已拒绝删除");
            }
            targets.append(' ').append(q(root + "/" + name));
        }
        execute(
                "ROOT=" + q(root) + "\n" +
                "case \"$ROOT\" in " + q(getStorageRoot() + "/ManualBackup/") +
                "*) ;; *) echo WUWA_UNSAFE_DELETE >&2; exit 60 ;; esac\n" +
                "rm -rf --" + targets +
                " || { echo WUWA_DELETE_FAILED >&2; exit 60; }\n",
                null,
                COPY_TIMEOUT_SECONDS,
                "删除手动备份");
    }

    public List<BackupInfo> listBackups() throws OperationException {
        List<BackupInfo> backups = new ArrayList<>();
        for (GameServer server : GameServer.values()) {
            String directory = getBackupDirectory(server);
            String manualDirectory = getManualBackupDirectory(server);
            String legacyDirectory = server.getLegacyBackupDirectory();
            String script =
                    "DIR=" + q(directory) + "\n" +
                    "BACKUP=\"$DIR\"\n" +
                    "MANUAL=" + q(manualDirectory) + "\n" +
                    "LEGACY=" + q(legacyDirectory) + "\n" +
                    legacyBackupMigrationScript() +
                    "exists=0; [ -d \"$DIR\" ] && exists=1\n" +
                    "valid=0; [ -f \"$DIR/" + BACKUP_MARKER + "\" ] && valid=1\n" +
                    "kb=$(du -sk \"$DIR\" 2>/dev/null | awk '{print $1}')\n" +
                    "[ -n \"$kb\" ] || kb=0\n" +
                    "manual_count=0\n" +
                    "if [ -d \"$MANUAL\" ]; then\n" +
                    "  for item in \"$MANUAL\"/*; do\n" +
                    "    [ -d \"$item\" ] || continue\n" +
                    "    [ -f \"$item/" + BACKUP_MARKER + "\" ] || continue\n" +
                    "    manual_count=$((manual_count + 1))\n" +
                    "  done\n" +
                    "fi\n" +
                    "manual_kb=$(du -sk \"$MANUAL\" 2>/dev/null | awk '{print $1}')\n" +
                    "[ -n \"$manual_kb\" ] || manual_kb=0\n" +
                    "printf '%s|%s|%s|%s|%s' \"$exists\" \"$valid\" \"$kb\" " +
                    "\"$manual_count\" \"$manual_kb\"\n";
            ShellResult result = execute(
                    script, null, NORMAL_TIMEOUT_SECONDS, "读取备份大小");
            String[] fields = result.getStdout().trim().split("\\|", -1);
            if (fields.length != 5) {
                throw new OperationException("读取备份信息失败：Shell 返回格式无效");
            }
            boolean exists = "1".equals(fields[0]);
            boolean valid = "1".equals(fields[1]);
            long kiloBytes;
            try {
                kiloBytes = Long.parseLong(fields[2]);
            } catch (NumberFormatException error) {
                kiloBytes = 0L;
            }
            int manualCount;
            long manualKiloBytes;
            try {
                manualCount = Integer.parseInt(fields[3]);
                manualKiloBytes = Long.parseLong(fields[4]);
            } catch (NumberFormatException error) {
                manualCount = 0;
                manualKiloBytes = 0L;
            }
            backups.add(new BackupInfo(
                    server,
                    exists,
                    valid,
                    kiloBytes * 1024L,
                    manualCount,
                    manualKiloBytes * 1024L));
        }
        return backups;
    }

    public void forceStop(GameServer server) throws OperationException {
        execute(
                "am force-stop " + q(server.getPackageName()),
                null,
                NORMAL_TIMEOUT_SECONDS,
                "强制关闭游戏");
    }

    /** Launches the installed launcher activity through the privileged shell identity. */
    public void launchGame(GameServer server) throws OperationException {
        String script =
                "PKG=" + q(server.getPackageName()) + "\n" +
                "COMPONENT=$(cmd package resolve-activity --brief " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "\"$PKG\" 2>/dev/null | tail -n 1)\n" +
                "case \"$COMPONENT\" in */*) ;; *) echo WUWA_GAME_ACTIVITY_MISSING >&2; exit 72 ;; esac\n" +
                "am start -W -n \"$COMPONENT\" >/dev/null 2>&1 || { " +
                "echo WUWA_GAME_LAUNCH_FAILED >&2; exit 73; }\n";
        execute(script, null, NORMAL_TIMEOUT_SECONDS, "启动游戏");
    }

    public int getRunningProcessCount(GameServer server) throws OperationException {
        String script =
                "PKG=" + q(server.getPackageName()) + "\n" +
                "ps -A 2>/dev/null | awk -v p=\"$PKG\" '" +
                "{n=$NF; if (n==p || index(n,p \":\")==1 || index(n,p \".\")==1) c++} " +
                "END {print c+0}'\n";
        ShellResult result = execute(
                script, null, NORMAL_TIMEOUT_SECONDS, "读取游戏进程状态");
        try {
            return Math.max(0, Integer.parseInt(result.getStdout().trim()));
        } catch (NumberFormatException error) {
            throw new OperationException("读取游戏进程状态失败：Shell 返回格式无效", error);
        }
    }

    public void beginSensorRotationSession(GameServer server) throws OperationException {
        // A previous process may have died after changing global rotation settings. Always
        // finish that transaction before starting a new one, including after reinstall.
        recoverInterruptedRotationSessionIfNeeded();

        ShellResult original = execute(
                rotationUserPrelude() +
                "ACCEL=$(settings --user \"$WUWA_ANDROID_USER\" get system " +
                "accelerometer_rotation 2>/dev/null)\n" +
                "USER=$(settings --user \"$WUWA_ANDROID_USER\" get system " +
                "user_rotation 2>/dev/null)\n" +
                "CURRENT=$(dumpsys input 2>/dev/null | sed -n " +
                "'s/.*SurfaceOrientation:[[:space:]]*//p' | head -n 1)\n" +
                "IGNORE_RAW=$(wm get-ignore-orientation-request 2>/dev/null || true)\n" +
                "FIXED_RAW=$(wm fixed-to-user-rotation 2>/dev/null | " +
                "tail -n 1 | tr -d '\\r' || true)\n" +
                "case \"$ACCEL\" in 0|1) ;; *) ACCEL=1 ;; esac\n" +
                "case \"$USER\" in 0|1|2|3) ;; *) USER=0 ;; esac\n" +
                "case \"$CURRENT\" in 0|1|2|3) ;; *) CURRENT=$USER ;; esac\n" +
                "case \"$IGNORE_RAW\" in *true*|*TRUE*) IGNORE=1 ;; *) IGNORE=0 ;; esac\n" +
                "case \"$FIXED_RAW\" in enabled|disabled|default) ;; " +
                "*) FIXED_RAW=default ;; esac\n" +
                "printf '%s\\n%s\\n%s\\n%s\\n%s' \"$ACCEL\" \"$USER\" " +
                "\"$CURRENT\" \"$IGNORE\" \"$FIXED_RAW\"",
                null, NORMAL_TIMEOUT_SECONDS, "读取系统旋转状态");
        String[] values = original.getStdout().trim().split("\\r?\\n");
        RotationState state = new RotationState(
                parseRotationValue(values, 0, 1, 0, 1),
                parseRotationValue(values, 1, 0, 0, 3),
                parseRotationValue(values, 2, 0, 0, 3),
                parseRotationValue(values, 3, 0, 0, 1) == 1,
                values.length > 4 ? normalizeFixedRotationMode(values[4]) : "default",
                server.getPackageName());
        AppLogger.info("Rotation", "已读取旋转前状态：auto=" +
                state.accelerometerRotation + "，userRotation=" + state.userRotation +
                "，currentRotation=" + state.currentRotation +
                "，ignoreRequest=" + state.ignoreOrientationRequest +
                "，fixedMode=" + state.fixedToUserRotationMode);

        if (!preferences.edit()
                .putBoolean(KEY_ROTATION_SESSION_ACTIVE, true)
                .putString(KEY_ROTATION_SESSION_PACKAGE, state.packageName)
                .putInt(KEY_ROTATION_ORIGINAL_ACCELEROMETER, state.accelerometerRotation)
                .putInt(KEY_ROTATION_ORIGINAL_USER, state.userRotation)
                .putInt(KEY_ROTATION_ORIGINAL_CURRENT, state.currentRotation)
                .putBoolean(KEY_ROTATION_ORIGINAL_IGNORE_REQUEST,
                        state.ignoreOrientationRequest)
                .putString(KEY_ROTATION_ORIGINAL_FIXED_MODE, state.fixedToUserRotationMode)
                .putString(KEY_ROTATION_SESSION_PHASE, ROTATION_PHASE_PREPARING)
                .putLong(KEY_ROTATION_CAPTURED_AT, state.capturedAtMillis)
                .commit()) {
            throw new OperationException("无法保存旋转恢复状态，已取消启动游戏");
        }

        try {
            persistRotationRecoveryMarker(state);
            execute(
                    resetAllOrientationOverridesScript() +
                    "PKG=" + q(state.packageName) + "\n" +
                    "if ! am compat enable OVERRIDE_ANY_ORIENTATION_TO_USER \"$PKG\" " +
                    ">/dev/null 2>&1; then\n" +
                    "  am compat enable OVERRIDE_ANY_ORIENTATION \"$PKG\" " +
                    ">/dev/null 2>&1 || true\n" +
                    "  am compat enable OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT " +
                    "\"$PKG\" >/dev/null 2>&1 || true\n" +
                    "fi\n" +
                    lockRotationScript(0) +
                    verifyLockedRotationScript(0),
                    null, NORMAL_TIMEOUT_SECONDS, "启动悬浮方向控制");
            preferences.edit()
                    .putString(KEY_ROTATION_SESSION_PHASE, ROTATION_PHASE_ACTIVE)
                    .commit();
        } catch (OperationException startError) {
            try {
                restoreRotationState(state);
                clearRotationRecoveryState();
            } catch (OperationException rollbackError) {
                throw new OperationException(
                        "方向控制启动失败，且系统旋转自动回滚失败。请保留应用并点击“恢复系统旋转”。\n\n" +
                        "启动错误：" + startError.getMessage() + "\n回滚错误：" +
                        rollbackError.getMessage(), rollbackError);
            }
            throw startError;
        }
    }

    public void applySensorRotation(int rotation) throws OperationException {
        if (rotation < 0 || rotation > 3) throw new OperationException("无效的屏幕方向值");
        if (!isRotationSessionActive()) {
            throw new OperationException("方向控制会话已经结束，请重新启动游戏");
        }
        execute(
                lockRotationScript(rotation) + verifyLockedRotationScript(rotation),
                null, NORMAL_TIMEOUT_SECONDS, "根据悬浮控制器旋转屏幕");
        preferences.edit().putString(KEY_ROTATION_SESSION_PHASE, ROTATION_PHASE_ACTIVE).apply();
    }

    public void finishSensorRotationSession() throws OperationException {
        recoverInterruptedRotationSessionIfNeeded();
    }

    /**
     * Restores the exact state captured before direction control was enabled. The external
     * marker deliberately lives outside the app sandbox so reinstalling the app does not
     * destroy the only recovery information while the system remains globally locked.
     *
     * @return true when a pending rotation transaction was found and restored.
     */
    public boolean recoverInterruptedRotationSessionIfNeeded() throws OperationException {
        RotationState state = readPrivateRotationState();
        if (state == null) state = readPersistentRotationState();
        if (state == null) return false;

        preferences.edit().putString(KEY_ROTATION_SESSION_PHASE, ROTATION_PHASE_RESTORING).commit();
        restoreRotationState(state);
        clearRotationRecoveryState();
        return true;
    }

    public void restoreAutomaticRotation() throws OperationException {
        execute(
                resetAllOrientationOverridesScript() +
                freeRotationScript() +
                rotationUserPrelude() +
                "settings --user \"$WUWA_ANDROID_USER\" put system " +
                "user_rotation 0 || exit 1\n" +
                "settings --user \"$WUWA_ANDROID_USER\" put system " +
                "accelerometer_rotation 1 || exit 1\n" +
                verifyRestoredRotationScript(1, 0, false, "default"),
                null, NORMAL_TIMEOUT_SECONDS, "恢复系统自动旋转");
        clearRotationRecoveryState();
    }

    public boolean isRotationSessionActive() {
        return preferences.getBoolean(KEY_ROTATION_SESSION_ACTIVE, false);
    }

    public String getRotationSessionPackage() {
        return preferences.getString(KEY_ROTATION_SESSION_PACKAGE, null);
    }

    public boolean hasPrivateRotationRecoveryState() {
        return preferences.getBoolean(KEY_ROTATION_SESSION_ACTIVE, false);
    }

    public RotationRecoveryInfo getPrivateRotationRecoveryInfo() {
        if (!hasPrivateRotationRecoveryState()) return null;
        return new RotationRecoveryInfo(
                preferences.getInt(KEY_ROTATION_ORIGINAL_ACCELEROMETER, 1) == 1,
                preferences.getInt(KEY_ROTATION_ORIGINAL_USER, 0),
                preferences.getInt(KEY_ROTATION_ORIGINAL_CURRENT,
                        preferences.getInt(KEY_ROTATION_ORIGINAL_USER, 0)),
                preferences.getString(KEY_ROTATION_SESSION_PHASE, ROTATION_PHASE_ACTIVE),
                preferences.getLong(KEY_ROTATION_CAPTURED_AT, 0L),
                preferences.getString(KEY_ROTATION_SESSION_PACKAGE, ""));
    }

    public boolean isGameForeground(String packageName) throws OperationException {
        ShellResult result = execute(
                "PKG=" + q(packageName) + "\n" +
                "ACTIVITY=$(dumpsys activity activities 2>/dev/null)\n" +
                "TOP=$(printf '%s\\n' \"$ACTIVITY\" | " +
                "sed -n 's/.*topResumedActivity=.* u[0-9][0-9]* \\([^/ ]*\\)\\/.*/\\1/p' | head -n 1)\n" +
                "[ -z \"$TOP\" ] && TOP=$(printf '%s\\n' \"$ACTIVITY\" | " +
                "sed -n 's/.*mResumedActivity:.* u[0-9][0-9]* \\([^/ ]*\\)\\/.*/\\1/p' | head -n 1)\n" +
                "if [ -z \"$TOP\" ]; then\n" +
                "  WINDOW=$(dumpsys window windows 2>/dev/null)\n" +
                "  TOP=$(printf '%s\\n' \"$WINDOW\" | sed -n " +
                "'s/.*mCurrentFocus=.* \\([^/ }]*\\)\\/.*/\\1/p' | head -n 1)\n" +
                "  [ -z \"$TOP\" ] && TOP=$(printf '%s\\n' \"$WINDOW\" | sed -n " +
                "'s/.*mFocusedApp=.* \\([^/ }]*\\)\\/.*/\\1/p' | head -n 1)\n" +
                "fi\n" +
                "[ \"$TOP\" = \"$PKG\" ] && printf 1 || printf 0",
                null, NORMAL_TIMEOUT_SECONDS, "检测游戏前台状态");
        return "1".equals(result.getStdout().trim());
    }

    public SystemLoadSample sampleSystemLoad() throws OperationException {
        String script =
                "set -- $(head -n 1 /proc/stat)\n" +
                "CPU_USER=${2:-0}; CPU_NICE=${3:-0}; CPU_SYSTEM=${4:-0}; CPU_IDLE=${5:-0}\n" +
                "CPU_IOWAIT=${6:-0}; CPU_IRQ=${7:-0}; CPU_SOFTIRQ=${8:-0}; CPU_STEAL=${9:-0}\n" +
                "CPU_IDLE_ALL=$((CPU_IDLE + CPU_IOWAIT))\n" +
                "CPU_TOTAL=$((CPU_USER + CPU_NICE + CPU_SYSTEM + CPU_IDLE + CPU_IOWAIT + " +
                "CPU_IRQ + CPU_SOFTIRQ + CPU_STEAL))\n" +
                "GPU_ACTIVE=-1; GPU_TOTAL=-1; GPU_DIRECT=-1\n" +
                "if [ -r /sys/class/kgsl/kgsl-3d0/gpubusy ]; then\n" +
                "  set -- $(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null)\n" +
                "  if [ \"${2:-0}\" -gt 0 ] 2>/dev/null; then " +
                "GPU_DIRECT=$((100 * ${1:-0} / ${2:-1})); fi\n" +
                "else\n" +
                "  set -- $(dumpsys gpu 2>/dev/null | sed -n '/^GPU work information/,$p' | " +
                "awk '$1 ~ /^[0-9]+$/ { a += $3; i += $4 } " +
                "END { printf \"%.0f %.0f\", a, a + i }')\n" +
                "  GPU_ACTIVE=${1:--1}; GPU_TOTAL=${2:--1}\n" +
                "fi\n" +
                "printf '%s %s %s %s %s\\n' \"$CPU_TOTAL\" \"$CPU_IDLE_ALL\" " +
                "\"$GPU_ACTIVE\" \"$GPU_TOTAL\" \"$GPU_DIRECT\"\n";
        ShellResult result = execute(
                script, null, NORMAL_TIMEOUT_SECONDS, "读取系统负载");
        String[] values = result.getStdout().trim().split("\\s+");
        if (values.length < 5) throw new OperationException("系统负载数据格式无效");
        try {
            return new SystemLoadSample(
                    Long.parseLong(values[0]),
                    Long.parseLong(values[1]),
                    Long.parseLong(values[2]),
                    Long.parseLong(values[3]),
                    Integer.parseInt(values[4]));
        } catch (NumberFormatException error) {
            throw new OperationException("系统负载数据无法解析", error);
        }
    }

    private static String resetOrientationOverrideScript(String packageName) {
        return "PKG=" + q(packageName) + "\n" +
                "am compat reset OVERRIDE_ANY_ORIENTATION_TO_USER \"$PKG\" " +
                ">/dev/null 2>&1 || true\n" +
                "am compat reset OVERRIDE_ANY_ORIENTATION \"$PKG\" " +
                ">/dev/null 2>&1 || true\n" +
                "am compat reset OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT \"$PKG\" " +
                ">/dev/null 2>&1 || true\n";
    }

    private static String resetAllOrientationOverridesScript() {
        return resetOrientationOverrideScript("com.kurogame.mingchao") +
                resetOrientationOverrideScript("com.kurogame.mingchao.bilibili") +
                resetOrientationOverrideScript("com.kurogame.wutheringwaves.global");
    }

    private static String lockRotationScript(int rotation) {
        return rotationUserPrelude() +
                "settings --user \"$WUWA_ANDROID_USER\" put system " +
                "accelerometer_rotation 0 || exit 1\n" +
                "settings --user \"$WUWA_ANDROID_USER\" put system " +
                "user_rotation " + rotation + " || exit 1\n" +
                "wm set-ignore-orientation-request true >/dev/null 2>&1 || true\n" +
                "wm set-fix-to-user-rotation enabled >/dev/null 2>&1 || true\n" +
                "wm fixed-to-user-rotation enabled >/dev/null 2>&1 || true\n" +
                userRotationLockScript(rotation) +
                "settings --user \"$WUWA_ANDROID_USER\" put system " +
                "user_rotation " + rotation + " || exit 1\n";
    }

    private static String freeRotationScript() {
        return "wm set-user-rotation free >/dev/null 2>&1 || true\n" +
                "wm user-rotation free >/dev/null 2>&1 || true\n" +
                "wm set-fix-to-user-rotation default >/dev/null 2>&1 || true\n" +
                "wm fixed-to-user-rotation default >/dev/null 2>&1 || true\n" +
                "wm set-ignore-orientation-request false >/dev/null 2>&1 || true\n";
    }

    private static String userRotationLockScript(int rotation) {
        return "wm set-user-rotation lock " + rotation + " >/dev/null 2>&1 || true\n" +
                "wm user-rotation lock " + rotation + " >/dev/null 2>&1 || true\n";
    }

    private static String verifyLockedRotationScript(int rotation) {
        return rotationUserPrelude() +
                "ACCEL_NOW=$(settings --user \"$WUWA_ANDROID_USER\" get system " +
                "accelerometer_rotation 2>/dev/null)\n" +
                "USER_NOW=$(settings --user \"$WUWA_ANDROID_USER\" get system " +
                "user_rotation 2>/dev/null)\n" +
                "[ \"$ACCEL_NOW\" = \"0\" ] || { " +
                "echo WUWA_ROTATION_VERIFY_ACCEL >&2; exit 94; }\n" +
                "[ \"$USER_NOW\" = \"" + rotation + "\" ] || { " +
                "echo WUWA_ROTATION_VERIFY_USER >&2; exit 95; }\n";
    }

    private static String verifyRestoredRotationScript(
            int accelerometerRotation, int userRotation,
            boolean ignoreOrientationRequest, String fixedMode) {
        String expectedIgnore = ignoreOrientationRequest ? "1" : "0";
        String normalizedFixedMode = normalizeFixedRotationMode(fixedMode);
        return rotationUserPrelude() +
                "ACCEL_NOW=$(settings --user \"$WUWA_ANDROID_USER\" get system " +
                "accelerometer_rotation 2>/dev/null)\n" +
                "USER_NOW=$(settings --user \"$WUWA_ANDROID_USER\" get system " +
                "user_rotation 2>/dev/null)\n" +
                "IGNORE_NOW=$(wm get-ignore-orientation-request 2>/dev/null || true)\n" +
                "FIXED_NOW=$(wm fixed-to-user-rotation 2>/dev/null | " +
                "tail -n 1 | tr -d '\\r' || true)\n" +
                "case \"$IGNORE_NOW\" in *true*|*TRUE*) IGNORE_VALUE=1 ;; " +
                "*) IGNORE_VALUE=0 ;; esac\n" +
                "[ \"$ACCEL_NOW\" = \"" + accelerometerRotation + "\" ] || { " +
                "echo WUWA_ROTATION_RESTORE_ACCEL >&2; exit 96; }\n" +
                "[ \"$USER_NOW\" = \"" + userRotation + "\" ] || { " +
                "echo WUWA_ROTATION_RESTORE_USER >&2; exit 97; }\n" +
                "[ \"$IGNORE_VALUE\" = \"" + expectedIgnore + "\" ] || { " +
                "echo WUWA_ROTATION_RESTORE_OVERRIDE >&2; exit 98; }\n" +
                "case \"$FIXED_NOW\" in enabled|disabled|default) " +
                "[ \"$FIXED_NOW\" = \"" + normalizedFixedMode + "\" ] || { " +
                "echo WUWA_ROTATION_RESTORE_FIXED >&2; exit 99; } ;; esac\n";
    }

    private void persistRotationRecoveryMarker(RotationState state)
            throws OperationException {
        String script =
                "MARKER=" + q(ROTATION_RECOVERY_MARKER) + "\n" +
                "TEMP=\"$MARKER.tmp.$$\"\n" +
                "mkdir -p \"${MARKER%/*}\" || exit 1\n" +
                "umask 077\n" +
                "printf '%s\\n%s\\n%s\\n%s\\n%s\\n%s\\n%s\\n%s\\n' " +
                q(ROTATION_RECOVERY_MAGIC) + " " + state.accelerometerRotation + " " +
                state.userRotation + " " + state.currentRotation + " " +
                (state.ignoreOrientationRequest ? "1" : "0") +
                " " + q(state.fixedToUserRotationMode) + " " + q(state.packageName) +
                " " + state.capturedAtMillis +
                " > \"$TEMP\" || { rm -f -- \"$TEMP\"; exit 1; }\n" +
                "chmod 0644 \"$TEMP\" || { rm -f -- \"$TEMP\"; exit 1; }\n" +
                "mv -f -- \"$TEMP\" \"$MARKER\" || { " +
                "rm -f -- \"$TEMP\"; exit 1; }\n" +
                "[ -s \"$MARKER\" ] || exit 1\n";
        execute(script, null, NORMAL_TIMEOUT_SECONDS, "保存系统旋转救援状态");
    }

    private RotationState readPrivateRotationState() {
        if (!isRotationSessionActive()) return null;
        return new RotationState(
                preferences.getInt(KEY_ROTATION_ORIGINAL_ACCELEROMETER, 1),
                preferences.getInt(KEY_ROTATION_ORIGINAL_USER, 0),
                preferences.getInt(KEY_ROTATION_ORIGINAL_CURRENT,
                        preferences.getInt(KEY_ROTATION_ORIGINAL_USER, 0)),
                preferences.getBoolean(KEY_ROTATION_ORIGINAL_IGNORE_REQUEST, false),
                preferences.getString(KEY_ROTATION_ORIGINAL_FIXED_MODE, "default"),
                preferences.getString(KEY_ROTATION_SESSION_PACKAGE, ""),
                preferences.getLong(KEY_ROTATION_CAPTURED_AT, 0L));
    }

    private RotationState readPersistentRotationState() throws OperationException {
        ShellResult result = execute(
                "MARKER=" + q(ROTATION_RECOVERY_MARKER) + "\n" +
                "if [ ! -f \"$MARKER\" ]; then printf WUWA_NONE; exit 0; fi\n" +
                "cat \"$MARKER\" || exit 1",
                null, NORMAL_TIMEOUT_SECONDS, "读取系统旋转救援状态");
        String output = result.getStdout().trim();
        if ("WUWA_NONE".equals(output) || output.isEmpty()) return null;

        String[] lines = output.split("\\r?\\n");
        String magic = lines.length == 0 ? "" : lines[0].trim();
        boolean legacy = LEGACY_ROTATION_RECOVERY_MAGIC.equals(magic);
        boolean v2 = V2_ROTATION_RECOVERY_MAGIC.equals(magic);
        boolean v3 = ROTATION_RECOVERY_MAGIC.equals(magic);
        if ((!legacy && !v2 && !v3) ||
                (legacy && lines.length < 4) || (v2 && lines.length < 6)
                || (v3 && lines.length < 8)) {
            throw new OperationException("系统旋转救援状态已损坏，请使用“恢复自动旋转”");
        }
        int accelerometer = parseStrictRotationValue(lines[1], 0, 1);
        int userRotation = parseStrictRotationValue(lines[2], 0, 3);
        int currentRotation = v3 ? parseStrictRotationValue(lines[3], 0, 3) : userRotation;
        int offset = v3 ? 1 : 0;
        boolean ignoreRequest = !legacy &&
                parseStrictRotationValue(lines[3 + offset], 0, 1) == 1;
        String fixedMode = legacy ? "default" :
                normalizeFixedRotationMode(lines[4 + offset]);
        String packageName = lines[legacy ? 3 : 5 + offset].trim();
        long capturedAt = v3 ? parsePositiveLong(lines[7]) : 0L;
        if (!isKnownGamePackage(packageName)) {
            throw new OperationException("系统旋转救援状态中的游戏包名无效");
        }
        return new RotationState(
                accelerometer, userRotation, currentRotation, ignoreRequest,
                fixedMode, packageName, capturedAt);
    }

    private void restoreRotationState(RotationState state) throws OperationException {
        int accelerometer = state.accelerometerRotation == 0 ? 0 : 1;
        int userRotation = Math.max(0, Math.min(3, state.userRotation));
        String fixedMode = normalizeFixedRotationMode(state.fixedToUserRotationMode);
        String ignoreValue = state.ignoreOrientationRequest ? "true" : "false";
        AppLogger.info("Rotation", "准备精确恢复旋转状态：auto=" + accelerometer +
                "，userRotation=" + userRotation + "，ignoreRequest=" + ignoreValue +
                "，fixedMode=" + fixedMode);
        String restoreUserMode = accelerometer == 1
                ? "wm set-user-rotation free >/dev/null 2>&1 || true\n" +
                "wm user-rotation free >/dev/null 2>&1 || true\n"
                : userRotationLockScript(userRotation);
        execute(
                resetAllOrientationOverridesScript() +
                freeRotationScript() +
                rotationUserPrelude() +
                "wm set-ignore-orientation-request " + ignoreValue +
                " >/dev/null 2>&1 || true\n" +
                "wm set-fix-to-user-rotation " + fixedMode +
                " >/dev/null 2>&1 || true\n" +
                "wm fixed-to-user-rotation " + fixedMode +
                " >/dev/null 2>&1 || true\n" +
                "settings --user \"$WUWA_ANDROID_USER\" put system " +
                "user_rotation " + userRotation + " || exit 1\n" +
                restoreUserMode +
                "settings --user \"$WUWA_ANDROID_USER\" put system " +
                "accelerometer_rotation " + accelerometer + " || exit 1\n" +
                verifyRestoredRotationScript(
                        accelerometer, userRotation,
                        state.ignoreOrientationRequest, fixedMode),
                null, NORMAL_TIMEOUT_SECONDS, "恢复方向控制前的系统旋转设置");
        AppLogger.info("Rotation", "旋转状态逐项校验通过");
    }

    private void clearRotationRecoveryState() throws OperationException {
        execute(
                "MARKER=" + q(ROTATION_RECOVERY_MARKER) + "\n" +
                "rm -f -- \"$MARKER\" || exit 1\n" +
                "[ ! -e \"$MARKER\" ] || exit 1",
                null, NORMAL_TIMEOUT_SECONDS, "清理系统旋转救援状态");
        clearRotationSessionPreferences();
    }

    private void clearRotationSessionPreferences() {
        preferences.edit()
                .remove(KEY_ROTATION_SESSION_ACTIVE)
                .remove(KEY_ROTATION_SESSION_PACKAGE)
                .remove(KEY_ROTATION_ORIGINAL_ACCELEROMETER)
                .remove(KEY_ROTATION_ORIGINAL_USER)
                .remove(KEY_ROTATION_ORIGINAL_CURRENT)
                .remove(KEY_ROTATION_ORIGINAL_IGNORE_REQUEST)
                .remove(KEY_ROTATION_ORIGINAL_FIXED_MODE)
                .remove(KEY_ROTATION_SESSION_PHASE)
                .remove(KEY_ROTATION_CAPTURED_AT)
                .apply();
    }

    private static int parseRotationValue(
            String[] values, int index, int fallback, int minimum, int maximum) {
        if (index < 0 || index >= values.length) return fallback;
        try {
            int value = Integer.parseInt(values[index]);
            return value >= minimum && value <= maximum ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseStrictRotationValue(String value, int minimum, int maximum)
            throws OperationException {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= minimum && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) {
            // Handled by the common error below.
        }
        throw new OperationException("系统旋转救援状态数值无效");
    }

    private static long parsePositiveLong(String value) {
        try {
            return Math.max(0L, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * The settings CLI otherwise may read or write user 0 on some tablet and work-profile
     * builds even when the visible Android user is different. Resolve the active user for every
     * rotation transaction so the quick-settings auto-rotate state and our shell state agree.
     */
    private static String rotationUserPrelude() {
        return "WUWA_ANDROID_USER=$(am get-current-user 2>/dev/null | tr -d '\\r')\n" +
                "case \"$WUWA_ANDROID_USER\" in ''|*[!0-9]*) WUWA_ANDROID_USER=0 ;; esac\n";
    }

    private static boolean isKnownGamePackage(String packageName) {
        return "com.kurogame.mingchao".equals(packageName) ||
                "com.kurogame.mingchao.bilibili".equals(packageName) ||
                "com.kurogame.wutheringwaves.global".equals(packageName);
    }

    private static String normalizeFixedRotationMode(String value) {
        if (value == null) return "default";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "enabled".equals(normalized) || "disabled".equals(normalized)
                ? normalized : "default";
    }

    private static final class RotationState {
        final int accelerometerRotation;
        final int userRotation;
        final int currentRotation;
        final boolean ignoreOrientationRequest;
        final String fixedToUserRotationMode;
        final String packageName;
        final long capturedAtMillis;

        RotationState(
                int accelerometerRotation, int userRotation,
                int currentRotation, boolean ignoreOrientationRequest,
                String fixedToUserRotationMode,
                String packageName) {
            this(accelerometerRotation, userRotation, currentRotation,
                    ignoreOrientationRequest, fixedToUserRotationMode,
                    packageName, System.currentTimeMillis());
        }

        RotationState(
                int accelerometerRotation, int userRotation, int currentRotation,
                boolean ignoreOrientationRequest, String fixedToUserRotationMode,
                String packageName, long capturedAtMillis) {
            this.accelerometerRotation = accelerometerRotation;
            this.userRotation = userRotation;
            this.currentRotation = currentRotation;
            this.ignoreOrientationRequest = ignoreOrientationRequest;
            this.fixedToUserRotationMode = normalizeFixedRotationMode(fixedToUserRotationMode);
            this.packageName = packageName == null ? "" : packageName;
            this.capturedAtMillis = capturedAtMillis;
        }
    }

    public static final class RotationRecoveryInfo {
        public final boolean automaticRotationEnabled;
        public final int userRotation;
        public final int currentRotation;
        public final String phase;
        public final long capturedAtMillis;
        public final String packageName;

        RotationRecoveryInfo(
                boolean automaticRotationEnabled, int userRotation, int currentRotation,
                String phase, long capturedAtMillis, String packageName) {
            this.automaticRotationEnabled = automaticRotationEnabled;
            this.userRotation = userRotation;
            this.currentRotation = currentRotation;
            this.phase = phase == null ? ROTATION_PHASE_ACTIVE : phase;
            this.capturedAtMillis = capturedAtMillis;
            this.packageName = packageName == null ? "" : packageName;
        }
    }

    public static final class SystemLoadSample {
        public final long cpuTotal;
        public final long cpuIdle;
        public final long gpuActive;
        public final long gpuTotal;
        public final int gpuDirectPercent;

        public SystemLoadSample(
                long cpuTotal, long cpuIdle, long gpuActive, long gpuTotal,
                int gpuDirectPercent) {
            this.cpuTotal = cpuTotal;
            this.cpuIdle = cpuIdle;
            this.gpuActive = gpuActive;
            this.gpuTotal = gpuTotal;
            this.gpuDirectPercent = gpuDirectPercent;
        }
    }

    private void requireOfficialConfig() throws OperationException {
        if (!officialConfigBundle.isReady() && !officialConfigBundle.prepare()) {
            throw new OperationException("内置游戏原配置缺失或已损坏，请重新安装 App");
        }
        if (officialConfigBundle.files().isEmpty()) {
            throw new OperationException("内置游戏原配置为空，请重新安装 App");
        }
    }

    private void restoreOfficialFromPrivateDirectoryWithRoot(GameServer server)
            throws OperationException {
        List<File> files = officialConfigBundle.files();
        StringBuilder script = new StringBuilder();
        script.append("TARGET=").append(q(server.getConfigDirectory())).append("\n")
                .append("if [ ! -d \"$TARGET\" ]; then echo WUWA_TARGET_MISSING >&2; exit 40; fi\n");
        for (File file : files) {
            String destination = server.getConfigDirectory() + "/" + file.getName();
            script.append("cp -f ").append(q(file.getAbsolutePath())).append(" ")
                    .append(q(destination))
                    .append(" || { echo WUWA_RESTORE_OFFICIAL_COPY_FAILED:")
                    .append(file.getName()).append(" >&2; exit 50; }\n");
        }
        for (File file : files) {
            String destination = server.getConfigDirectory() + "/" + file.getName();
            script.append("cmp -s ").append(q(file.getAbsolutePath())).append(" ")
                    .append(q(destination))
                    .append(" || { echo WUWA_RESTORE_OFFICIAL_VERIFY_FAILED:")
                    .append(file.getName()).append(" >&2; exit 51; }\n");
        }
        execute(script.toString(), null, COPY_TIMEOUT_SECONDS, "恢复内置游戏原配置");
    }

    private void restoreOfficialThroughShizukuStaging(GameServer server)
            throws OperationException {
        String token = UUID.randomUUID().toString().replace("-", "");
        String staging = "/data/local/tmp/wuwa_official_config_" + token;
        List<File> files = officialConfigBundle.files();
        try {
            execute(
                    "umask 077; rm -rf -- " + q(staging) + " && mkdir -p " + q(staging),
                    null,
                    NORMAL_TIMEOUT_SECONDS,
                    "准备内置游戏原配置临时目录");
            for (File file : files) {
                stagePrivateFile(file, staging + "/" + file.getName());
            }

            StringBuilder script = new StringBuilder();
            script.append("TARGET=").append(q(server.getConfigDirectory())).append("\n")
                    .append("if [ ! -d \"$TARGET\" ]; then echo WUWA_TARGET_MISSING >&2; exit 40; fi\n");
            for (File file : files) {
                String source = staging + "/" + file.getName();
                String destination = server.getConfigDirectory() + "/" + file.getName();
                script.append("cp -f ").append(q(source)).append(" ").append(q(destination))
                        .append(" || { echo WUWA_RESTORE_OFFICIAL_COPY_FAILED:")
                        .append(file.getName()).append(" >&2; exit 50; }\n");
            }
            for (File file : files) {
                String source = staging + "/" + file.getName();
                String destination = server.getConfigDirectory() + "/" + file.getName();
                script.append("cmp -s ").append(q(source)).append(" ").append(q(destination))
                        .append(" || { echo WUWA_RESTORE_OFFICIAL_VERIFY_FAILED:")
                        .append(file.getName()).append(" >&2; exit 51; }\n");
            }
            execute(script.toString(), null, COPY_TIMEOUT_SECONDS, "恢复内置游戏原配置");
        } finally {
            try {
                privilegeManager.execute(
                        "rm -rf -- " + q(staging), null, NORMAL_TIMEOUT_SECONDS);
            } catch (Exception ignored) {
                // Random app-owned staging directory; cleanup is best effort.
            }
        }
    }

    private void runBackupTransaction(GameServer server) throws OperationException {
        String target = server.getConfigDirectory();
        String backup = getBackupDirectory(server);
        String legacy = server.getLegacyBackupDirectory();
        String temporary = backup + ".tmp_" +
                UUID.randomUUID().toString().replace("-", "");
        String script =
                "TARGET=" + q(target) + "\n" +
                "BACKUP=" + q(backup) + "\n" +
                "LEGACY=" + q(legacy) + "\n" +
                "TEMP=" + q(temporary) + "\n" +
                "if [ ! -d \"$TARGET\" ]; then\n" +
                "  echo WUWA_TARGET_MISSING >&2; exit 40\n" +
                "fi\n" +
                "if [ -z \"$(ls -A \"$TARGET\" 2>/dev/null)\" ]; then\n" +
                "  echo WUWA_TARGET_EMPTY >&2; exit 41\n" +
                "fi\n" +
                legacyBackupMigrationScript() +
                "if [ -f \"$BACKUP/" + BACKUP_MARKER + "\" ]; then exit 0; fi\n" +
                "if [ -e \"$BACKUP\" ] || [ -L \"$BACKUP\" ]; then\n" +
                "  echo WUWA_INCOMPLETE_BACKUP >&2; exit 42\n" +
                "fi\n" +
                safeCopyPreflight("$TARGET", "$TEMP") +
                "mkdir -p \"${BACKUP%/*}\" || { echo WUWA_BACKUP_PREPARE_FAILED >&2; exit 42; }\n" +
                "mkdir \"$TEMP\" || { echo WUWA_BACKUP_PREPARE_FAILED >&2; exit 42; }\n" +
                contentOnlyTreeCopy("$TARGET", "$TEMP", false,
                        "WUWA_BACKUP_COPY_FAILED") +
                ": > \"$TEMP/" + BACKUP_MARKER + "\" || { rm -rf -- \"$TEMP\"; echo WUWA_BACKUP_MARK_FAILED >&2; exit 44; }\n" +
                "mv \"$TEMP\" \"$BACKUP\" || { rm -rf -- \"$TEMP\"; echo WUWA_BACKUP_MARK_FAILED >&2; exit 44; }\n";
        execute(script, null, COPY_TIMEOUT_SECONDS, "首次完整备份");
    }

    private void replaceFromPrivateDirectoryWithRoot(GameServer server, QualityPreset preset)
            throws OperationException {
        String target = server.getConfigDirectory();
        File selectedDirectory = getPresetDirectory(preset);
        File engine = new File(selectedDirectory, ENGINE_FILE);
        File device = new File(selectedDirectory, DEVICE_PROFILE_FILE);
        String script =
                "TARGET=" + q(target) + "\n" +
                "if [ ! -d \"$TARGET\" ]; then echo WUWA_TARGET_MISSING >&2; exit 40; fi\n" +
                "cp -f " + q(engine.getAbsolutePath()) + " \"$TARGET/" + ENGINE_FILE + "\" || { echo WUWA_REPLACE_FAILED_ENGINE >&2; exit 45; }\n" +
                "cp -f " + q(device.getAbsolutePath()) + " \"$TARGET/" + DEVICE_PROFILE_FILE + "\" || { echo WUWA_REPLACE_FAILED_DEVICE >&2; exit 45; }\n" +
                "cmp -s " + q(engine.getAbsolutePath()) + " \"$TARGET/" + ENGINE_FILE + "\" || { echo WUWA_VERIFY_FAILED_ENGINE >&2; exit 45; }\n" +
                "cmp -s " + q(device.getAbsolutePath()) + " \"$TARGET/" + DEVICE_PROFILE_FILE + "\" || { echo WUWA_VERIFY_FAILED_DEVICE >&2; exit 45; }\n";
        execute(script, null, NORMAL_TIMEOUT_SECONDS, "替换画质预设");
    }

    private void replaceThroughShizukuStaging(GameServer server, QualityPreset preset)
            throws OperationException {
        String token = UUID.randomUUID().toString().replace("-", "");
        String staging = "/data/local/tmp/wuwa_config_manager_" + token;
        String stagedEngine = staging + "/" + ENGINE_FILE;
        String stagedDevice = staging + "/" + DEVICE_PROFILE_FILE;
        File selectedDirectory = getPresetDirectory(preset);
        File engine = new File(selectedDirectory, ENGINE_FILE);
        File device = new File(selectedDirectory, DEVICE_PROFILE_FILE);

        try {
            execute(
                    "umask 077; rm -rf -- " + q(staging) + " && mkdir -p " + q(staging),
                    null,
                    NORMAL_TIMEOUT_SECONDS,
                    "准备 Shizuku 临时目录");
            stagePrivateFile(engine, stagedEngine);
            stagePrivateFile(device, stagedDevice);

            String target = server.getConfigDirectory();
            String script =
                    "TARGET=" + q(target) + "\n" +
                    "if [ ! -d \"$TARGET\" ]; then echo WUWA_TARGET_MISSING >&2; exit 40; fi\n" +
                    "cp -f " + q(stagedEngine) + " \"$TARGET/" + ENGINE_FILE + "\" || { echo WUWA_REPLACE_FAILED_ENGINE >&2; exit 45; }\n" +
                    "cp -f " + q(stagedDevice) + " \"$TARGET/" + DEVICE_PROFILE_FILE + "\" || { echo WUWA_REPLACE_FAILED_DEVICE >&2; exit 45; }\n" +
                    "cmp -s " + q(stagedEngine) + " \"$TARGET/" + ENGINE_FILE + "\" || { echo WUWA_VERIFY_FAILED_ENGINE >&2; exit 45; }\n" +
                    "cmp -s " + q(stagedDevice) + " \"$TARGET/" + DEVICE_PROFILE_FILE + "\" || { echo WUWA_VERIFY_FAILED_DEVICE >&2; exit 45; }\n";
            execute(script, null, NORMAL_TIMEOUT_SECONDS, "替换画质预设");
        } finally {
            try {
                privilegeManager.execute(
                        "rm -rf -- " + q(staging), null, NORMAL_TIMEOUT_SECONDS);
            } catch (Exception ignored) {
                // The random, private staging directory is best-effort cleanup only.
            }
        }
    }

    private void stagePrivateFile(File source, String destination)
            throws OperationException {
        if (!validPreset(source)) {
            throw new OperationException("预设文件缺失，请重新安装App");
        }
        if (source.length() > MAX_PRESET_BYTES) {
            throw new OperationException(
                    source.getName() + " 超过 8 MB，无法安全传输到 Shizuku");
        }

        byte[] buffer = new byte[BINDER_CHUNK_BYTES];
        boolean first = true;
        try (InputStream input = new FileInputStream(source)) {
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                byte[] chunk = count == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, count);
                String redirect = first ? ">" : ">>";
                execute(
                        "umask 077; cat " + redirect + " " + q(destination),
                        chunk,
                        NORMAL_TIMEOUT_SECONDS,
                        "传输预设文件 " + source.getName());
                first = false;
            }
            if (first) {
                execute(
                        ": > " + q(destination),
                        null,
                        NORMAL_TIMEOUT_SECONDS,
                        "传输预设文件 " + source.getName());
            }
        } catch (IOException error) {
            throw new OperationException("读取私有预设失败：" + source.getName(), error);
        }
    }

    private ShellResult execute(
            String command, byte[] stdin, int timeoutSeconds, String operation)
            throws OperationException {
        long startedAt = android.os.SystemClock.elapsedRealtime();
        AppLogger.debug("Shell", "开始：" + operation + "；timeout=" +
                timeoutSeconds + "s；stdin=" + (stdin == null ? 0 : stdin.length) + "B");
        final ShellResult result;
        try {
            result = privilegeManager.execute(command, stdin, timeoutSeconds);
        } catch (SecurityException error) {
            AppLogger.error("Shell", operation + " 权限失败", error);
            throw new OperationException("权限不足，请检查Shizuku/Root状态", error);
        } catch (Exception error) {
            AppLogger.error("Shell", operation + " 执行异常", error);
            throw new OperationException(operation + "失败：" + safeMessage(error), error);
        }
        if (!result.isSuccess()) {
            AppLogger.error("Shell", operation + " 失败；耗时=" +
                    (android.os.SystemClock.elapsedRealtime() - startedAt) + "ms；exit=" +
                    result.getExitCode() + "；stderr=" + limitLogText(result.getStderr()), null);
            throw new OperationException(describeShellFailure(operation, result));
        }
        AppLogger.debug("Shell", operation + " 成功；耗时=" +
                (android.os.SystemClock.elapsedRealtime() - startedAt) + "ms；exit=" +
                result.getExitCode());
        return result;
    }

    private static String limitLogText(String value) {
        if (value == null) return "";
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 600 ? normalized : normalized.substring(0, 600) + "…";
    }

    private String describeShellFailure(String operation, ShellResult result) {
        String stderr = result.getStderr().trim();
        if (stderr.contains("WUWA_RESTORE_OFFICIAL_COPY_FAILED")) {
            return "恢复内置游戏原配置时复制失败，请确认游戏已完全退出并重新检测 Root/Shizuku 权限" + detail(stderr);
        }
        if (stderr.contains("WUWA_RESTORE_OFFICIAL_VERIFY_FAILED")) {
            return "恢复内置游戏原配置后校验失败，目标文件与安装包内置文件不一致" + detail(stderr);
        }
        if (stderr.contains("WUWA_TARGET_MISSING") || stderr.contains("WUWA_TARGET_EMPTY")) {
            return "未找到游戏配置文件，请确保已安装游戏并至少运行过一次";
        }
        if (stderr.contains("WUWA_NO_BACKUP")) {
            return "没有可用的备份，无法恢复";
        }
        if (stderr.contains("WUWA_NO_MANUAL_BACKUP")) {
            return "这份手动备份不存在或不完整，无法恢复。请返回备份列表重新选择。";
        }
        if (stderr.contains("WUWA_BACKUP_PREPARE_FAILED")) {
            return "无法创建备份目录，请检查 Download 目录访问权限" + detail(stderr);
        }
        if (stderr.contains("WUWA_INCOMPLETE_BACKUP")) {
            return "检测到上次中断后遗留的不完整备份目录。为避免误删文件，本次操作已安全终止。\n\n" +
                    "请在“备份”页面确认存储位置后，手动移走对应服务器的无效备份目录，再重新操作。" +
                    detail(stderr);
        }
        if (stderr.contains("WUWA_STORAGE_CREATE_FAILED")) {
            return "无法创建新的备份存储目录，请检查路径是否有效，以及 Root/Shizuku 是否仍可访问该位置"
                    + detail(stderr);
        }
        if (stderr.contains("WUWA_BACKUP_COPY_FAILED")) {
            return "备份复制失败，原配置未被替换" + detail(stderr);
        }
        if (stderr.contains("WUWA_BACKUP_MARK_FAILED")) {
            return "备份文件已复制，但无法创建成功标记；为安全起见已终止替换" + detail(stderr);
        }
        if (stderr.contains("WUWA_BACKUP_MIGRATION_FAILED")) {
            return "检测到旧版本备份，但无法将它安全复制到新的 Wuwa CFBP 目录。\n\n" +
                    "为避免覆盖原始配置，本次操作已终止。请检查存储空间和权限后重试。" +
                    detail(stderr);
        }
        if (stderr.contains("WUWA_MANUAL_BACKUP_PREPARE_FAILED")) {
            return "手动备份失败：无法创建快照目录。\n\n" +
                    "请确认 Download/Wuwa CFBP 可访问，并重新检测 Root 或 Shizuku 权限。" +
                    detail(stderr);
        }
        if (stderr.contains("WUWA_MANUAL_BACKUP_COPY_FAILED")
                || stderr.contains("WUWA_MANUAL_BACKUP_MARK_FAILED")) {
            return "手动备份失败：游戏配置没有完整复制，未完成的快照已经清理。\n\n" +
                    "请确认游戏已至少运行一次，然后重新检测权限后再试。" + detail(stderr);
        }
        if (stderr.contains("WUWA_VERIFY_FAILED_ENGINE")) {
            return "Engine.ini 覆盖后校验不一致，操作已中止。请确认游戏已完全退出，并重新检测 Root/Shizuku 权限。" +
                    detail(stderr);
        }
        if (stderr.contains("WUWA_VERIFY_FAILED_DEVICE")) {
            return "DeviceProfiles.ini 覆盖后校验不一致，操作已中止。请确认游戏已完全退出，并重新检测 Root/Shizuku 权限。" +
                    detail(stderr);
        }
        if (stderr.contains("WUWA_REPLACE_FAILED")) {
            return "预设文件覆盖失败，原始备份仍然保留" + detail(stderr);
        }
        if (stderr.contains("WUWA_RESTORE_COPY_FAILED")) {
            return "恢复备份时复制失败" + detail(stderr);
        }
        if (stderr.contains("WUWA_DELETE_FAILED") || stderr.contains("WUWA_UNSAFE_DELETE")) {
            return "删除备份失败" + detail(stderr);
        }
        if (stderr.contains("WUWA_UNSAFE_COPY_PATH")
                || stderr.contains("WUWA_UNSAFE_SOURCE_LINK")) {
            return "为保护手机存储，已拒绝执行本次复制：备份路径与游戏目录存在嵌套，或源目录包含符号链接。\n\n" +
                    "请将备份位置改到 Download、Documents 等普通共享文件夹后重试。" + detail(stderr);
        }
        if (stderr.contains("WUWA_SOURCE_TOO_LARGE")) {
            return "检测到配置目录体积异常（超过 256 MB），已为保护手机存储终止操作。\n\n" +
                    "正常的游戏配置目录不应达到这一大小。请检查备份位置、Root 挂载模块和游戏目录是否被重定向。" +
                    detail(stderr);
        }
        String lowerError = stderr.toLowerCase(Locale.ROOT);
        if (lowerError.contains("transport endpoint is not connected")) {
            return "系统共享存储连接已中断，当前无法访问游戏配置目录。\n\n" +
                    "请依次尝试：\n" +
                    "1. 打开 Shizuku，确认服务仍在运行；\n" +
                    "2. 回到本 App，在“设置”中点击“重新连接 Shizuku”；\n" +
                    "3. 若仍失败，请重启 Shizuku 或手机后重试。\n\n" +
                    "本次操作没有完成，请不要将其视为恢复成功。" + detail(stderr);
        }
        if (lowerError.contains("permission denied")) {
            return "权限不足，请检查Shizuku/Root状态" + detail(stderr);
        }
        String message = operation + "失败（退出码 " + result.getExitCode() + "）";
        return message + detail(stderr.isEmpty() ? result.getStdout().trim() : stderr);
    }

    private void requirePresets(QualityPreset preset) throws OperationException {
        if (!presetsReady(preset)) {
            throw new OperationException("预设文件缺失，请重新安装App");
        }
    }

    private void copyAssetAtomically(String assetPath, File destination) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("无法清理临时预设文件");
        }
        try (InputStream input = appContext.getAssets().open(assetPath);
             OutputStream output = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[8192];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                if (count > 0) output.write(buffer, 0, count);
            }
            output.flush();
        }
        if (!temporary.isFile()) {
            temporary.delete();
            throw new IOException("预设文件复制失败：" + assetPath);
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("无法更新工作预设：" + assetPath);
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("无法写入工作预设：" + assetPath);
        }
    }

    private void invalidateWorkingPresets() {
        for (QualityPreset preset : QualityPreset.values()) {
            File directory = getPresetDirectory(preset);
            new File(directory, ENGINE_FILE).delete();
            new File(directory, DEVICE_PROFILE_FILE).delete();
            new File(directory, ENGINE_FILE + ".tmp").delete();
            new File(directory, DEVICE_PROFILE_FILE + ".tmp").delete();
            new File(directory, LEGACY_DEVICE_PROFILE_FILE).delete();
            new File(directory, LEGACY_DEVICE_PROFILE_FILE + ".tmp").delete();
        }
        // Clean up the previous single-tier working layout during app upgrades.
        new File(presetDirectory, ENGINE_FILE).delete();
        new File(presetDirectory, DEVICE_PROFILE_FILE).delete();
        new File(presetDirectory, LEGACY_DEVICE_PROFILE_FILE).delete();
    }

    private static boolean validPreset(File file) {
        return file.isFile();
    }

    private static boolean usablePreset(File file) {
        if (!validPreset(file)) return false;
        if (file.length() == 0L) return true;
        byte[] buffer = new byte[512];
        try (InputStream input = new FileInputStream(file)) {
            int count = input.read(buffer);
            if (count <= 0) return false;
            String header = new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8);
            return !header.contains(PRESET_PLACEHOLDER_MARKER);
        } catch (IOException error) {
            return false;
        }
    }

    private String[] nonNullAssets(String directory) throws IOException {
        String[] assets = appContext.getAssets().list(directory);
        return assets == null ? new String[0] : assets;
    }

    private boolean allPresetsReady() {
        for (QualityPreset preset : QualityPreset.values()) {
            if (!presetsReady(preset)) return false;
        }
        return true;
    }

    private File getPresetDirectory(QualityPreset preset) {
        return new File(presetDirectory, preset.getId());
    }

    private String getBackupDirectory(GameServer server) {
        return getStorageRoot() + "/Backup/" + server.getPackageName();
    }

    private String getManualBackupDirectory(GameServer server) {
        return getStorageRoot() + "/ManualBackup/" + server.getPackageName();
    }

    private String readManualBackupDisplayName(GameServer server, String snapshotName)
            throws OperationException {
        String nameFile = getManualBackupDirectory(server) + "/" + snapshotName +
                "/" + DISPLAY_NAME_FILE;
        ShellResult result = execute(
                "FILE=" + q(nameFile) + "\n" +
                "[ -f \"$FILE\" ] && head -c 120 \"$FILE\" || true",
                null,
                NORMAL_TIMEOUT_SECONDS,
                "读取备份名称");
        String value = result.getStdout().replaceAll("[\\r\\n\\u0000-\\u001F]", "").trim();
        return value.isEmpty() ? null : value;
    }

    private static String normalizeStorageRoot(String value) {
        String root = value == null ? DEFAULT_STORAGE_ROOT : value.trim().replace('\\', '/');
        while (root.endsWith("/") && root.length() > 1) {
            root = root.substring(0, root.length() - 1);
        }
        return root;
    }

    private static boolean isSafeStorageRoot(String root) {
        if (root == null) return false;
        String lower = root.toLowerCase(Locale.ROOT);
        return root.startsWith("/storage/emulated/0/")
                && root.length() > "/storage/emulated/0/".length()
                && !root.contains("..")
                && !root.contains("//")
                && !root.contains("/./")
                && !root.contains("\n")
                && !root.contains("\r")
                && !lower.equals("/storage/emulated/0/android")
                && !lower.startsWith("/storage/emulated/0/android/")
                && root.length() <= 220;
    }

    /**
     * Rejects recursive-copy sources containing symbolic links and any source/destination
     * overlap. Shared-storage FUSE must only receive ordinary recursive copies: archive mode
     * attempts chmod/chown/setattr and can crash MediaProvider on some Android 15/16 ROMs.
     */
    private static String safeCopyPreflight(String sourceExpression, String destinationExpression) {
        return "COPY_SOURCE=\"" + sourceExpression + "\"\n" +
                "COPY_DEST=\"" + destinationExpression + "\"\n" +
                "case \"$COPY_SOURCE/\" in \"$COPY_DEST/\"*) echo WUWA_UNSAFE_COPY_PATH >&2; exit 62 ;; esac\n" +
                "case \"$COPY_DEST/\" in \"$COPY_SOURCE/\"*) echo WUWA_UNSAFE_COPY_PATH >&2; exit 62 ;; esac\n" +
                "if [ -L \"$COPY_SOURCE\" ] || [ -L \"$COPY_DEST\" ]; then echo WUWA_UNSAFE_COPY_PATH >&2; exit 62; fi\n" +
                "if [ -n \"$(find \"$COPY_SOURCE\" -type l -print 2>/dev/null | head -n 1)\" ]; then echo WUWA_UNSAFE_SOURCE_LINK >&2; exit 62; fi\n" +
                "if [ -d \"$COPY_DEST\" ] && [ -n \"$(find \"$COPY_DEST\" -type l -print 2>/dev/null | head -n 1)\" ]; then echo WUWA_UNSAFE_SOURCE_LINK >&2; exit 62; fi\n" +
                "COPY_KB=$(du -sk \"$COPY_SOURCE\" 2>/dev/null | awk 'NR == 1 { print $1 }')\n" +
                "case \"$COPY_KB\" in ''|*[!0-9]*) echo WUWA_UNSAFE_COPY_PATH >&2; exit 62 ;; esac\n" +
                "[ \"$COPY_KB\" -le " + MAX_CONFIG_TREE_KB + " ] || { echo WUWA_SOURCE_TOO_LARGE >&2; exit 63; }\n";
    }

    /**
     * Copies directory contents without asking Android shared storage to preserve chmod,
     * ownership, timestamps or xattrs. On affected Android 15/16 builds even a plain recursive
     * cp can issue FUSE setattr calls and crash MediaProvider. Redirection keeps existing target
     * file permissions and creates new files using the executing shell's normal umask.
     */
    private static String contentOnlyTreeCopy(
            String sourceExpression,
            String destinationExpression,
            boolean excludeBackupMetadata,
            String failureToken) {
        String excludeFlag = excludeBackupMetadata ? "1" : "0";
        return "wuwa_content_copy() {\n" +
                "  WUWA_COPY_SOURCE=\"$1\"\n" +
                "  WUWA_COPY_DEST=\"$2\"\n" +
                "  WUWA_COPY_EXCLUDE=\"$3\"\n" +
                "  mkdir -p \"$WUWA_COPY_DEST\" || return 1\n" +
                "  find \"$WUWA_COPY_SOURCE\" -mindepth 1 -type d -print 2>/dev/null |\n" +
                "  while IFS= read -r WUWA_ITEM; do\n" +
                "    WUWA_REL=${WUWA_ITEM#\"$WUWA_COPY_SOURCE\"/}\n" +
                "    mkdir -p \"$WUWA_COPY_DEST/$WUWA_REL\" || exit 1\n" +
                "  done || return 1\n" +
                "  find \"$WUWA_COPY_SOURCE\" -mindepth 1 -type f -print 2>/dev/null |\n" +
                "  while IFS= read -r WUWA_ITEM; do\n" +
                "    WUWA_REL=${WUWA_ITEM#\"$WUWA_COPY_SOURCE\"/}\n" +
                "    if [ \"$WUWA_COPY_EXCLUDE\" = 1 ]; then\n" +
                "      case \"$WUWA_REL\" in\n" +
                "        " + BACKUP_MARKER + "|" + DISPLAY_NAME_FILE + "|" +
                AUTOMATIC_BACKUP_MARKER + ") continue ;;\n" +
                "      esac\n" +
                "    fi\n" +
                "    WUWA_OUTPUT=\"$WUWA_COPY_DEST/$WUWA_REL\"\n" +
                "    mkdir -p \"${WUWA_OUTPUT%/*}\" || exit 1\n" +
                "    cat \"$WUWA_ITEM\" > \"$WUWA_OUTPUT\" || exit 1\n" +
                "  done || return 1\n" +
                "}\n" +
                "wuwa_content_copy \"" + sourceExpression + "\" \"" +
                destinationExpression + "\" " + excludeFlag + " || { echo " +
                failureToken + " >&2; exit 71; }\n";
    }

    private static String legacyBackupMigrationScript() {
        return "if [ ! -f \"$BACKUP/" + BACKUP_MARKER + "\" ] " +
                "&& [ -f \"$LEGACY/" + BACKUP_MARKER + "\" ]; then\n" +
                "  [ ! -e \"$BACKUP\" ] && [ ! -L \"$BACKUP\" ] || { " +
                "echo WUWA_INCOMPLETE_BACKUP >&2; exit 49; }\n" +
                "  mkdir -p \"${BACKUP%/*}\" || { " +
                "echo WUWA_BACKUP_MIGRATION_FAILED >&2; exit 49; }\n" +
                "  MIGRATION_TEMP=\"${BACKUP}.migration_tmp_$$\"\n" +
                "  [ ! -e \"$MIGRATION_TEMP\" ] || { echo WUWA_BACKUP_MIGRATION_FAILED >&2; exit 49; }\n" +
                safeCopyPreflight("$LEGACY", "$MIGRATION_TEMP") +
                "  mkdir \"$MIGRATION_TEMP\" || { echo WUWA_BACKUP_MIGRATION_FAILED >&2; exit 49; }\n" +
                contentOnlyTreeCopy("$LEGACY", "$MIGRATION_TEMP", false,
                        "WUWA_BACKUP_MIGRATION_FAILED") +
                "  [ -f \"$MIGRATION_TEMP/" + BACKUP_MARKER + "\" ] || { " +
                "rm -rf -- \"$MIGRATION_TEMP\"; " +
                "echo WUWA_BACKUP_MIGRATION_FAILED >&2; exit 49; }\n" +
                "  mv \"$MIGRATION_TEMP\" \"$BACKUP\" || { rm -rf -- \"$MIGRATION_TEMP\"; " +
                "echo WUWA_BACKUP_MIGRATION_FAILED >&2; exit 49; }\n" +
                "fi\n";
    }

    private static boolean isSafeSnapshotName(String name) {
        return name != null && name.matches("[0-9]{8}_[0-9]{6}_[0-9]{3}");
    }

    private static String q(String value) {
        return ShellEscaper.quote(value);
    }

    private static String detail(String shellDetail) {
        String cleaned = shellDetail
                .replaceAll("WUWA_[A-Z_]+", "")
                .trim();
        return cleaned.isEmpty() ? "" : "\n\n详细信息：" + cleaned;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
