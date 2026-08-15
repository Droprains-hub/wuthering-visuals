package com.wuwa.config.manager.privilege;

public final class PrivilegeState {
    public enum ShizukuStatus {
        NOT_RUNNING,
        WAITING_PERMISSION,
        DENIED,
        CONNECTING,
        READY,
        ERROR
    }

    private final boolean checkingRoot;
    private final boolean rootAvailable;
    private final ShizukuStatus shizukuStatus;
    private final String detail;

    public PrivilegeState(
            boolean checkingRoot,
            boolean rootAvailable,
            ShizukuStatus shizukuStatus,
            String detail) {
        this.checkingRoot = checkingRoot;
        this.rootAvailable = rootAvailable;
        this.shizukuStatus = shizukuStatus;
        this.detail = detail;
    }

    public boolean isCheckingRoot() {
        return checkingRoot;
    }

    public boolean isRootAvailable() {
        return rootAvailable;
    }

    public ShizukuStatus getShizukuStatus() {
        return shizukuStatus;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isShizukuReady() {
        return shizukuStatus == ShizukuStatus.READY;
    }

    public boolean isReady() {
        return rootAvailable || isShizukuReady();
    }
}

