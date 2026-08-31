package com.firewolf.xiaolinstudy.data;

import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogArticle;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CompactHtmlRendererTest {
    @Test
    public void rendersInterviewSectionsDiagramAndSafeSourceLink() {
        CatalogArticle article = new CatalogArticle("TCP <握手>", "https://compact.xiaolin/tcp",
                "快速摘要", "30 秒回答", Arrays.asList("要点一", "要点二"),
                Arrays.asList("追问与回答"), "不要混淆", "https://www.xiaolincoding.com/network/",
                "关键流程", Arrays.asList("SYN", "SYN + ACK", "ACK"));

        String html = CompactHtmlRenderer.render(article);

        assertTrue(html.contains("30 秒回答"));
        assertTrue(html.contains("面试官常追问"));
        assertTrue(html.contains("关键流程"));
        assertTrue(html.contains("查看小林原文"));
        assertTrue(html.contains("TCP &lt;握手&gt;"));
        assertFalse(html.contains("TCP <握手>"));
    }

    @Test
    public void rendersDedicatedDarkPalette() {
        CatalogArticle article = new CatalogArticle("深色速记", "https://compact.xiaolin/dark",
                "摘要", "回答", Arrays.asList("要点"), Arrays.asList("追问"), "提醒",
                "https://www.xiaolincoding.com/", "", Arrays.asList());

        String darkHtml = CompactHtmlRenderer.render(article, true);

        assertTrue(darkHtml.contains("content=\"dark\""));
        assertTrue(darkHtml.contains("color-scheme:dark"));
        assertTrue(darkHtml.contains("--bg:#101412"));
        assertFalse(darkHtml.contains("--surface:#fff"));
    }
}
