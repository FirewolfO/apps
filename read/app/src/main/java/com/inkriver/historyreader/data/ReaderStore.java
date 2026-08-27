package com.inkriver.historyreader.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class ReaderStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "reader.db";
    private static final int DB_VERSION = 3;

    public ReaderStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE progress (" +
                "book_id TEXT PRIMARY KEY, edition_id TEXT NOT NULL DEFAULT '', " +
                "chapter_index INTEGER NOT NULL, " +
                "scroll_y INTEGER NOT NULL, scroll_position INTEGER NOT NULL DEFAULT 0, " +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE excerpts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, book_id TEXT NOT NULL, book_title TEXT NOT NULL, " +
                "chapter_index INTEGER NOT NULL, chapter_title TEXT NOT NULL, excerpt TEXT NOT NULL, " +
                "note TEXT NOT NULL DEFAULT '', start_offset INTEGER NOT NULL DEFAULT -1, " +
                "end_offset INTEGER NOT NULL DEFAULT -1, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE imports (" +
                "book_id TEXT PRIMARY KEY, title TEXT NOT NULL, path TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX excerpts_book ON excerpts(book_id, chapter_index)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE excerpts ADD COLUMN start_offset INTEGER NOT NULL DEFAULT -1");
            db.execSQL("ALTER TABLE excerpts ADD COLUMN end_offset INTEGER NOT NULL DEFAULT -1");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE progress ADD COLUMN scroll_position INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE progress ADD COLUMN edition_id TEXT NOT NULL DEFAULT ''");
        }
    }

    public void saveProgress(String bookId, int chapter, int scrollY) {
        saveProgress(bookId, bookId, chapter, scrollY, 0);
    }

    public void saveProgress(String bookId, int chapter, int scrollY, int scrollPosition) {
        saveProgress(bookId, bookId, chapter, scrollY, scrollPosition);
    }

    public void saveProgress(String progressKey, String editionId, int chapter,
                             int scrollY, int scrollPosition) {
        saveProgress(progressKey, editionId, chapter, scrollY,
                scrollPosition, System.currentTimeMillis());
    }

    private void saveProgress(String progressKey, String editionId, int chapter,
                              int scrollY, int scrollPosition, long updatedAt) {
        ContentValues values = new ContentValues();
        values.put("book_id", progressKey);
        values.put("edition_id", editionId == null ? "" : editionId);
        values.put("chapter_index", chapter);
        values.put("scroll_y", Math.max(0, scrollY));
        values.put("scroll_position", Math.max(0,
                Math.min(ReadingProgress.POSITION_SCALE, scrollPosition)));
        values.put("updated_at", updatedAt);
        getWritableDatabase().insertWithOnConflict(
                "progress", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public ReadingProgress progress(String bookId) {
        try (Cursor cursor = getReadableDatabase().query(
                "progress",
                new String[]{"edition_id", "chapter_index", "scroll_y", "scroll_position", "updated_at"},
                "book_id=?", new String[]{bookId}, null, null, null)) {
            if (cursor.moveToFirst()) {
                return new ReadingProgress(
                        cursor.getString(0), cursor.getInt(1), cursor.getInt(2),
                        cursor.getInt(3), cursor.getLong(4));
            }
        }
        return new ReadingProgress("", 0, 0, 0, 0);
    }

    public ReadingProgress progressFor(Book book) {
        ReadingProgress shared = progress(book.progressKey());
        if (shared.hasStarted() || book.id.equals(book.progressKey())) return shared;

        ReadingProgress original = progress(book.historyKey + "-original");
        ReadingProgress vernacular = progress(book.historyKey + "-vernacular");
        ReadingProgress legacy = original.updatedAt >= vernacular.updatedAt
                ? original : vernacular;
        if (!legacy.hasStarted()) return shared;

        String legacyBookId = legacy.bookId;
        if (legacyBookId.isEmpty()) {
            legacyBookId = legacy == original
                    ? book.historyKey + "-original" : book.historyKey + "-vernacular";
        }
        saveProgress(book.progressKey(), legacyBookId, legacy.chapter, legacy.scrollY,
                legacy.scrollPosition, legacy.updatedAt);
        return new ReadingProgress(legacyBookId, legacy.chapter, legacy.scrollY,
                legacy.scrollPosition, legacy.updatedAt);
    }

    public long addExcerpt(String bookId, String bookTitle, int chapter, String chapterTitle,
                           String excerpt, String note) {
        return addExcerpt(bookId, bookTitle, chapter, chapterTitle, excerpt, note, -1, -1);
    }

    public long addExcerpt(String bookId, String bookTitle, int chapter, String chapterTitle,
                           String excerpt, String note, int startOffset, int endOffset) {
        ContentValues values = new ContentValues();
        values.put("book_id", bookId);
        values.put("book_title", bookTitle);
        values.put("chapter_index", chapter);
        values.put("chapter_title", chapterTitle);
        values.put("excerpt", excerpt);
        values.put("note", note == null ? "" : note.trim());
        values.put("start_offset", startOffset);
        values.put("end_offset", endOffset);
        values.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("excerpts", null, values);
    }

    public void updateNote(long id, String note) {
        ContentValues values = new ContentValues();
        values.put("note", note == null ? "" : note.trim());
        getWritableDatabase().update("excerpts", values, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteExcerpt(long id) {
        getWritableDatabase().delete("excerpts", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Excerpt> excerpts() {
        ArrayList<Excerpt> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "excerpts", null, null, null, null, null, "created_at DESC")) {
            while (cursor.moveToNext()) {
                result.add(new Excerpt(
                        cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("book_id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("book_title")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("chapter_index")),
                        cursor.getString(cursor.getColumnIndexOrThrow("chapter_title")),
                        cursor.getString(cursor.getColumnIndexOrThrow("excerpt")),
                        cursor.getString(cursor.getColumnIndexOrThrow("note")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("start_offset")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("end_offset")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))));
            }
        }
        return result;
    }

    public List<Excerpt> excerptsFor(String bookId, int chapter) {
        ArrayList<Excerpt> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "excerpts", null, "book_id=? AND chapter_index=?",
                new String[]{bookId, String.valueOf(chapter)}, null, null, "created_at ASC")) {
            while (cursor.moveToNext()) {
                result.add(new Excerpt(
                        cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("book_id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("book_title")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("chapter_index")),
                        cursor.getString(cursor.getColumnIndexOrThrow("chapter_title")),
                        cursor.getString(cursor.getColumnIndexOrThrow("excerpt")),
                        cursor.getString(cursor.getColumnIndexOrThrow("note")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("start_offset")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("end_offset")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))));
            }
        }
        return result;
    }

    public void addImport(String id, String title, String path) {
        ContentValues values = new ContentValues();
        values.put("book_id", id);
        values.put("title", title);
        values.put("path", path);
        values.put("created_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "imports", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<Book> importedBooks() {
        ArrayList<Book> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "imports", null, null, null, null, null, "created_at DESC")) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(cursor.getColumnIndexOrThrow("book_id"));
                String title = cursor.getString(cursor.getColumnIndexOrThrow("title"));
                String path = cursor.getString(cursor.getColumnIndexOrThrow("path"));
                result.add(new Book(id, id, title, "本地文件", "个人书架", 1,
                        "从设备导入的纯文本书籍", Color.rgb(86, 114, 103),
                        Book.Edition.IMPORTED, true, path));
            }
        }
        return result;
    }

    public JSONObject exportData() throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "history-reader-backup");
        root.put("version", 1);
        root.put("exportedAt", System.currentTimeMillis());
        JSONArray progressItems = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query("progress", null, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("bookId", cursor.getString(cursor.getColumnIndexOrThrow("book_id")));
                item.put("editionId", cursor.getString(cursor.getColumnIndexOrThrow("edition_id")));
                item.put("chapter", cursor.getInt(cursor.getColumnIndexOrThrow("chapter_index")));
                item.put("scrollY", cursor.getInt(cursor.getColumnIndexOrThrow("scroll_y")));
                item.put("scrollPosition", cursor.getInt(
                        cursor.getColumnIndexOrThrow("scroll_position")));
                item.put("updatedAt", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
                progressItems.put(item);
            }
        }
        JSONArray excerptItems = new JSONArray();
        for (Excerpt excerpt : excerpts()) {
            JSONObject item = new JSONObject();
            item.put("bookId", excerpt.bookId);
            item.put("bookTitle", excerpt.bookTitle);
            item.put("chapter", excerpt.chapter);
            item.put("chapterTitle", excerpt.chapterTitle);
            item.put("text", excerpt.text);
            item.put("note", excerpt.note);
            item.put("startOffset", excerpt.startOffset);
            item.put("endOffset", excerpt.endOffset);
            item.put("createdAt", excerpt.createdAt);
            excerptItems.put(item);
        }
        root.put("progress", progressItems);
        root.put("excerpts", excerptItems);
        return root;
    }
}
