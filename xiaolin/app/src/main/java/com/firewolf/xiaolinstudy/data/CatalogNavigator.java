package com.firewolf.xiaolinstudy.data;

import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogArticle;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogBook;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogGroup;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CatalogNavigator {
    private final Map<String, Position> positions;

    public CatalogNavigator(List<CatalogGroup> groups) {
        Map<String, Position> result = new HashMap<>();
        for (CatalogGroup group : groups) {
            for (CatalogBook book : group.getBooks()) {
                List<Entry> entries = flatten(book);
                for (int index = 0; index < entries.size(); index++) {
                    Entry entry = entries.get(index);
                    CatalogArticle previous = index > 0 ? entries.get(index - 1).article : null;
                    CatalogArticle next = index + 1 < entries.size()
                            ? entries.get(index + 1).article : null;
                    result.put(UrlTools.normalize(entry.article.getUrl()), new Position(
                            group.getTitle(), book.getTitle(), entry.sectionTitle,
                            entry.article, previous, next, index, entries.size()));
                }
            }
        }
        positions = Collections.unmodifiableMap(result);
    }

    public Position find(String url) {
        return positions.get(UrlTools.normalize(url));
    }

    private static List<Entry> flatten(CatalogBook book) {
        List<Entry> result = new ArrayList<>();
        for (CatalogSection section : book.getSections()) {
            for (CatalogArticle article : section.getArticles()) {
                result.add(new Entry(section.getTitle(), article));
            }
        }
        return result;
    }

    private static final class Entry {
        final String sectionTitle;
        final CatalogArticle article;

        Entry(String sectionTitle, CatalogArticle article) {
            this.sectionTitle = sectionTitle;
            this.article = article;
        }
    }

    public static final class Position {
        private final String groupTitle;
        private final String bookTitle;
        private final String sectionTitle;
        private final CatalogArticle article;
        private final CatalogArticle previous;
        private final CatalogArticle next;
        private final int index;
        private final int total;

        Position(String groupTitle, String bookTitle, String sectionTitle,
                 CatalogArticle article, CatalogArticle previous, CatalogArticle next,
                 int index, int total) {
            this.groupTitle = groupTitle;
            this.bookTitle = bookTitle;
            this.sectionTitle = sectionTitle;
            this.article = article;
            this.previous = previous;
            this.next = next;
            this.index = index;
            this.total = total;
        }

        public String getGroupTitle() { return groupTitle; }
        public String getBookTitle() { return bookTitle; }
        public String getSectionTitle() { return sectionTitle; }
        public CatalogArticle getArticle() { return article; }
        public CatalogArticle getPrevious() { return previous; }
        public CatalogArticle getNext() { return next; }
        public int getIndex() { return index; }
        public int getTotal() { return total; }
    }
}
