package com.inkriver.historyreader.data;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CatalogRepository {
    private final Context context;
    private final ReaderStore store;
    private List<Book> cached;

    public CatalogRepository(Context context, ReaderStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
    }

    public List<Book> allBooks() {
        if (cached != null) return cached;
        ArrayList<Book> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(readAsset("catalog.json"));
            JSONArray histories = root.getJSONArray("histories");
            for (int i = 0; i < histories.length(); i++) {
                JSONObject item = histories.getJSONObject(i);
                addEdition(result, item, Book.Edition.ORIGINAL);
                addEdition(result, item, Book.Edition.VERNACULAR);
            }
            result.addAll(store.importedBooks());
        } catch (Exception error) {
            throw new IllegalStateException("无法读取内置书目", error);
        }
        cached = Collections.unmodifiableList(result);
        return cached;
    }

    public void invalidate() {
        cached = null;
    }

    public Book find(String id) {
        for (Book book : allBooks()) {
            if (book.id.equals(id)) return book;
        }
        return null;
    }

    public List<Chapter> chapters(Book book) {
        if (book == null) return Collections.emptyList();
        if (book.edition == Book.Edition.IMPORTED) return importedChapters(book);
        try {
            JSONObject root = new JSONObject(readAsset(
                    "content/" + book.contentFile + "/index.json"));
            JSONArray source = root.getJSONArray("chapters");
            ArrayList<Chapter> chapters = new ArrayList<>();
            for (int i = 0; i < source.length(); i++) {
                JSONObject chapter = source.getJSONObject(i);
                chapters.add(new Chapter(
                        chapter.optInt("index", i),
                        chapter.getString("title"),
                        "content/" + book.contentFile + "/" + chapter.getString("file")));
            }
            return chapters;
        } catch (Exception error) {
            return legacyChapters(book);
        }
    }

    public String textFor(Book book, Chapter chapter) {
        if (chapter.assetPath == null) return chapter.textFor(book.edition);
        try {
            JSONObject payload = new JSONObject(readAsset(chapter.assetPath));
            String key = book.edition == Book.Edition.VERNACULAR
                    ? "vernacular" : "original";
            String value = payload.getString(key).trim();
            if (value.isEmpty()) throw new IllegalStateException("empty " + key);
            return value;
        } catch (Exception error) {
            return book.edition == Book.Edition.VERNACULAR
                    ? "本卷白话机器初译文件无法读取。"
                    : "本卷原文文件无法读取。";
        }
    }

    private List<Chapter> legacyChapters(Book book) {
        try {
            JSONObject root = new JSONObject(readAsset("content/" + book.contentFile));
            JSONArray source = root.getJSONArray("chapters");
            ArrayList<Chapter> chapters = new ArrayList<>();
            for (int i = 0; i < source.length(); i++) {
                JSONObject chapter = source.getJSONObject(i);
                chapters.add(new Chapter(
                        chapter.optInt("index", i),
                        chapter.getString("title"),
                        chapter.optString("original"),
                        chapter.optString("vernacular")));
            }
            return chapters;
        } catch (Exception error) {
            return Collections.singletonList(new Chapter(
                    0,
                    "内容未安装",
                    "这部书的原文语料尚未通过完整性校验，因此没有被冒充为全本打包。",
                    "这部书的白话译稿尚未完成逐卷校验，因此没有被冒充为全本打包。"));
        }
    }

    private void addEdition(List<Book> target, JSONObject item, Book.Edition edition) throws Exception {
        String key = item.getString("id");
        boolean original = edition == Book.Edition.ORIGINAL;
        JSONObject availability = item.getJSONObject("availability");
        target.add(new Book(
                key + (original ? "-original" : "-vernacular"),
                key,
                item.getString("title"),
                item.getString("author"),
                item.getString("period"),
                item.getInt("volumes"),
                item.getString("description"),
                Color.parseColor(item.getString("accent")),
                edition,
                availability.optBoolean(original ? "originalComplete" : "vernacularComplete"),
                item.getString("content")));
    }

    private List<Chapter> importedChapters(Book book) {
        try {
            String text = readStream(new FileInputStream(new File(book.contentFile)));
            ArrayList<Chapter> chapters = new ArrayList<>();
            String[] parts = text.split("(?m)(?=^\\s*(?:第[一二三四五六七八九十百千万0-9]+[章节回卷]|卷[一二三四五六七八九十百千万0-9]+).*$)");
            int index = 0;
            for (String part : parts) {
                String clean = part.trim();
                if (clean.isEmpty()) continue;
                int lineEnd = clean.indexOf('\n');
                String title = lineEnd > 0 && lineEnd < 80
                        ? clean.substring(0, lineEnd).trim() : "第 " + (index + 1) + " 章";
                chapters.add(new Chapter(index++, title, clean, clean));
            }
            if (chapters.isEmpty()) chapters.add(new Chapter(0, book.title, text, text));
            return chapters;
        } catch (Exception error) {
            return Collections.singletonList(new Chapter(0, "文件不可用", "无法读取导入文件。", "无法读取导入文件。"));
        }
    }

    private String readAsset(String path) throws Exception {
        try (InputStream input = context.getAssets().open(path)) {
            return readStream(input);
        }
    }

    private static String readStream(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        byte[] bytes = output.toByteArray();
        int offset = bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf ? 3 : 0;
        ByteBuffer source = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(source)
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            return Charset.forName("GB18030").decode(
                    ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
        }
    }
}
