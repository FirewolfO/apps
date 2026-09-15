package com.firewolf.players;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CatalogParserTest {
    @Test
    public void parsesQualityOptionsAndMetadata() throws Exception {
        List<VideoItem> items = CatalogParser.parse("{\"version\":1,\"items\":[{" +
                "\"id\":\"film-1\",\"title\":\"测试电影\",\"summary\":\"清晰简介\"," +
                "\"posterUrl\":\"https://example.com/poster.jpg\",\"source\":\"授权片库\"," +
                "\"category\":\"电影\",\"year\":\"2026\",\"badge\":\"4K\"," +
                "\"license\":{\"name\":\"CC BY\",\"url\":\"https://example.com/license\"}," +
                "\"streams\":[{\"label\":\"4K\",\"url\":\"https://example.com/movie.mpd\"}," +
                "{\"label\":\"1080P\",\"url\":\"https://example.com/movie.m3u8\"}]}]}");

        assertEquals(1, items.size());
        assertEquals("测试电影", items.get(0).title);
        assertEquals("4K", items.get(0).primaryStream().label);
        assertEquals(2, items.get(0).streams.size());
        assertTrue(items.get(0).matches("授权片库"));
    }

    @Test
    public void removesCleartextPlaybackAndPosterUrls() throws Exception {
        List<VideoItem> items = CatalogParser.parse("{\"version\":1,\"items\":[{" +
                "\"id\":\"unsafe\",\"title\":\"不安全片源\",\"posterUrl\":\"http://example.com/a.jpg\"," +
                "\"streams\":[{\"url\":\"http://example.com/a.mp4\"}]}]}");

        assertTrue(items.isEmpty());
    }

    @Test(expected = JSONException.class)
    public void rejectsUnknownCatalogVersion() throws Exception {
        CatalogParser.parse("{\"version\":2,\"items\":[]}");
    }

    @Test
    public void matchingIsCaseInsensitiveAndSearchesDescription() {
        VideoItem item = new VideoItem("id", "Sintel", "Dragon Adventure", "", "Blender",
                "动画", "2010", "HD", "CC BY", "", "", java.util.Collections.singletonList(
                new VideoItem.Stream("HD", "https://example.com/sintel.mp4", "video/mp4", "")));
        assertTrue(item.matches("dragon"));
        assertTrue(item.matches("BLENDER"));
        assertFalse(item.matches("NASA"));
    }

    @Test
    public void normalizesOfficialNasaUrlsForPlayback() {
        String url = VideoItem.secureUrl("http://images-assets.nasa.gov/video/NASA’s New Film/NASA’s New Film~orig.mp4");
        assertEquals("https://images-assets.nasa.gov/video/NASA%E2%80%99s%20New%20Film/NASA%E2%80%99s%20New%20Film~orig.mp4", url);
    }
}
