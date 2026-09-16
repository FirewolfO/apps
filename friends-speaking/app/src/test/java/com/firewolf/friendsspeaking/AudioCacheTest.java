package com.firewolf.friendsspeaking;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class AudioCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cachesCompleteOriginWithoutRangeSupportAndFingerprintsActualBytes() throws Exception {
        byte[] audio = "audio bytes for a seekable cached file".getBytes(StandardCharsets.UTF_8);
        try (Origin server = new Origin(audio)) {
            File cache = temporary.newFolder("audio");
            Files.write(new File(cache, "S01E01-abandoned.part").toPath(), new byte[]{1});
            String url = server.url();
            AudioCache.Result result = AudioCache.prepare(cache, "S01E01", url, () -> false);
            assertArrayEquals(audio, Files.readAllBytes(result.file.toPath()));
            assertEquals(AlignmentIndex.hex(MessageDigest.getInstance("SHA-256").digest(audio)), result.sha256);
            AudioCache.Result again = AudioCache.prepare(cache, "S01E01", url, () -> false);
            assertEquals(result.sha256, again.sha256);
            assertEquals(1, server.requests.get());
            assertEquals(1, cache.listFiles().length);
        }
    }

    @Test public void cancelledDownloadNeverBecomesPlayableFile() throws Exception {
        try (Origin server = new Origin(new byte[]{1, 2, 3, 4})) {
            File cache = temporary.newFolder("cancelled");
            try {
                AudioCache.prepare(cache, "S01E01", server.url(), () -> true);
                fail("Cancelled download accepted");
            } catch (java.io.IOException expected) {
                assertEquals(0, cache.listFiles().length);
            }
        }
    }

    @Test public void refreshedAudioIndexReplacesAnOlderCachedSource() throws Exception {
        byte[] repaired = "complete repaired audio".getBytes(StandardCharsets.UTF_8);
        try (Origin server = new Origin(repaired)) {
            File cache = temporary.newFolder("changed-source");
            Files.write(new File(cache, "S07E22.wma").toPath(), "old partial audio".getBytes(StandardCharsets.UTF_8));
            String expected = AlignmentIndex.hex(MessageDigest.getInstance("SHA-256").digest(repaired));
            AudioCache.Result result = AudioCache.prepare(cache, "S07E22", server.url(), () -> false, expected);
            assertEquals(expected, result.sha256);
            assertArrayEquals(repaired, Files.readAllBytes(result.file.toPath()));
            AudioCache.prepare(cache, "S07E22", server.url(), () -> false, expected);
            assertEquals(1, server.requests.get());
        }
    }

    @Test public void wrongPublishedBytesCannotReplacePlayableCache() throws Exception {
        byte[] previous = "previous valid audio".getBytes(StandardCharsets.UTF_8);
        try (Origin server = new Origin("unexpected response".getBytes(StandardCharsets.UTF_8))) {
            File cache = temporary.newFolder("checksum");
            File audio = new File(cache, "S01E01.wma");
            Files.write(audio.toPath(), previous);
            try {
                AudioCache.prepare(cache, "S01E01", server.url(), () -> false, "a".repeat(64));
                fail("Wrong audio was accepted");
            } catch (java.io.IOException expected) {
                assertEquals("Audio checksum mismatch", expected.getMessage());
            }
            assertArrayEquals(previous, Files.readAllBytes(audio.toPath()));
            assertEquals(1, cache.listFiles().length);
        }
    }

    private static final class Origin implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress());
        final AtomicInteger requests = new AtomicInteger();

        Origin(byte[] body) throws Exception {
            Thread worker = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket client = socket.accept()) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) { /* Consume request. */ }
                        requests.incrementAndGet();
                        client.getOutputStream().write(("HTTP/1.0 200 OK\r\nContent-Length: " + body.length
                                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        client.getOutputStream().write(body);
                    } catch (java.io.IOException ignored) { /* Closing the fixture cancels accept. */ }
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        String url() { return "http://127.0.0.1:" + socket.getLocalPort() + "/audio"; }
        @Override public void close() throws Exception { socket.close(); }
    }
}
