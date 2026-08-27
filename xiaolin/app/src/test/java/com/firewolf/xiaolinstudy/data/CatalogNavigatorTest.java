package com.firewolf.xiaolinstudy.data;

import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogArticle;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogBook;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogGroup;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogSection;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public final class CatalogNavigatorTest {
    private static final String BASE_URL = "https://www.xiaolincoding.com/test/";

    @Test
    public void navigatesInCatalogOrderAcrossSections() {
        CatalogArticle first = article("第一节", "first.html");
        CatalogArticle second = article("第二节", "second.html");
        CatalogArticle third = article("第三节", "third.html");
        CatalogBook book = book("专题一",
                section("第一章", first, second),
                section("第二章", third));

        CatalogNavigator navigator = navigator(book);
        CatalogNavigator.Position firstPosition = navigator.find(first.getUrl());
        CatalogNavigator.Position secondPosition = navigator.find(second.getUrl());
        CatalogNavigator.Position thirdPosition = navigator.find(third.getUrl());

        assertNull(firstPosition.getPrevious());
        assertSame(second, firstPosition.getNext());
        assertSame(first, secondPosition.getPrevious());
        assertSame(third, secondPosition.getNext());
        assertSame(second, thirdPosition.getPrevious());
        assertNull(thirdPosition.getNext());
        assertEquals(2, thirdPosition.getIndex());
        assertEquals(3, thirdPosition.getTotal());
        assertEquals("第二章", thirdPosition.getSectionTitle());
    }

    @Test
    public void doesNotNavigateAcrossBooks() {
        CatalogArticle firstBookLast = article("专题一末节", "book-one.html");
        CatalogArticle secondBookFirst = article("专题二首节", "book-two.html");
        CatalogNavigator navigator = navigator(
                book("专题一", section("章节", firstBookLast)),
                book("专题二", section("章节", secondBookFirst)));

        assertNull(navigator.find(firstBookLast.getUrl()).getNext());
        assertNull(navigator.find(secondBookFirst.getUrl()).getPrevious());
    }

    @Test
    public void findsCatalogPageAfterUrlNormalization() {
        CatalogArticle article = article("正文", "normalized.html");
        CatalogNavigator navigator = navigator(book("专题", section("章节", article)));

        CatalogNavigator.Position position = navigator.find(
                "http://xiaolincoding.com/test/normalized.html?from=app#section");

        assertSame(article, position.getArticle());
    }

    private static CatalogNavigator navigator(CatalogBook... books) {
        CatalogGroup group = new CatalogGroup("系列", "说明", Arrays.asList(books));
        return new CatalogNavigator(Collections.singletonList(group));
    }

    private static CatalogBook book(String title, CatalogSection... sections) {
        return new CatalogBook(title, "说明", BASE_URL, Arrays.asList(sections));
    }

    private static CatalogSection section(String title, CatalogArticle... articles) {
        return new CatalogSection(title, Arrays.asList(articles));
    }

    private static CatalogArticle article(String title, String path) {
        return new CatalogArticle(title, BASE_URL + path);
    }
}
