package com.firewolf.xiaolinstudy.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CatalogRepository {
    private CatalogRepository() {}

    public static List<CatalogGroup> load(Context context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("catalog.json"), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                json.append(buffer, 0, count);
            }
            JSONArray groups = new JSONObject(json.toString()).getJSONArray("groups");
            List<CatalogGroup> result = new ArrayList<>();
            for (int index = 0; index < groups.length(); index++) {
                result.add(parseGroup(groups.getJSONObject(index)));
            }
            return Collections.unmodifiableList(result);
        } catch (IOException | JSONException error) {
            return Collections.emptyList();
        }
    }

    private static CatalogGroup parseGroup(JSONObject json) throws JSONException {
        JSONArray values = json.getJSONArray("books");
        List<CatalogBook> books = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            books.add(parseBook(values.getJSONObject(index)));
        }
        return new CatalogGroup(json.getString("title"), json.getString("description"), books);
    }

    private static CatalogBook parseBook(JSONObject json) throws JSONException {
        JSONArray values = json.getJSONArray("sections");
        List<CatalogSection> sections = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            sections.add(parseSection(values.getJSONObject(index)));
        }
        return new CatalogBook(json.getString("title"), json.getString("description"),
                json.getString("homeUrl"), sections);
    }

    private static CatalogSection parseSection(JSONObject json) throws JSONException {
        JSONArray values = json.getJSONArray("articles");
        List<CatalogArticle> articles = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.getJSONObject(index);
            articles.add(new CatalogArticle(value.getString("title"), value.getString("url")));
        }
        return new CatalogSection(json.getString("title"), articles);
    }

    public static final class CatalogGroup {
        private final String title;
        private final String description;
        private final List<CatalogBook> books;

        CatalogGroup(String title, String description, List<CatalogBook> books) {
            this.title = title;
            this.description = description;
            this.books = Collections.unmodifiableList(books);
        }

        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public List<CatalogBook> getBooks() { return books; }

        public int articleCount() {
            int total = 0;
            for (CatalogBook book : books) total += book.articleCount();
            return total;
        }
    }

    public static final class CatalogBook {
        private final String title;
        private final String description;
        private final String homeUrl;
        private final List<CatalogSection> sections;

        CatalogBook(String title, String description, String homeUrl,
                    List<CatalogSection> sections) {
            this.title = title;
            this.description = description;
            this.homeUrl = homeUrl;
            this.sections = Collections.unmodifiableList(sections);
        }

        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getHomeUrl() { return homeUrl; }
        public List<CatalogSection> getSections() { return sections; }

        public int articleCount() {
            int total = 0;
            for (CatalogSection section : sections) total += section.getArticles().size();
            return total;
        }
    }

    public static final class CatalogSection {
        private final String title;
        private final List<CatalogArticle> articles;

        CatalogSection(String title, List<CatalogArticle> articles) {
            this.title = title;
            this.articles = Collections.unmodifiableList(articles);
        }

        public String getTitle() { return title; }
        public List<CatalogArticle> getArticles() { return articles; }
    }

    public static final class CatalogArticle {
        private final String title;
        private final String url;

        CatalogArticle(String title, String url) {
            this.title = title;
            this.url = url;
        }

        public String getTitle() { return title; }
        public String getUrl() { return url; }
    }
}
