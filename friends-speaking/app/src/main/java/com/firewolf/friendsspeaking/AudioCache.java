package com.firewolf.friendsspeaking;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.BooleanSupplier;

/** Verifies published audio and keeps exact seeking independent of network buffering. */
final class AudioCache {
    private static final long MAX_BYTES = 256L * 1024L * 1024L;

    static final class Result {
        final File file;
        final String sha256;
        Result(File file, String sha256) { this.file = file; this.sha256 = sha256; }
    }

    static String fingerprint(InputStream input, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        copy(input, null, digest, cancelled);
        return AlignmentIndex.hex(digest.digest());
    }

    static Result prepare(File directory, String key, String url, BooleanSupplier cancelled) throws Exception {
        return prepare(directory, key, url, cancelled, null);
    }

    static Result prepare(File directory, String key, String url, BooleanSupplier cancelled, String expectedSha256) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Audio cache unavailable");
        // A killed process cannot run its finally block. The service uses one
        // serial loader, so .part files at the next load are abandoned downloads.
        File[] abandoned = directory.listFiles(file -> file.getName().endsWith(".part"));
        if (abandoned != null) for (File file : abandoned) Files.deleteIfExists(file.toPath());
        File target = new File(directory, key + ".wma");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (target.isFile() && target.length() > 0) {
            try (InputStream input = new FileInputStream(target)) { copy(input, null, digest, cancelled); }
            String cachedHash = AlignmentIndex.hex(digest.digest());
            if (expectedSha256 == null || expectedSha256.equals(cachedHash)) {
                target.setLastModified(System.currentTimeMillis());
                trim(directory, target);
                return new Result(target, cachedHash);
            }
            // A new release may pair with a repaired source file. Keep the old
            // cache until the replacement has downloaded successfully.
            digest.reset();
        }
        {
            File temporary = File.createTempFile(key + "-", ".part", directory);
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "FilmAudio/" + BuildConfig.VERSION_NAME);
            try {
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("Audio request failed");
                long expected = connection.getContentLengthLong();
                long actual;
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(temporary.toPath())) {
                    actual = copy(input, output, digest, cancelled);
                }
                if (actual == 0 || (expected >= 0 && actual != expected)) throw new IOException("Incomplete audio");
                String downloadedHash = AlignmentIndex.hex(digest.digest());
                if (expectedSha256 != null && !expectedSha256.equals(downloadedHash)) {
                    throw new IOException("Audio checksum mismatch");
                }
                if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                target.setLastModified(System.currentTimeMillis());
                trim(directory, target);
                return new Result(target, downloadedHash);
            } finally {
                connection.disconnect();
                Files.deleteIfExists(temporary.toPath());
            }
        }
    }

    private static long copy(InputStream input, OutputStream output, MessageDigest digest,
                             BooleanSupplier cancelled) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            if (output != null) output.write(buffer, 0, count);
            digest.update(buffer, 0, count);
            total += count;
            if (output != null && total > MAX_BYTES) throw new IOException("Audio exceeds cache limit");
        }
        return total;
    }

    private static void trim(File directory, File current) {
        File[] files = directory.listFiles(file -> file.getName().endsWith(".wma"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= MAX_BYTES) break;
            if (file.equals(current)) continue;
            long size = file.length();
            if (file.delete()) total -= size;
        }
    }
}
