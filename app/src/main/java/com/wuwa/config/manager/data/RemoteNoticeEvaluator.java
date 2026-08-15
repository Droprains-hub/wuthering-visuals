package com.wuwa.config.manager.data;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Pure time-window validation shared by remote announcements and updates. */
public final class RemoteNoticeEvaluator {
    private RemoteNoticeEvaluator() {}

    public static boolean isActiveWindow(
            String effectiveAt, String expiresAt, long nowEpochMillis) {
        Long effective = parseTimestamp(effectiveAt);
        Long expires = parseTimestamp(expiresAt);
        if (!isBlank(effectiveAt) && effective == null) return false;
        if (!isBlank(expiresAt) && expires == null) return false;
        if (effective != null && nowEpochMillis < effective) return false;
        return expires == null || nowEpochMillis < expires;
    }

    /**
     * Returns whether an installed build belongs to the audience for an update.
     * A client at or above the destination version is never targeted again.
     * Zero range boundaries mean unbounded for backwards-compatible manifests.
     */
    public static boolean isUpdateApplicable(
            long installedVersionCode,
            long targetVersionCode,
            long appliesToMinimumVersionCode,
            long appliesToMaximumVersionCode) {
        if (installedVersionCode < 0
                || targetVersionCode <= 0
                || appliesToMinimumVersionCode < 0
                || appliesToMaximumVersionCode < 0
                || (appliesToMinimumVersionCode > 0
                    && appliesToMaximumVersionCode > 0
                    && appliesToMinimumVersionCode > appliesToMaximumVersionCode)) {
            return false;
        }
        if (targetVersionCode <= installedVersionCode) return false;
        if (appliesToMinimumVersionCode > 0
                && installedVersionCode < appliesToMinimumVersionCode) {
            return false;
        }
        return appliesToMaximumVersionCode <= 0
                || installedVersionCode <= appliesToMaximumVersionCode;
    }

    /** New releases must identify at least one old build instead of targeting everyone. */
    public static boolean hasExplicitUpdateAudience(
            long appliesToMinimumVersionCode, long appliesToMaximumVersionCode) {
        return appliesToMinimumVersionCode > 0L
                && appliesToMaximumVersionCode > 0L
                && appliesToMinimumVersionCode <= appliesToMaximumVersionCode;
    }

    /** Prevents an old cached command from indefinitely locking a client offline. */
    public static boolean isCacheFreshEnough(
            long validatedAtMillis, long nowEpochMillis, long maximumAgeMillis) {
        if (validatedAtMillis <= 0L || maximumAgeMillis < 0L) return false;
        long age = nowEpochMillis - validatedAtMillis;
        return age >= 0L && age <= maximumAgeMillis;
    }

    private static Long parseTimestamp(String value) {
        if (isBlank(value)) return null;
        try {
            return Instant.parse(value.trim()).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli();
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
