package com.wuwa.config.manager.diagnostics;

import android.net.Uri;

import java.io.File;

public final class LogEntry {
    public final String displayName;
    public final long sizeBytes;
    public final long modifiedTimeMillis;
    public final Uri contentUri;
    final Uri mediaStoreUri;
    final File privateFile;
    final File legacyPublicFile;
    public final boolean currentSession;

    LogEntry(
            String displayName, long sizeBytes, long modifiedTimeMillis,
            Uri contentUri, Uri mediaStoreUri, File privateFile,
            File legacyPublicFile, boolean currentSession) {
        this.displayName = displayName;
        this.sizeBytes = Math.max(0L, sizeBytes);
        this.modifiedTimeMillis = Math.max(0L, modifiedTimeMillis);
        this.contentUri = contentUri;
        this.mediaStoreUri = mediaStoreUri;
        this.privateFile = privateFile;
        this.legacyPublicFile = legacyPublicFile;
        this.currentSession = currentSession;
    }
}
