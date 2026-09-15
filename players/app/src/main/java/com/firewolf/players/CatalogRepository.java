package com.firewolf.players;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class CatalogRepository {
    public interface Callback {
        void onLoaded(SyncResult result);
    }

    public static final class SyncResult {
        public final List<VideoItem> items;
        public final String status;
        public final long syncedAt;

        SyncResult(List<VideoItem> items, String status, long syncedAt) {
            this.items = Collections.unmodifiableList(items);
            this.status = status;
            this.syncedAt = syncedAt;
        }
    }

    private static final String NASA_SEARCH = "https://images-api.nasa.gov/search?media_type=video&page_size=30&year_start=";
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static volatile CatalogRepository instance;

    private final Context context;
    private final File cacheDirectory;
    private final ExecutorService executor = Executors.newFixedThreadPool(6);
    private final Handler main = new Handler(Looper.getMainLooper());

    private CatalogRepository(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDirectory = new File(this.context.getFilesDir(), "catalog");
        //noinspection ResultOfMethodCallIgnored
        cacheDirectory.mkdirs();
    }

    public static CatalogRepository get(Context context) {
        if (instance == null) {
            synchronized (CatalogRepository.class) {
                if (instance == null) instance = new CatalogRepository(context);
            }
        }
        return instance;
    }

    public List<VideoItem> loadCached() {
        Map<String, VideoItem> merged = new LinkedHashMap<>();
        addAll(merged, readAsset());
        addAll(merged, readCache("remote.json"));
        addAll(merged, readCache("nasa.json"));
        return sort(new ArrayList<>(merged.values()));
    }

    public void refresh(Callback callback) {
        executor.execute(() -> {
            SyncResult result = syncNow();
            main.post(() -> callback.onLoaded(result));
        });
    }

    synchronized SyncResult syncNow() {
        boolean remoteOk = false;
        boolean nasaOk = false;
        try {
            List<VideoItem> remote = CatalogParser.parse(download(remoteCatalogUrl()));
            writeCache("remote.json", CatalogParser.encode(remote, isoNow()));
            remoteOk = true;
        } catch (Exception ignored) {
            // A previous remote catalog and the bundled catalog remain usable.
        }
        try {
            List<VideoItem> nasa = downloadNasa();
            if (!nasa.isEmpty()) {
                writeCache("nasa.json", CatalogParser.encode(nasa, isoNow()));
                nasaOk = true;
            }
        } catch (Exception ignored) {
            // NASA is an optional live source; a cached result remains available.
        }
        long now = System.currentTimeMillis();
        if (remoteOk || nasaOk) context.getSharedPreferences("players_settings", Context.MODE_PRIVATE)
                .edit().putLong("last_sync", now).apply();
        String status;
        if (remoteOk && nasaOk) status = "资源已更新";
        else if (remoteOk || nasaOk) status = "部分资源已更新";
        else status = "离线片单可用";
        return new SyncResult(loadCached(), status, now);
    }

    public long lastSuccessfulSync() {
        return context.getSharedPreferences("players_settings", Context.MODE_PRIVATE)
                .getLong("last_sync", 0L);
    }

    public String remoteCatalogUrl() {
        SharedPreferences preferences = context.getSharedPreferences("players_settings", Context.MODE_PRIVATE);
        String custom = VideoItem.secureUrl(preferences.getString("catalog_url", BuildConfig.REMOTE_CATALOG_URL));
        return custom.isEmpty() ? BuildConfig.REMOTE_CATALOG_URL : custom;
    }

    public boolean setRemoteCatalogUrl(String value) {
        String url = VideoItem.secureUrl(value);
        if (url.isEmpty()) return false;
        context.getSharedPreferences("players_settings", Context.MODE_PRIVATE)
                .edit().putString("catalog_url", url).apply();
        return true;
    }

    public void resetRemoteCatalogUrl() {
        context.getSharedPreferences("players_settings", Context.MODE_PRIVATE)
                .edit().remove("catalog_url").apply();
    }

    private List<VideoItem> readAsset() {
        try (InputStream input = context.getAssets().open("catalog.json")) {
            return CatalogParser.parse(read(input));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<VideoItem> readCache(String name) {
        File file = new File(cacheDirectory, name);
        if (!file.isFile()) return Collections.emptyList();
        try (InputStream input = new FileInputStream(file)) {
            return CatalogParser.parse(read(input));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private void writeCache(String name, String content) throws Exception {
        File target = new File(cacheDirectory, name);
        File temporary = new File(cacheDirectory, name + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) throw new IllegalStateException("cannot replace catalog");
        if (!temporary.renameTo(target)) throw new IllegalStateException("cannot publish catalog");
    }

    private List<VideoItem> downloadNasa() throws Exception {
        int year = Calendar.getInstance().get(Calendar.YEAR) - 1;
        JSONObject response = new JSONObject(download(NASA_SEARCH + year));
        JSONArray items = response.getJSONObject("collection").getJSONArray("items");
        List<NasaStub> stubs = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            JSONArray dataValues = item.optJSONArray("data");
            if (dataValues == null || dataValues.length() == 0) continue;
            JSONObject data = dataValues.optJSONObject(0);
            if (data == null) continue;
            if (data.optString("nasa_id").trim().isEmpty()) continue;
            String poster = "";
            JSONArray links = item.optJSONArray("links");
            if (links != null) {
                for (int linkIndex = 0; linkIndex < links.length(); linkIndex++) {
                    JSONObject link = links.optJSONObject(linkIndex);
                    if (link != null && "image".equals(link.optString("render"))) {
                        poster = link.optString("href");
                        if (link.optInt("width", 0) >= 800) break;
                    }
                }
            }
            stubs.add(new NasaStub(
                    data.optString("nasa_id"), data.optString("title"),
                    firstNonEmpty(data.optString("description"), data.optString("description_508")),
                    data.optString("date_created"), poster, item.optString("href")));
        }
        stubs.sort((left, right) -> right.date.compareTo(left.date));
        if (stubs.size() > 18) stubs = new ArrayList<>(stubs.subList(0, 18));

        List<Future<VideoItem>> futures = new ArrayList<>();
        for (NasaStub stub : stubs) {
            futures.add(executor.submit((Callable<VideoItem>) () -> resolveNasa(stub)));
        }
        List<VideoItem> result = new ArrayList<>();
        for (Future<VideoItem> future : futures) {
            try {
                VideoItem item = future.get();
                if (item != null && item.isPlayable()) result.add(item);
            } catch (Exception ignored) {
                // One malformed NASA item must not discard the rest of the feed.
            }
        }
        return result;
    }

    private VideoItem resolveNasa(NasaStub stub) throws Exception {
        JSONArray assets = new JSONArray(download(stub.collectionUrl));
        List<String> originals = new ArrayList<>();
        List<String> large = new ArrayList<>();
        List<String> medium = new ArrayList<>();
        String subtitles = "";
        for (int index = 0; index < assets.length(); index++) {
            String url = VideoItem.secureUrl(assets.optString(index));
            if (url.isEmpty()) continue;
            String lower = url.toLowerCase(Locale.ROOT);
            if ((lower.endsWith(".srt") || lower.endsWith(".vtt")) && subtitles.isEmpty()) subtitles = url;
            else if (lower.endsWith("~orig.mp4")) originals.add(url);
            else if (lower.endsWith("~large.mp4")) large.add(url);
            else if (lower.endsWith("~medium.mp4")) medium.add(url);
        }
        List<VideoItem.Stream> streams = new ArrayList<>();
        if (!originals.isEmpty()) streams.add(new VideoItem.Stream("原画", originals.get(0), "video/mp4", subtitles));
        if (!large.isEmpty()) streams.add(new VideoItem.Stream("高清", large.get(0), "video/mp4", subtitles));
        if (!medium.isEmpty()) streams.add(new VideoItem.Stream("流畅", medium.get(0), "video/mp4", subtitles));
        String year = stub.date.length() >= 4 ? stub.date.substring(0, 4) : "";
        String summary = stub.summary.length() > 700 ? stub.summary.substring(0, 700) + "…" : stub.summary;
        return new VideoItem(
                "nasa:" + stub.id, stub.title, summary, stub.poster, "NASA 官方媒体库",
                "NASA", year, streams.isEmpty() ? "高清" : streams.get(0).label,
                "NASA Media Usage Guidelines",
                "https://www.nasa.gov/nasa-brand-center/images-and-media/", stub.date, streams);
    }

    private String download(String rawUrl) throws Exception {
        String url = VideoItem.secureUrl(rawUrl);
        if (url.isEmpty()) throw new IllegalArgumentException("only HTTPS sources are allowed");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(25_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "PlayersAndroid/1.0 (https://github.com/FirewolfO/apps)");
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() / 100 != 2) {
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            }
            try (InputStream input = connection.getInputStream()) {
                return read(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("response too large");
            output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void addAll(Map<String, VideoItem> target, List<VideoItem> items) {
        for (VideoItem item : items) if (item.isPlayable()) target.put(item.id, item);
    }

    private static List<VideoItem> sort(List<VideoItem> values) {
        values.sort(Comparator
                .comparing((VideoItem item) -> item.addedAt, Comparator.reverseOrder())
                .thenComparing(item -> item.title));
        return values;
    }

    private static String firstNonEmpty(String first, String second) {
        return first == null || first.trim().isEmpty() ? second : first;
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static final class NasaStub {
        final String id;
        final String title;
        final String summary;
        final String date;
        final String poster;
        final String collectionUrl;

        NasaStub(String id, String title, String summary, String date, String poster, String collectionUrl) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.date = date;
            this.poster = poster;
            this.collectionUrl = collectionUrl;
        }
    }
}
