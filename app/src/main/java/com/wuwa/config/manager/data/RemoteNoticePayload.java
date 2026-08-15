package com.wuwa.config.manager.data;

import androidx.annotation.Nullable;

/** Parsed, immutable remote announcement and update configuration. */
public final class RemoteNoticePayload {
    private final int schema;
    private final String revision;
    @Nullable private final Announcement announcement;
    @Nullable private final Update update;
    @Nullable private final SecurityMode securityMode;

    public RemoteNoticePayload(
            int schema,
            String revision,
            @Nullable Announcement announcement,
            @Nullable Update update,
            @Nullable SecurityMode securityMode) {
        this.schema = schema;
        this.revision = revision;
        this.announcement = announcement;
        this.update = update;
        this.securityMode = securityMode;
    }

    public int getSchema() {
        return schema;
    }

    public String getRevision() {
        return revision;
    }

    @Nullable
    public Announcement getAnnouncement() {
        return announcement;
    }

    @Nullable
    public Update getUpdate() {
        return update;
    }

    @Nullable
    public SecurityMode getSecurityMode() {
        return securityMode;
    }

    public static final class SecurityMode {
        private final String id;
        private final boolean enabled;
        private final String mode;
        private final String level;
        private final String title;
        private final String content;
        private final String publishedAt;
        private final String effectiveAt;
        private final String expiresAt;
        private final String downloadUrl;
        private final boolean allowLogExport;
        private final boolean allowRotationRecovery;
        private final long minimumVersionCode;
        private final long maximumVersionCode;

        public SecurityMode(String id, boolean enabled, String mode, String level,
                String title, String content, String publishedAt, String effectiveAt,
                String expiresAt, String downloadUrl, boolean allowLogExport,
                boolean allowRotationRecovery, long minimumVersionCode,
                long maximumVersionCode) {
            this.id = id;
            this.enabled = enabled;
            this.mode = mode;
            this.level = level;
            this.title = title;
            this.content = content;
            this.publishedAt = publishedAt;
            this.effectiveAt = effectiveAt;
            this.expiresAt = expiresAt;
            this.downloadUrl = downloadUrl;
            this.allowLogExport = allowLogExport;
            this.allowRotationRecovery = allowRotationRecovery;
            this.minimumVersionCode = minimumVersionCode;
            this.maximumVersionCode = maximumVersionCode;
        }

        public String getId() { return id; }
        public boolean isEnabled() { return enabled; }
        public String getMode() { return mode; }
        public String getLevel() { return level; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
        public String getPublishedAt() { return publishedAt; }
        public String getEffectiveAt() { return effectiveAt; }
        public String getExpiresAt() { return expiresAt; }
        public String getDownloadUrl() { return downloadUrl; }
        public boolean isLogExportAllowed() { return allowLogExport; }
        public boolean isRotationRecoveryAllowed() { return allowRotationRecovery; }
        public long getMinimumVersionCode() { return minimumVersionCode; }
        public long getMaximumVersionCode() { return maximumVersionCode; }
    }

    public static final class Announcement {
        private final String id;
        private final boolean enabled;
        private final String title;
        private final String content;
        private final String publishedAt;
        private final String displayPolicy;
        private final String type;
        private final String effectiveAt;
        private final String expiresAt;
        private final String highlightText;
        private final String highlightColor;
        private final String actionLabel;
        private final String actionUrl;
        private final boolean actionConfirm;

        public Announcement(
                String id,
                boolean enabled,
                String title,
                String content,
                String publishedAt,
                String displayPolicy,
                String type,
                String effectiveAt,
                String expiresAt,
                String highlightText,
                String highlightColor,
                String actionLabel,
                String actionUrl,
                boolean actionConfirm) {
            this.id = id;
            this.enabled = enabled;
            this.title = title;
            this.content = content;
            this.publishedAt = publishedAt;
            this.displayPolicy = displayPolicy;
            this.type = type;
            this.effectiveAt = effectiveAt;
            this.expiresAt = expiresAt;
            this.highlightText = highlightText;
            this.highlightColor = highlightColor;
            this.actionLabel = actionLabel;
            this.actionUrl = actionUrl;
            this.actionConfirm = actionConfirm;
        }

        public String getId() {
            return id;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public String getTitle() {
            return title;
        }

        public String getContent() {
            return content;
        }

        public String getPublishedAt() {
            return publishedAt;
        }

        public String getDisplayPolicy() {
            return displayPolicy;
        }

        public String getType() {
            return type;
        }

        public String getEffectiveAt() {
            return effectiveAt;
        }

        public String getExpiresAt() {
            return expiresAt;
        }

        public String getHighlightText() {
            return highlightText;
        }

        public String getHighlightColor() {
            return highlightColor;
        }

        public String getActionLabel() {
            return actionLabel;
        }

        public String getActionUrl() {
            return actionUrl;
        }

        public boolean isActionConfirm() {
            return actionConfirm;
        }
    }

    public static final class Update {
        private final boolean enabled;
        private final boolean paused;
        private final long latestVersionCode;
        private final long minimumSupportedVersionCode;
        private final long appliesToMinimumVersionCode;
        private final long appliesToMaximumVersionCode;
        private final String latestVersionName;
        private final String title;
        private final String content;
        private final String url;
        private final String mode;
        private final boolean force;
        private final String effectiveAt;
        private final String expiresAt;
        private final String releaseId;
        private final String publishedAt;
        private final long apkSizeBytes;
        private final String sha256;
        private final String downloadChannel;

        public Update(
                boolean enabled,
                boolean paused,
                long latestVersionCode,
                long minimumSupportedVersionCode,
                long appliesToMinimumVersionCode,
                long appliesToMaximumVersionCode,
                String latestVersionName,
                String title,
                String content,
                String url,
                String mode,
                boolean force,
                String effectiveAt,
                String expiresAt,
                String releaseId,
                String publishedAt,
                long apkSizeBytes,
                String sha256,
                String downloadChannel) {
            this.enabled = enabled;
            this.paused = paused;
            this.latestVersionCode = latestVersionCode;
            this.minimumSupportedVersionCode = minimumSupportedVersionCode;
            this.appliesToMinimumVersionCode = appliesToMinimumVersionCode;
            this.appliesToMaximumVersionCode = appliesToMaximumVersionCode;
            this.latestVersionName = latestVersionName;
            this.title = title;
            this.content = content;
            this.url = url;
            this.mode = mode;
            this.force = force;
            this.effectiveAt = effectiveAt;
            this.expiresAt = expiresAt;
            this.releaseId = releaseId;
            this.publishedAt = publishedAt;
            this.apkSizeBytes = apkSizeBytes;
            this.sha256 = sha256;
            this.downloadChannel = downloadChannel;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isPaused() {
            return paused;
        }

        public long getLatestVersionCode() {
            return latestVersionCode;
        }

        public long getMinimumSupportedVersionCode() {
            return minimumSupportedVersionCode;
        }

        public long getAppliesToMinimumVersionCode() {
            return appliesToMinimumVersionCode;
        }

        public long getAppliesToMaximumVersionCode() {
            return appliesToMaximumVersionCode;
        }

        public String getLatestVersionName() {
            return latestVersionName;
        }

        public String getTitle() {
            return title;
        }

        public String getContent() {
            return content;
        }

        public String getUrl() {
            return url;
        }

        public String getMode() {
            return mode;
        }

        public boolean isForce() {
            return force || "force".equalsIgnoreCase(mode);
        }

        public String getEffectiveAt() {
            return effectiveAt;
        }

        public String getExpiresAt() {
            return expiresAt;
        }

        public String getReleaseId() {
            return releaseId;
        }

        public String getPublishedAt() {
            return publishedAt;
        }

        public long getApkSizeBytes() {
            return apkSizeBytes;
        }

        public String getSha256() {
            return sha256;
        }

        public String getDownloadChannel() {
            return downloadChannel;
        }
    }
}
