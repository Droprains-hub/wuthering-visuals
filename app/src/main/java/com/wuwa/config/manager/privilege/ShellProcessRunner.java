package com.wuwa.config.manager.privilege;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ShellProcessRunner {
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    private ShellProcessRunner() {}

    static ShellResult run(Process process, byte[] stdin, int timeoutSeconds) throws Exception {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds <= 0");

        ExecutorService readers = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "wuwa-shell-output");
            thread.setDaemon(true);
            return thread;
        });
        Future<Captured> stdoutFuture = readers.submit(
                (Callable<Captured>) () -> capture(process.getInputStream()));
        Future<Captured> stderrFuture = readers.submit(
                (Callable<Captured>) () -> capture(process.getErrorStream()));

        try {
            try {
                if (stdin != null && stdin.length > 0) {
                    process.getOutputStream().write(stdin);
                    process.getOutputStream().flush();
                }
            } finally {
                process.getOutputStream().close();
            }

            if (!waitForExit(process, timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy();
                waitForExit(process, 2, TimeUnit.SECONDS);
                throw new TimeoutException("Shell 命令执行超时（" + timeoutSeconds + " 秒）");
            }

            Captured stdout = stdoutFuture.get(3, TimeUnit.SECONDS);
            Captured stderr = stderrFuture.get(3, TimeUnit.SECONDS);
            if (stdout.truncated || stderr.truncated) {
                throw new IOException("Shell 命令输出超过安全限制");
            }
            return new ShellResult(process.exitValue(), stdout.text, stderr.text);
        } finally {
            if (!hasExited(process)) process.destroy();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            readers.shutdownNow();
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
        }
    }

    /** Process timed waiting APIs are unavailable on Android 7.x (API 24-25). */
    private static boolean waitForExit(Process process, long duration, TimeUnit unit)
            throws InterruptedException {
        long timeoutNanos = unit.toNanos(duration);
        long deadline = System.nanoTime() + timeoutNanos;
        while (!hasExited(process)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) return false;
            long sleepMillis = Math.min(50L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            Thread.sleep(sleepMillis);
        }
        return true;
    }

    private static boolean hasExited(Process process) {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException ignored) {
            return false;
        }
    }

    private static Captured capture(InputStream input) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream(8192)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            boolean truncated = false;
            while (true) {
                int count = stream.read(buffer);
                if (count < 0) break;
                if (count == 0) continue;
                int remaining = Math.max(0, MAX_OUTPUT_BYTES - output.size());
                if (remaining > 0) output.write(buffer, 0, Math.min(count, remaining));
                total += count;
                if (total > MAX_OUTPUT_BYTES) truncated = true;
            }
            return new Captured(
                    new String(output.toByteArray(), StandardCharsets.UTF_8), truncated);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
    }

    private static final class Captured {
        final String text;
        final boolean truncated;

        Captured(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
