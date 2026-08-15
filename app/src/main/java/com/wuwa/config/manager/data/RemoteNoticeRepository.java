package com.wuwa.config.manager.data;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small HTTPS client for the announcement/update manifest. */
public final class RemoteNoticeRepository {
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    private static final String CACHE_FILE_NAME = "remote_configuration.json";
    private static final String CACHE_META_FILE_NAME = "remote_configuration.meta.json";
    private static final String LEGACY_CACHE_FILE_NAME = "verified_remote_configuration.json";

    public enum Source {
        NETWORK,
        REVALIDATED_CACHE,
        STALE_CACHE
    }

    public static final class FetchResult {
        private final RemoteNoticePayload payload;
        private final Source source;
        private final long validatedAtMillis;
        @Nullable private final Exception networkFailure;

        FetchResult(RemoteNoticePayload payload, Source source, long validatedAtMillis,
                @Nullable Exception networkFailure) {
            this.payload = payload;
            this.source = source;
            this.validatedAtMillis = validatedAtMillis;
            this.networkFailure = networkFailure;
        }

        public RemoteNoticePayload getPayload() {
            return payload;
        }

        public Source getSource() {
            return source;
        }

        public long getValidatedAtMillis() {
            return validatedAtMillis;
        }

        @Nullable
        public Exception getNetworkFailure() {
            return networkFailure;
        }

        public boolean isNetworkConfirmed() {
            return source == Source.NETWORK || source == Source.REVALIDATED_CACHE;
        }

        public long getAgeMillis(long nowMillis) {
            if (validatedAtMillis <= 0L) return Long.MAX_VALUE;
            return Math.max(0L, nowMillis - validatedAtMillis);
        }
    }

    private static final class CacheMetadata {
        long validatedAtMillis;
        String etag = "";
        String lastModified = "";
    }

    private static final class NetworkResponse {
        final boolean notModified;
        final String body;
        final String etag;
        final String lastModified;

        NetworkResponse(boolean notModified, String body, String etag, String lastModified) {
            this.notModified = notModified;
            this.body = body;
            this.etag = etag == null ? "" : etag;
            this.lastModified = lastModified == null ? "" : lastModified;
        }
    }

    private final String endpoint;
    private final String fallbackEndpoint;
    private final File cacheFile;
    private final File metadataFile;
    private final File legacyCacheFile;

    public RemoteNoticeRepository(Context context, String endpoint) {
        this(context, endpoint, "");
    }

    public RemoteNoticeRepository(Context context, String endpoint, String fallbackEndpoint) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.fallbackEndpoint = fallbackEndpoint == null ? "" : fallbackEndpoint.trim();
        this.cacheFile = new File(context.getFilesDir(), CACHE_FILE_NAME);
        this.metadataFile = new File(context.getFilesDir(), CACHE_META_FILE_NAME);
        this.legacyCacheFile = new File(context.getFilesDir(), LEGACY_CACHE_FILE_NAME);
    }

    public boolean isConfigured() {
        return !TextUtils.isEmpty(endpoint);
    }

    /**
     * Always attempts a conditional network request first. When the network is unavailable,
     * the last valid manifest is returned as a clearly marked stale cache result.
     */
    public FetchResult fetch() throws Exception {
        CacheMetadata metadata = readMetadata();
        Exception networkFailure;
        try {
            NetworkResponse response = fetchManifest(metadata);
            long now = System.currentTimeMillis();
            if (response.notModified) {
                RemoteNoticePayload payload = readCachedPayload();
                metadata.validatedAtMillis = now;
                if (!TextUtils.isEmpty(response.etag)) metadata.etag = response.etag;
                if (!TextUtils.isEmpty(response.lastModified)) {
                    metadata.lastModified = response.lastModified;
                }
                saveMetadata(metadata);
                return new FetchResult(payload, Source.REVALIDATED_CACHE, now, null);
            }

            RemoteNoticePayload payload = parse(response.body);
            saveManifest(response.body);
            metadata.validatedAtMillis = now;
            metadata.etag = response.etag;
            metadata.lastModified = response.lastModified;
            saveMetadata(metadata);
            return new FetchResult(payload, Source.NETWORK, now, null);
        } catch (Exception error) {
            networkFailure = error;
        }

        try {
            RemoteNoticePayload payload = readCachedPayload();
            long validatedAt = metadata.validatedAtMillis;
            if (validatedAt <= 0L) {
                File readableCache = cacheFile.isFile() ? cacheFile : legacyCacheFile;
                validatedAt = readableCache.lastModified();
            }
            return new FetchResult(payload, Source.STALE_CACHE, validatedAt, networkFailure);
        } catch (Exception cacheFailure) {
            networkFailure.addSuppressed(cacheFailure);
            throw networkFailure;
        }
    }

    private RemoteNoticePayload readCachedPayload() throws Exception {
        File readableCache = cacheFile.isFile() ? cacheFile : legacyCacheFile;
        if (!readableCache.isFile()) throw new IOException("No cached remote configuration");
        try (InputStream input = new FileInputStream(readableCache)) {
            return parse(readLimited(input));
        }
    }

    private NetworkResponse fetchManifest(CacheMetadata metadata) throws IOException {
        if (!isConfigured()) throw new IOException("Remote notice endpoint is not configured");
        try {
            return fetchManifestFromEndpoint(endpoint, metadata);
        } catch (IOException primaryFailure) {
            if (TextUtils.isEmpty(fallbackEndpoint) || endpoint.equals(fallbackEndpoint)) {
                throw primaryFailure;
            }
            try {
                return fetchManifestFromEndpoint(fallbackEndpoint, metadata);
            } catch (IOException fallbackFailure) {
                primaryFailure.addSuppressed(fallbackFailure);
                throw primaryFailure;
            }
        }
    }

    private NetworkResponse fetchManifestFromEndpoint(
            String sourceEndpoint, CacheMetadata metadata) throws IOException {
        URL url = new URL(sourceEndpoint);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Remote notice endpoint must use HTTPS");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setUseCaches(true);
        connection.setDefaultUseCaches(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        if (!TextUtils.isEmpty(metadata.etag)) {
            connection.setRequestProperty("If-None-Match", metadata.etag);
        }
        if (!TextUtils.isEmpty(metadata.lastModified)) {
            connection.setRequestProperty("If-Modified-Since", metadata.lastModified);
        }
        try {
            int status = connection.getResponseCode();
            String etag = connection.getHeaderField("ETag");
            String lastModified = connection.getHeaderField("Last-Modified");
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return new NetworkResponse(true, "", etag, lastModified);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Announcement service returned HTTP " + status);
            }
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw new IOException("Announcement response is too large");
            }
            try (InputStream input = connection.getInputStream()) {
                return new NetworkResponse(false, readLimited(input), etag, lastModified);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void saveManifest(String manifest) throws IOException {
        writeAtomic(cacheFile, manifest);
    }

    private CacheMetadata readMetadata() {
        CacheMetadata metadata = new CacheMetadata();
        if (!metadataFile.isFile()) return metadata;
        try (InputStream input = new FileInputStream(metadataFile)) {
            JSONObject json = new JSONObject(readLimited(input));
            metadata.validatedAtMillis = json.optLong("validated_at_millis", 0L);
            metadata.etag = json.optString("etag", "");
            metadata.lastModified = json.optString("last_modified", "");
        } catch (Exception ignored) {
            // A corrupt metadata sidecar must never discard an otherwise valid manifest cache.
        }
        return metadata;
    }

    private void saveMetadata(CacheMetadata metadata) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("validated_at_millis", metadata.validatedAtMillis);
            json.put("etag", metadata.etag);
            json.put("last_modified", metadata.lastModified);
        } catch (JSONException impossible) {
            throw new IOException("Unable to serialize remote cache metadata", impossible);
        }
        writeAtomic(metadataFile, json.toString());
    }

    private static void writeAtomic(File destination, String content) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Unable to replace remote configuration cache");
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("Unable to commit remote configuration cache");
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException("Announcement response is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static RemoteNoticePayload parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int schema = root.optInt("schema", 1);
        if (schema != 1) throw new JSONException("Unsupported announcement schema: " + schema);

        RemoteNoticePayload.Announcement announcement = null;
        JSONObject announcementJson = root.optJSONObject("announcement");
        if (announcementJson != null) {
            boolean legacyShowOnce = announcementJson.optBoolean("show_once", true);
            JSONObject highlightJson = announcementJson.optJSONObject("highlight");
            JSONObject actionJson = announcementJson.optJSONObject("action");
            announcement = new RemoteNoticePayload.Announcement(
                    announcementJson.optString("id", "").trim(),
                    announcementJson.optBoolean("enabled", false),
                    announcementJson.optString("title", "").trim(),
                    announcementJson.optString("content", "").trim(),
                    announcementJson.optString("published_at", "").trim(),
                    announcementJson.optString("display_policy",
                            legacyShowOnce ? "once" : "every_start").trim(),
                    announcementJson.optString("type", "normal").trim(),
                    announcementJson.optString("effective_at", "").trim(),
                    announcementJson.optString("expires_at", "").trim(),
                    highlightJson == null ? "" : highlightJson.optString("text", "").trim(),
                    highlightJson == null ? "primary"
                            : highlightJson.optString("color", "primary").trim(),
                    actionJson == null ? "" : actionJson.optString("label", "").trim(),
                    actionJson == null ? "" : actionJson.optString("url", "").trim(),
                    actionJson != null && actionJson.optBoolean("confirm", true));
        }

        RemoteNoticePayload.Update update = null;
        JSONObject updateJson = root.optJSONObject("update");
        if (updateJson != null) {
            boolean legacyForce = updateJson.optBoolean("force", false);
            update = new RemoteNoticePayload.Update(
                    updateJson.optBoolean("enabled", true),
                    updateJson.optBoolean("paused", false),
                    updateJson.optLong("latest_version_code", 0L),
                    updateJson.optLong("min_supported_version_code", 0L),
                    updateJson.optLong("applies_to_min_version_code", 0L),
                    updateJson.optLong("applies_to_max_version_code", 0L),
                    updateJson.optString("latest_version_name", "").trim(),
                    updateJson.optString("title", "").trim(),
                    updateJson.optString("content", "").trim(),
                    updateJson.optString("url", "").trim(),
                    updateJson.optString("mode", legacyForce ? "force" : "optional").trim(),
                    legacyForce,
                    updateJson.optString("effective_at", "").trim(),
                    updateJson.optString("expires_at", "").trim(),
                    updateJson.optString("release_id", "").trim(),
                    updateJson.optString("published_at", "").trim(),
                    updateJson.optLong("apk_size_bytes", 0L),
                    updateJson.optString("sha256", "").trim(),
                    updateJson.optString("download_channel", "UC网盘").trim());
        }

        RemoteNoticePayload.SecurityMode securityMode = null;
        JSONObject securityJson = root.optJSONObject("security");
        if (securityJson != null) {
            securityMode = new RemoteNoticePayload.SecurityMode(
                    securityJson.optString("id", "").trim(),
                    securityJson.optBoolean("enabled", false),
                    securityJson.optString("mode", "maintenance").trim(),
                    securityJson.optString("level", "important").trim(),
                    securityJson.optString("title", "").trim(),
                    securityJson.optString("content", "").trim(),
                    securityJson.optString("published_at", "").trim(),
                    securityJson.optString("effective_at", "").trim(),
                    securityJson.optString("expires_at", "").trim(),
                    securityJson.optString("download_url", "").trim(),
                    securityJson.optBoolean("allow_log_export", true),
                    securityJson.optBoolean("allow_rotation_recovery", true),
                    securityJson.optLong("min_version_code", 0L),
                    securityJson.optLong("max_version_code", 0L));
        }
        return new RemoteNoticePayload(schema,
                root.optString("revision", "").trim(), announcement, update, securityMode);
    }
}
