package com.wuwa.config.manager.model;

import java.util.Arrays;
import java.util.List;

public enum GameServer {
    CHINA_OFFICIAL("国服", "com.kurogame.mingchao"),
    CHINA_BILIBILI("哔哩哔哩服（B服）", "com.kurogame.mingchao.bilibili"),
    GLOBAL("国际服（Global）", "com.kurogame.wutheringwaves.global");

    public static final String CONFIG_SUFFIX =
            "/files/UE4Game/Client/Client/Saved/Config/Android";
    public static final String BACKUP_ROOT =
            "/storage/emulated/0/Download/Wuwa CFBP/Backup";
    public static final String MANUAL_BACKUP_ROOT =
            "/storage/emulated/0/Download/Wuwa CFBP/ManualBackup";
    private static final String LEGACY_BACKUP_ROOT =
            "/storage/emulated/0/Download/鸣潮画质助手/Backup";

    private final String displayName;
    private final String packageName;

    GameServer(String displayName, String packageName) {
        this.displayName = displayName;
        this.packageName = packageName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getConfigDirectory() {
        return "/storage/emulated/0/Android/data/" + packageName + CONFIG_SUFFIX;
    }

    public String getBackupDirectory() {
        return BACKUP_ROOT + "/" + packageName;
    }

    public String getManualBackupDirectory() {
        return MANUAL_BACKUP_ROOT + "/" + packageName;
    }

    public String getLegacyBackupDirectory() {
        return LEGACY_BACKUP_ROOT + "/" + packageName;
    }

    public String getTargetLabel() {
        return "当前目标：" + displayName + " (" + packageName + ")";
    }

    public static GameServer fromPackageName(String packageName) {
        if (packageName == null) return null;
        for (GameServer server : values()) {
            if (server.packageName.equals(packageName)) return server;
        }
        return null;
    }

    public static List<GameServer> all() {
        return Arrays.asList(values());
    }
}
