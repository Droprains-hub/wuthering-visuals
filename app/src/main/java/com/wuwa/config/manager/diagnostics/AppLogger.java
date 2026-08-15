package com.wuwa.config.manager.diagnostics;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.annotation.RequiresApi;

import com.wuwa.config.manager.BuildConfig;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Process-wide diagnostic logger. Logs are mirrored to app-private storage first so a
 * permission or MediaStore failure never hides the exception that caused it. When shared
 * storage is available, the user-visible copy is written to Download/Wuwa CFBP/Log.
 */
public final class AppLogger {
    public static final String PUBLIC_DIRECTORY =
            "/storage/emulated/0/Download/Wuwa CFBP/Log/";
    private static final String RELATIVE_DIRECTORY = "Download/Wuwa CFBP/Log/";
    private static final String FILE_PREFIX = "WuwaCFBP_";
    private static final int MAX_RETAINED_LOGS = 10;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat LINE_TIME =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private static Context appContext;
    private static String sessionFileName;
    private static File internalFile;
    private static BufferedWriter internalWriter;
    private static BufferedWriter publicWriter;
    private static Uri publicUri;
    private static Thread.UncaughtExceptionHandler previousExceptionHandler;
    private static boolean crashHandlerInstalled;

    private AppLogger() {}

    public static void initialize(Context context) {
        synchronized (LOCK) {
            if (appContext != null) return;
            appContext = context.getApplicationContext();
            String timestamp = new SimpleDateFormat(
                    "yyyy-MM-dd_HH-mm-ss-SSS", Locale.ROOT).format(new Date());
            sessionFileName = FILE_PREFIX + timestamp + ".log";
            File privateDirectory = new File(appContext.getFilesDir(), "logs");
            if (!privateDirectory.exists()) privateDirectory.mkdirs();
            internalFile = new File(privateDirectory, sessionFileName);
            try {
                internalWriter = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(internalFile, true), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                internalWriter = null;
            }
            ensurePublicWriterLocked();
            installCrashHandlerLocked();
        }
        event("Application", "日志会话开始；版本=" + BuildConfig.VERSION_NAME +
                "(" + BuildConfig.VERSION_CODE + ")；设备=" + Build.MANUFACTURER + " " +
                Build.MODEL + "；Android=" + Build.VERSION.RELEASE +
                " API=" + Build.VERSION.SDK_INT + "；ABI=" + primaryAbi());
        pruneOldLogs(context.getApplicationContext());
    }

    public static void tryEnablePublicLogging() {
        synchronized (LOCK) {
            ensurePublicWriterLocked();
        }
    }

    public static void debug(String tag, String message) {
        if (!BuildConfig.ENABLE_DEBUG_LOGS) return;
        write("DEBUG", tag, message, null);
    }

    public static void info(String tag, String message) {
        if (!BuildConfig.ENABLE_DEBUG_LOGS) return;
        write("INFO", tag, message, null);
    }

    /** Records a concise user-relevant event in both debug and release builds. */
    public static void event(String tag, String message) {
        write("INFO", tag, message, null);
    }

    public static void warn(String tag, String message) {
        write("WARN", tag, message, null);
    }

    public static void error(String tag, String message, Throwable throwable) {
        write("ERROR", tag, message, throwable);
    }

    public static String getSessionFileName() {
        synchronized (LOCK) {
            return sessionFileName == null ? "" : sessionFileName;
        }
    }

    public static boolean isPublicLoggingAvailable() {
        synchronized (LOCK) {
            return publicUri != null;
        }
    }

    public static List<LogEntry> listLogs(Context context) {
        Context safeContext = context.getApplicationContext();
        flush();
        Map<String, LogEntry> merged = new LinkedHashMap<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (LogEntry entry : listMediaStoreLogs(safeContext)) {
                mergeLogEntry(merged, entry);
            }
        } else {
            File directory = new File(PUBLIC_DIRECTORY);
            File[] files = directory.listFiles(AppLogger::isLogFile);
            if (files != null) {
                for (File file : files) {
                    try {
                        Uri uri = FileProvider.getUriForFile(
                                safeContext, BuildConfig.APPLICATION_ID + ".files", file);
                        mergeLogEntry(merged, new LogEntry(
                                file.getName(), file.length(), file.lastModified(), uri,
                                null, null, file,
                                file.getName().equals(getSessionFileName())));
                    } catch (RuntimeException ignored) {}
                }
            }
        }

        // Always enumerate the private mirror. Some OEM MediaStore implementations create
        // the public file correctly but do not return it for an exact RELATIVE_PATH query.
        // The private copy is app-owned, survives that OEM inconsistency and remains safely
        // shareable through FileProvider.
        File privateDirectory = new File(safeContext.getFilesDir(), "logs");
        File[] privateFiles = privateDirectory.listFiles(AppLogger::isLogFile);
        if (privateFiles != null) {
            for (File file : privateFiles) {
                try {
                    Uri uri = FileProvider.getUriForFile(
                            safeContext, BuildConfig.APPLICATION_ID + ".files", file);
                    mergeLogEntry(merged, new LogEntry(
                            file.getName(), file.length(), file.lastModified(), uri,
                            null, file, null,
                            file.getName().equals(getSessionFileName())));
                } catch (RuntimeException ignored) {}
            }
        }

        // A failed directory listing must not hide the active session file.
        synchronized (LOCK) {
            if (internalFile != null && internalFile.isFile()
                    && !merged.containsKey(internalFile.getName())) {
                Uri uri = FileProvider.getUriForFile(
                        safeContext, BuildConfig.APPLICATION_ID + ".files", internalFile);
                mergeLogEntry(merged, new LogEntry(
                        internalFile.getName(), internalFile.length(),
                        internalFile.lastModified(), uri, null, internalFile,
                        null, true));
            }
        }

        List<LogEntry> entries = new ArrayList<>(merged.values());
        sortNewestFirst(entries);
        return entries;
    }

    public static boolean deleteLog(Context context, LogEntry entry) {
        if (entry == null || entry.currentSession) return false;
        boolean attempted = false;
        boolean deleted = false;
        if (entry.mediaStoreUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            attempted = true;
            try {
                deleted |= context.getContentResolver()
                        .delete(entry.mediaStoreUri, null, null) > 0;
            } catch (RuntimeException ignored) {}
        }
        if (entry.privateFile != null) {
            attempted = true;
            try {
                deleted |= !entry.privateFile.exists() || entry.privateFile.delete();
            } catch (RuntimeException ignored) {}
        }
        if (entry.legacyPublicFile != null) {
            attempted = true;
            try {
                deleted |= !entry.legacyPublicFile.exists() || entry.legacyPublicFile.delete();
            } catch (RuntimeException ignored) {}
        }
        return attempted && deleted;
    }

    private static void pruneOldLogs(Context context) {
        List<LogEntry> entries = listLogs(context);
        int remaining = entries.size();
        for (int index = entries.size() - 1;
                index >= 0 && remaining > MAX_RETAINED_LOGS; index--) {
            LogEntry entry = entries.get(index);
            if (entry.currentSession) continue;
            if (deleteLog(context, entry)) remaining--;
        }
    }

    public static Uri getCurrentShareUri(Context context) {
        flush();
        synchronized (LOCK) {
            if (publicUri != null) return publicUri;
            if (internalFile == null || !internalFile.isFile()) return null;
            return FileProvider.getUriForFile(
                    context, BuildConfig.APPLICATION_ID + ".files", internalFile);
        }
    }

    public static void flush() {
        synchronized (LOCK) {
            flushQuietly(internalWriter);
            flushQuietly(publicWriter);
        }
    }

    private static void write(String level, String tag, String message, Throwable throwable) {
        synchronized (LOCK) {
            if (appContext == null) return;
            String safeTag = tag == null ? "App" : tag.replace('\n', ' ');
            String safeMessage = message == null ? "" : message;
            String line = LINE_TIME.format(new Date()) + " [" + level + "] [" +
                    Thread.currentThread().getName() + "] " + safeTag + " - " + safeMessage;
            writeLineLocked(line);
            if (throwable != null) {
                StringWriter buffer = new StringWriter();
                throwable.printStackTrace(new PrintWriter(buffer));
                for (String stackLine : buffer.toString().split("\\r?\\n")) {
                    writeLineLocked("    " + stackLine);
                }
            }
            flushQuietly(internalWriter);
            flushQuietly(publicWriter);
        }
    }

    private static void writeLineLocked(String line) {
        try {
            if (internalWriter != null) {
                internalWriter.write(line);
                internalWriter.newLine();
            }
        } catch (IOException ignored) {}
        try {
            if (publicWriter != null) {
                publicWriter.write(line);
                publicWriter.newLine();
            }
        } catch (IOException ignored) {}
    }

    private static void ensurePublicWriterLocked() {
        if (appContext == null || publicWriter != null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                        appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) return;
        try {
            OutputStream output;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, sessionFileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIRECTORY);
                publicUri = appContext.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (publicUri == null) return;
                output = appContext.getContentResolver().openOutputStream(publicUri, "w");
            } else {
                File directory = new File(PUBLIC_DIRECTORY);
                if (!directory.exists() && !directory.mkdirs()) return;
                File outputFile = new File(directory, sessionFileName);
                publicUri = FileProvider.getUriForFile(
                        appContext, BuildConfig.APPLICATION_ID + ".files", outputFile);
                output = new FileOutputStream(outputFile, false);
            }
            if (output == null) return;
            copyInternalLogLocked(output);
            publicWriter = new BufferedWriter(
                    new OutputStreamWriter(output, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            publicUri = null;
            publicWriter = null;
        }
    }

    private static void copyInternalLogLocked(OutputStream output) throws IOException {
        flushQuietly(internalWriter);
        if (internalFile == null || !internalFile.isFile() || internalFile.length() == 0L) return;
        try (BufferedInputStream input = new BufferedInputStream(
                new FileInputStream(internalFile))) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            output.flush();
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static List<LogEntry> listMediaStoreLogs(Context context) {
        List<LogEntry> entries = new ArrayList<>();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.RELATIVE_PATH
        };
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] selectionArgs = {FILE_PREFIX + "%.log"};
        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, selection, selectionArgs,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) return entries;
            int idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameIndex);
                if (!isExpectedRelativePath(cursor.getString(pathIndex))) continue;
                Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex));
                entries.add(new LogEntry(
                        name, cursor.getLong(sizeIndex), cursor.getLong(dateIndex) * 1000L,
                        uri, uri, null, null,
                        name.equals(getSessionFileName())));
            }
        } catch (RuntimeException ignored) {}
        return entries;
    }

    private static boolean isLogFile(File file) {
        return file != null && file.isFile()
                && file.getName().startsWith(FILE_PREFIX)
                && file.getName().endsWith(".log");
    }

    private static boolean isExpectedRelativePath(String rawPath) {
        if (rawPath == null) return false;
        String normalized = rawPath.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized.equalsIgnoreCase(RELATIVE_DIRECTORY)
                || normalized.toLowerCase(Locale.ROOT)
                .endsWith(RELATIVE_DIRECTORY.toLowerCase(Locale.ROOT));
    }

    private static void mergeLogEntry(Map<String, LogEntry> entries, LogEntry incoming) {
        LogEntry existing = entries.get(incoming.displayName);
        if (existing == null) {
            entries.put(incoming.displayName, incoming);
            return;
        }
        File privateFile = incoming.privateFile != null
                ? incoming.privateFile : existing.privateFile;
        File legacyPublicFile = incoming.legacyPublicFile != null
                ? incoming.legacyPublicFile : existing.legacyPublicFile;
        Uri mediaStoreUri = incoming.mediaStoreUri != null
                ? incoming.mediaStoreUri : existing.mediaStoreUri;
        Uri shareUri = privateFile != null
                ? (incoming.privateFile != null ? incoming.contentUri : existing.contentUri)
                : (mediaStoreUri != null ? mediaStoreUri : incoming.contentUri);
        entries.put(incoming.displayName, new LogEntry(
                incoming.displayName,
                Math.max(existing.sizeBytes, incoming.sizeBytes),
                Math.max(existing.modifiedTimeMillis, incoming.modifiedTimeMillis),
                shareUri,
                mediaStoreUri,
                privateFile,
                legacyPublicFile,
                existing.currentSession || incoming.currentSession));
    }

    private static void sortNewestFirst(List<LogEntry> entries) {
        entries.sort(Comparator.comparingLong(
                (LogEntry entry) -> entry.modifiedTimeMillis).reversed());
    }

    private static void installCrashHandlerLocked() {
        if (crashHandlerInstalled) return;
        previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            error("Crash", "未捕获异常，线程=" + thread.getName(), throwable);
            flush();
            if (previousExceptionHandler != null) {
                previousExceptionHandler.uncaughtException(thread, throwable);
            }
        });
        crashHandlerInstalled = true;
    }

    private static void flushQuietly(BufferedWriter writer) {
        if (writer == null) return;
        try {
            writer.flush();
        } catch (IOException ignored) {}
    }

    private static String primaryAbi() {
        return Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
    }
}
