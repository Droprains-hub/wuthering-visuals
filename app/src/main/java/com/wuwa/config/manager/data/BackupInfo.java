package com.wuwa.config.manager.data;

import com.wuwa.config.manager.model.GameServer;

public final class BackupInfo {
    private final GameServer server;
    private final boolean exists;
    private final boolean valid;
    private final long sizeBytes;
    private final int manualCount;
    private final long manualSizeBytes;

    public BackupInfo(
            GameServer server,
            boolean exists,
            boolean valid,
            long sizeBytes,
            int manualCount,
            long manualSizeBytes) {
        this.server = server;
        this.exists = exists;
        this.valid = valid;
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.manualCount = Math.max(0, manualCount);
        this.manualSizeBytes = Math.max(0L, manualSizeBytes);
    }

    public GameServer getServer() {
        return server;
    }

    public boolean exists() {
        return exists;
    }

    public boolean isValid() {
        return valid;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public int getManualCount() {
        return manualCount;
    }

    public long getManualSizeBytes() {
        return manualSizeBytes;
    }

    public boolean hasAnyBackup() {
        return exists || manualCount > 0;
    }
}
