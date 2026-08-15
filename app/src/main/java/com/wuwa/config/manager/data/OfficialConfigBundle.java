package com.wuwa.config.manager.data;

import android.content.Context;
import android.util.Base64;
import android.util.Base64InputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts and validates the bundled, read-only game-original configuration set. */
final class OfficialConfigBundle {
    private static final String ASSET_NAME = "official_config.zip.b64";
    private static final String DIRECTORY_NAME = "official_config";
    private static final int MAX_ENTRIES = 64;
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 32L * 1024L * 1024L;

    private final Context context;
    private final File directory;

    OfficialConfigBundle(Context context) {
        this.context = context.getApplicationContext();
        this.directory = new File(this.context.getFilesDir(), DIRECTORY_NAME);
    }

    boolean prepare() {
        File temporary = new File(
                directory.getParentFile(),
                DIRECTORY_NAME + ".tmp_" + UUID.randomUUID().toString().replace("-", ""));
        deleteTree(temporary);
        if (!temporary.mkdirs()) return false;

        int entryCount = 0;
        long totalBytes = 0L;
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[8192];
        try (InputStream asset = context.getAssets().open(ASSET_NAME);
             Base64InputStream decoded = new Base64InputStream(asset, Base64.DEFAULT);
             ZipInputStream zip = new ZipInputStream(decoded)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String name = entry.getName();
                if (!isSafeIniName(name) || !names.add(name) || ++entryCount > MAX_ENTRIES) {
                    throw new IOException("Invalid bundled config entry: " + name);
                }

                File outputFile = new File(temporary, name);
                long fileBytes = 0L;
                try (FileOutputStream output = new FileOutputStream(outputFile, false)) {
                    int count;
                    while ((count = zip.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        fileBytes += count;
                        totalBytes += count;
                        if (fileBytes > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw new IOException("Bundled config archive is too large");
                        }
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                }
                zip.closeEntry();
            }
        } catch (IOException error) {
            deleteTree(temporary);
            return false;
        }

        if (!containsRequiredFiles(temporary) || entryCount == 0) {
            deleteTree(temporary);
            return false;
        }
        deleteTree(directory);
        if (!temporary.renameTo(directory)) {
            deleteTree(temporary);
            return false;
        }
        return isReady();
    }

    boolean isReady() {
        if (!containsRequiredFiles(directory)) return false;
        File[] files = directory.listFiles();
        if (files == null || files.length == 0 || files.length > MAX_ENTRIES) return false;
        for (File file : files) {
            if (!file.isFile() || !isSafeIniName(file.getName()) || file.length() > MAX_FILE_BYTES) {
                return false;
            }
        }
        return true;
    }

    List<File> files() {
        if (!isReady()) return Collections.emptyList();
        File[] children = directory.listFiles();
        if (children == null) return Collections.emptyList();
        List<File> result = new ArrayList<>();
        for (File child : children) {
            if (child.isFile() && isSafeIniName(child.getName())) result.add(child);
        }
        Collections.sort(result, (left, right) -> left.getName().compareTo(right.getName()));
        return result;
    }

    private static boolean containsRequiredFiles(File target) {
        return new File(target, ConfigRepository.ENGINE_FILE).isFile()
                && new File(target, ConfigRepository.DEVICE_PROFILE_FILE).isFile()
                && new File(target, "DeviceProfile.ini").isFile();
    }

    private static boolean isSafeIniName(String name) {
        return name != null
                && name.matches("[A-Za-z0-9._-]+\\.ini")
                && !name.contains("..")
                && !name.contains("/")
                && !name.contains("\\\\");
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        file.delete();
    }
}
