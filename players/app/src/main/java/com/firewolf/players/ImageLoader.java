package com.firewolf.players;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImageLoader {
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static volatile ImageLoader instance;
    private final LruCache<String, Bitmap> memory;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final File cacheDirectory;

    private ImageLoader(Context context) {
        int cacheKilobytes = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 8);
        memory = new LruCache<String, Bitmap>(cacheKilobytes) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return Math.max(1, bitmap.getAllocationByteCount() / 1024);
            }
        };
        cacheDirectory = new File(context.getCacheDir(), "posters");
        //noinspection ResultOfMethodCallIgnored
        cacheDirectory.mkdirs();
    }

    public static ImageLoader get(Context context) {
        if (instance == null) {
            synchronized (ImageLoader.class) {
                if (instance == null) instance = new ImageLoader(context.getApplicationContext());
            }
        }
        return instance;
    }

    public void load(String url, ImageView view) {
        view.setTag(url);
        view.setImageDrawable(null);
        if (url == null || !url.startsWith("https://")) return;
        Bitmap cached = memory.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        executor.execute(() -> {
            Bitmap bitmap = loadBitmap(url);
            if (bitmap == null) return;
            memory.put(url, bitmap);
            view.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    private Bitmap loadBitmap(String url) {
        File disk = new File(cacheDirectory, sha256(url));
        try {
            if (disk.isFile()) {
                try (InputStream input = new FileInputStream(disk)) {
                    Bitmap bitmap = decode(readBounded(input));
                    if (bitmap != null) return bitmap;
                }
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "PlayersAndroid/1.0");
            connection.setInstanceFollowRedirects(true);
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = readBounded(input);
                Bitmap bitmap = decode(bytes);
                if (bitmap != null) {
                    try (FileOutputStream output = new FileOutputStream(disk)) {
                        output.write(bytes);
                    }
                }
                return bitmap;
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_IMAGE_BYTES) throw new IllegalStateException("image too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
