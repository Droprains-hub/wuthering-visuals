package com.wuwa.config.manager.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;

public final class RemoteNoticeEvaluatorTest {
    private static final long NOW = Instant.parse("2026-08-15T04:00:00Z").toEpochMilli();

    @Test
    public void emptyWindowIsActive() {
        assertTrue(RemoteNoticeEvaluator.isActiveWindow("", "", NOW));
    }

    @Test
    public void futureAndExpiredWindowsAreInactive() {
        assertFalse(RemoteNoticeEvaluator.isActiveWindow(
                "2026-08-15T05:00:00Z", "", NOW));
        assertFalse(RemoteNoticeEvaluator.isActiveWindow(
                "", "2026-08-15T03:59:59Z", NOW));
    }

    @Test
    public void offsetWindowIsSupportedAndMalformedInputFailsClosed() {
        assertTrue(RemoteNoticeEvaluator.isActiveWindow(
                "2026-08-15T11:00:00+08:00", "2026-08-15T13:00:00+08:00", NOW));
        assertFalse(RemoteNoticeEvaluator.isActiveWindow("not-a-time", "", NOW));
    }

    @Test
    public void updateStopsApplyingWhenTargetVersionIsInstalled() {
        assertTrue(RemoteNoticeEvaluator.isUpdateApplicable(35, 36, 0, 0));
        assertFalse(RemoteNoticeEvaluator.isUpdateApplicable(36, 36, 0, 0));
        assertFalse(RemoteNoticeEvaluator.isUpdateApplicable(37, 36, 0, 0));
    }

    @Test
    public void updateCanTargetAnExactInstalledVersion() {
        assertFalse(RemoteNoticeEvaluator.isUpdateApplicable(34, 36, 35, 35));
        assertTrue(RemoteNoticeEvaluator.isUpdateApplicable(35, 36, 35, 35));
    }

    @Test
    public void updateCanTargetAnInstalledVersionRange() {
        assertFalse(RemoteNoticeEvaluator.isUpdateApplicable(30, 40, 31, 35));
        assertTrue(RemoteNoticeEvaluator.isUpdateApplicable(31, 40, 31, 35));
        assertTrue(RemoteNoticeEvaluator.isUpdateApplicable(35, 40, 31, 35));
        assertFalse(RemoteNoticeEvaluator.isUpdateApplicable(36, 40, 31, 35));
    }

    @Test
    public void malformedUpdateAudienceFailsClosed() {
        assertFalse(RemoteNoticeEvaluator.isUpdateApplicable(35, 0, 0, 0));
        assertFalse(RemoteNoticeEvaluator.isUpdateApplicable(35, 40, -1, 0));
        assertFalse(RemoteNoticeEvaluator.isUpdateApplicable(35, 40, 36, 31));
    }

    @Test
    public void newReleaseAudienceMustBeExplicit() {
        assertFalse(RemoteNoticeEvaluator.hasExplicitUpdateAudience(0, 0));
        assertFalse(RemoteNoticeEvaluator.hasExplicitUpdateAudience(35, 0));
        assertFalse(RemoteNoticeEvaluator.hasExplicitUpdateAudience(36, 35));
        assertTrue(RemoteNoticeEvaluator.hasExplicitUpdateAudience(35, 35));
        assertTrue(RemoteNoticeEvaluator.hasExplicitUpdateAudience(31, 35));
    }

    @Test
    public void staleCacheCannotEnforceRemoteLock() {
        long sixHours = 6L * 60L * 60L * 1000L;
        assertTrue(RemoteNoticeEvaluator.isCacheFreshEnough(
                NOW - sixHours, NOW, sixHours));
        assertFalse(RemoteNoticeEvaluator.isCacheFreshEnough(
                NOW - sixHours - 1L, NOW, sixHours));
    }
}
