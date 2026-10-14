package com.wind.subword;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 生词本和查词记录。
 * vocab：加进生词本的词，同一个词（不分大小写）只记一条，保留第一次加入时的原句和视频位置。
 * lookups：每次点词都记一条，用来显示「这个词查过几次」。
 */
final class VocabDb extends SQLiteOpenHelper {
    private static VocabDb instance;

    static synchronized VocabDb get(Context c) {
        if (instance == null) instance = new VocabDb(c.getApplicationContext());
        return instance;
    }

    private VocabDb(Context c) {
        super(c, "vocab.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE vocab (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "word TEXT NOT NULL," +
                "word_key TEXT NOT NULL UNIQUE," +
                "phonetic TEXT, meaning TEXT, form TEXT, note TEXT," +
                "sentence TEXT, video_url TEXT, video_title TEXT, video_time INTEGER," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE lookups (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "word_key TEXT NOT NULL," +
                "sentence TEXT, video_url TEXT, video_time INTEGER," +
                "at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX lookups_word ON lookups(word_key)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    static String key(String word) {
        return word.trim().toLowerCase(Locale.ROOT).replace('’', '\'');
    }

    static final class Entry {
        long id;
        String word, phonetic, meaning, form, note, sentence, videoUrl, videoTitle;
        int videoTime;
        long createdAt;

        /** 带时间点的视频地址：从生词本点回去时直接跳到这句 */
        String videoLink() {
            return Youtube.linkAt(videoUrl, videoTime);
        }
    }

    /** 记一次查词，返回这个词一共查过几次（含这次） */
    int recordLookup(String word, String sentence, String url, int time) {
        ContentValues v = new ContentValues();
        v.put("word_key", key(word));
        v.put("sentence", sentence);
        v.put("video_url", url);
        v.put("video_time", time);
        v.put("at", System.currentTimeMillis());
        getWritableDatabase().insert("lookups", null, v);
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM lookups WHERE word_key = ?", new String[]{key(word)})) {
            return c.moveToFirst() ? c.getInt(0) : 1;
        }
    }

    Entry find(String word) {
        try (Cursor c = getReadableDatabase().query("vocab", null, "word_key = ?", new String[]{key(word)}, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    void add(Entry e) {
        ContentValues v = new ContentValues();
        v.put("word", e.word);
        v.put("word_key", key(e.word));
        v.put("phonetic", e.phonetic);
        v.put("meaning", e.meaning);
        v.put("form", e.form);
        v.put("note", e.note);
        v.put("sentence", e.sentence);
        v.put("video_url", e.videoUrl);
        v.put("video_title", e.videoTitle);
        v.put("video_time", e.videoTime);
        v.put("created_at", e.createdAt > 0 ? e.createdAt : System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("vocab", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    void remove(String word) {
        getWritableDatabase().delete("vocab", "word_key = ?", new String[]{key(word)});
    }

    List<Entry> all() {
        List<Entry> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("vocab", null, null, null, null, null, "created_at DESC")) {
            while (c.moveToNext()) list.add(read(c));
        }
        return list;
    }

    int lookupCount(String word) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM lookups WHERE word_key = ?", new String[]{key(word)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private static Entry read(Cursor c) {
        Entry e = new Entry();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.word = c.getString(c.getColumnIndexOrThrow("word"));
        e.phonetic = c.getString(c.getColumnIndexOrThrow("phonetic"));
        e.meaning = c.getString(c.getColumnIndexOrThrow("meaning"));
        e.form = c.getString(c.getColumnIndexOrThrow("form"));
        e.note = c.getString(c.getColumnIndexOrThrow("note"));
        e.sentence = c.getString(c.getColumnIndexOrThrow("sentence"));
        e.videoUrl = c.getString(c.getColumnIndexOrThrow("video_url"));
        e.videoTitle = c.getString(c.getColumnIndexOrThrow("video_title"));
        e.videoTime = c.getInt(c.getColumnIndexOrThrow("video_time"));
        e.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return e;
    }

    /**
     * 导出成一行一个 JSON，字段和 LearningPack 的 PackFormat/import_vocab.sh 对得上，
     * 可以直接导入 iPad 的生词本：import_vocab.sh vocab.jsonl
     */
    String exportJsonl() throws Exception {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder out = new StringBuilder();
        List<Entry> list = all();
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            JSONObject o = new JSONObject();
            o.put("word", e.word);
            putIf(o, "sentence", e.sentence);
            putIf(o, "meaning", e.meaning);
            putIf(o, "phonetic", e.phonetic);
            putIf(o, "form", e.form);
            putIf(o, "note", e.note);
            String source = e.videoTitle == null || e.videoTitle.isEmpty() ? "YouTube" : "YouTube：" + e.videoTitle;
            String link = e.videoLink();
            if (link != null) source += " " + link;
            o.put("source", source);
            o.put("createdAt", iso.format(new Date(e.createdAt)));
            out.append(o).append('\n');
        }
        return out.toString();
    }

    private static void putIf(JSONObject o, String k, String v) throws Exception {
        if (v != null && !v.isEmpty()) o.put(k, v);
    }
}
