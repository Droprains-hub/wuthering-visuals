package com.wuwa.config.manager.data;

public final class ManualBackupInfo {
    private final String snapshotName;
    private final String displayName;
    private final long sizeBytes;
    private final boolean automatic;

    public ManualBackupInfo(String snapshotName, long sizeBytes) {
        this(snapshotName, null, sizeBytes, false);
    }

    public ManualBackupInfo(String snapshotName, String displayName, long sizeBytes) {
        this(snapshotName, displayName, sizeBytes, false);
    }

    public ManualBackupInfo(
            String snapshotName, String displayName, long sizeBytes, boolean automatic) {
        this.snapshotName = snapshotName;
        this.displayName = displayName == null || displayName.trim().isEmpty()
                ? null : displayName.trim();
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.automatic = automatic;
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean hasCustomName() {
        return displayName != null;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public boolean isAutomatic() {
        return automatic;
    }
}
