package com.inkriver.historyreader.data;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BookTest {
    private Book book(Book.Edition edition) {
        return new Book("shiji-original", "shiji", "史记", "司马迁", "西汉",
                130, "纪传体通史", 0, edition, false, "shiji.json");
    }

    @Test
    public void searchCoversMetadataAndEdition() {
        assertTrue(book(Book.Edition.ORIGINAL).matches("司马迁"));
        assertTrue(book(Book.Edition.ORIGINAL).matches("西汉"));
        assertTrue(book(Book.Edition.VERNACULAR).matches("白话"));
        assertFalse(book(Book.Edition.ORIGINAL).matches("班固"));
    }

    @Test
    public void displayTitleDistinguishesEditions() {
        assertTrue(book(Book.Edition.ORIGINAL).displayTitle().endsWith("原文"));
        assertTrue(book(Book.Edition.VERNACULAR).displayTitle().endsWith("白话"));
    }

    @Test
    public void builtInEditionsShareHistoryProgress() {
        assertEquals("shiji", book(Book.Edition.ORIGINAL).progressKey());
        assertEquals(book(Book.Edition.ORIGINAL).progressKey(),
                book(Book.Edition.VERNACULAR).progressKey());
    }
}
