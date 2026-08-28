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
        return load(context, "catalog.json");
    }

    public static List<CatalogGroup> load(Context context, boolean compactMode) {
        return load(context, compactMode ? "compact_catalog.json" : "catalog.json");
    }

    private static List<CatalogGroup> load(Context context, String assetName) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(assetName), StandardCharsets.UTF_8))) {
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
            JSONObject diagram = value.optJSONObject("diagram");
            articles.add(new CatalogArticle(value.getString("title"), value.getString("url"),
                    value.optString("summary", ""), value.optString("answer", ""),
                    readStrings(value.optJSONArray("keyPoints")),
                    readStrings(value.optJSONArray("followUps")),
                    value.optString("pitfall", ""), value.optString("sourceUrl", ""),
                    diagram == null ? "" : diagram.optString("title", ""),
                    diagram == null ? Collections.emptyList()
                            : readStrings(diagram.optJSONArray("nodes"))));
        }
        return new CatalogSection(json.getString("title"), articles);
    }

    private static List<String> readStrings(JSONArray values) {
        if (values == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            String value = values.optString(index, "").trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
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
        private final String summary;
        private final String answer;
        private final List<String> keyPoints;
        private final List<String> followUps;
        private final String pitfall;
        private final String sourceUrl;
        private final String diagramTitle;
        private final List<String> diagramNodes;

        CatalogArticle(String title, String url) {
            this(title, url, "", "", Collections.emptyList(), Collections.emptyList(),
                    "", "", "", Collections.emptyList());
        }

        CatalogArticle(String title, String url, String summary, String answer,
                       List<String> keyPoints, List<String> followUps, String pitfall,
                       String sourceUrl, String diagramTitle, List<String> diagramNodes) {
            this.title = title;
            this.url = url;
            this.summary = summary;
            this.answer = answer;
            this.keyPoints = Collections.unmodifiableList(new ArrayList<>(keyPoints));
            this.followUps = Collections.unmodifiableList(new ArrayList<>(followUps));
            this.pitfall = pitfall;
            this.sourceUrl = sourceUrl;
            this.diagramTitle = diagramTitle;
            this.diagramNodes = Collections.unmodifiableList(new ArrayList<>(diagramNodes));
        }

        public String getTitle() { return title; }
        public String getUrl() { return url; }
        public String getSummary() { return summary; }
        public String getAnswer() { return answer; }
        public List<String> getKeyPoints() { return keyPoints; }
        public List<String> getFollowUps() { return followUps; }
        public String getPitfall() { return pitfall; }
        public String getSourceUrl() { return sourceUrl; }
        public String getDiagramTitle() { return diagramTitle; }
        public List<String> getDiagramNodes() { return diagramNodes; }
        public boolean isCompact() { return !answer.isEmpty(); }
    }
}
