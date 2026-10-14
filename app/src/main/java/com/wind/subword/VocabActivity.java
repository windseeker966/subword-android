package com.wind.subword;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 生词本：点一条回到视频里查这个词的地方；长按删除；菜单里导出 */
public class VocabActivity extends Activity {
    private List<VocabDb.Entry> entries;
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ListView list = new ListView(this);
        list.setFitsSystemWindows(true);
        TextView empty = new TextView(this);
        empty.setText("还没有生词。看视频时点字幕里的词，再点「加入生词本」。");
        empty.setPadding(dp(24), dp(24), dp(24), dp(24));
        empty.setVisibility(View.GONE);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.addView(list);
        root.addView(empty);
        list.setEmptyView(empty);
        setContentView(root);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            String link = entries.get(position).videoLink();
            if (link == null) return;
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_OPEN_URL, link)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            VocabDb.Entry e = entries.get(position);
            new AlertDialog.Builder(this)
                    .setMessage("从生词本删除「" + e.word + "」？")
                    .setPositiveButton("删除", (d, w) -> {
                        VocabDb.get(this).remove(e.word);
                        reload();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        entries = VocabDb.get(this).all();
        setTitle("生词本（" + entries.size() + "）");
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("导出").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        export();
        return true;
    }

    /** 写到「下载/SubWord/」，格式见 VocabDb.exportJsonl */
    private void export() {
        try {
            String name = "vocab-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".jsonl";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/SubWord");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("无法创建文件");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(VocabDb.get(this).exportJsonl().getBytes(StandardCharsets.UTF_8));
            }
            new AlertDialog.Builder(this)
                    .setTitle("已导出 " + entries.size() + " 个词")
                    .setMessage("文件在「下载/SubWord/" + name + "」。\n\n导入 iPad 的 LearningPack 生词本：\nadb pull /sdcard/Download/SubWord/" + name + "\nPackFormat/import_vocab.sh " + name)
                    .setPositiveButton("好", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return entries == null ? 0 : entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return entries.get(i).id; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            TextView t = convert instanceof TextView ? (TextView) convert : new TextView(VocabActivity.this);
            t.setPadding(dp(16), dp(12), dp(16), dp(12));
            t.setTextSize(15);
            VocabDb.Entry e = entries.get(i);
            SpannableStringBuilder s = new SpannableStringBuilder();
            int start = s.length();
            s.append(e.word);
            s.setSpan(new StyleSpan(Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new android.text.style.RelativeSizeSpan(1.25f), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (e.phonetic != null) s.append("  ").append(e.phonetic);
            if (e.meaning != null) s.append("\n").append(e.meaning);
            if (e.form != null) s.append("\n原形：").append(e.form);
            if (e.sentence != null && !e.sentence.isEmpty()) {
                s.append("\n");
                int q = s.length();
                s.append(e.sentence);
                s.setSpan(new StyleSpan(Typeface.ITALIC), q, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (e.videoTitle != null && !e.videoTitle.isEmpty()) {
                s.append("\n");
                int q = s.length();
                s.append("▶ ").append(e.videoTitle).append("  ").append(clock(e.videoTime));
                s.setSpan(new android.text.style.ForegroundColorSpan(0xFF888888), q, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            t.setText(s);
            return t;
        }
    }

    private static String clock(int seconds) {
        int h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
