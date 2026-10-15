package com.wind.subword;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内嵌 m.youtube.com。字幕里的词点一下：视频暂停，弹出这个词在这句里的意思（AI），可以加入生词本；
 * 点别处收起，视频接着播放。简介、评论里长按选中文字，菜单里也有「AI 查词」
 */
public class MainActivity extends Activity {
    static final String EXTRA_OPEN_URL = "open_url";
    private static final String KEY_LAST_URL = "lastURL";

    private final Handler main = new Handler(Looper.getMainLooper());
    /** AI 请求：每个请求一个线程，换了词的旧请求不会挡住新的，整句翻译也不用等释义 */
    private final ExecutorService worker = Executors.newCachedThreadPool();
    /** 同一个词在同一句里查过的结果，这次运行内不再请求 */
    private final Map<String, String> meaningCache = new HashMap<>();

    private WebView web;
    private View topBar, scrim, card;
    private FrameLayout root, fullscreen;
    private ProgressBar progress;
    private TextView wordView, phoneticView, meaningView, formView, noteView, translationView, statusView;
    private TextView addButton, translateButton;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String script;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    /** 卡片上显示的这次查词 */
    private Lookup current;
    /** 透明层上按下的位置（透明层的坐标） */
    private float scrimX, scrimY;
    /** 每次打开、收起卡片加一；后台请求回来时对不上就丢掉 */
    private int generation;

    static final class Lookup {
        String word, sentence, previous, title, url;
        int time;
        float x, y, w, h, vw, vh;
        /** 选中的是一段话（不是单词或短语）：直接翻译 */
        boolean passage;
        Ai.Meaning meaning = new Ai.Meaning();
        boolean loading, pendingAdd;
        String error;

        static Lookup parse(String json) throws Exception {
            JSONObject o = new JSONObject(json);
            Lookup l = new Lookup();
            l.word = o.optString("word").trim();
            l.sentence = o.optString("sentence").trim();
            l.previous = o.optString("previous").trim();
            l.title = o.optString("title");
            l.url = o.optString("url");
            l.time = o.optInt("time");
            l.x = (float) o.optDouble("x");
            l.y = (float) o.optDouble("y");
            l.w = (float) o.optDouble("w");
            l.h = (float) o.optDouble("h");
            l.vw = (float) o.optDouble("vw");
            l.vh = (float) o.optDouble("vh");
            l.passage = l.word.split("\\s+").length > 4;
            return l;
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.root);
        topBar = findViewById(R.id.topBar);
        web = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        fullscreen = findViewById(R.id.fullscreen);
        scrim = findViewById(R.id.scrim);
        card = findViewById(R.id.card);
        wordView = findViewById(R.id.word);
        phoneticView = findViewById(R.id.phonetic);
        meaningView = findViewById(R.id.meaning);
        formView = findViewById(R.id.form);
        noteView = findViewById(R.id.note);
        translationView = findViewById(R.id.translation);
        statusView = findViewById(R.id.status);
        addButton = findViewById(R.id.add);
        translateButton = findViewById(R.id.translate);

        styleCard();
        script = readAsset("subword.js");

        findViewById(R.id.back).setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        findViewById(R.id.home).setOnClickListener(v -> web.loadUrl(Youtube.HOME));
        findViewById(R.id.vocab).setOnClickListener(v -> startActivity(new Intent(this, VocabActivity.class)));
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        scrim.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { scrimX = e.getX(); scrimY = e.getY(); }
            return false;
        });
        scrim.setOnClickListener(v -> tapScrim());
        addButton.setOnClickListener(v -> toggleVocab());
        translateButton.setOnClickListener(v -> translateSentence());
        findViewById(R.id.speak).setOnClickListener(v -> speak());

        setUpWebView();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                ttsReady = true;
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        String shared = urlFrom(getIntent());
        String last = prefs().getString(KEY_LAST_URL, null);
        web.loadUrl(shared != null ? shared : last != null ? last : Youtube.HOME);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setUpWebView() {
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 去掉 WebView 的标记（「; wv」「Version/4.0」），YouTube 当成普通的 Chrome 手机浏览器，给完整的手机版网页
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "SubWordBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                // 网页想跳去 YouTube App（intent://、vnd.youtube://）：不跳，留在这里
                return scheme == null || !(scheme.equals("http") || scheme.equals("https"));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                inject();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // YouTube 切换视频不重新加载网页，只改地址：记下来，下次打开接着看
                if (url != null && url.startsWith("http")) prefs().edit().putString(KEY_LAST_URL, url).apply();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                progress.setProgress(p);
                if (p >= 60) inject();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }
        });
    }

    private void inject() {
        if (script != null) web.evaluateJavascript(script, null);
    }

    // ---- 网页里的脚本调过来 ----

    private final class Bridge {
        @JavascriptInterface
        public void lookup(String json) {
            try {
                Lookup l = Lookup.parse(json);
                if (!l.word.isEmpty()) main.post(() -> showCard(l));
            } catch (Exception ignored) {
            }
        }
    }

    // ---- 释义卡片 ----

    private void showCard(Lookup l) {
        // 换了一个词：上一个词释义没生成完但点过「加入生词本」，存下已有的部分
        if (current != null && current.pendingAdd) {
            current.pendingAdd = false;
            addToVocab(current);
        }
        generation++;
        current = l;
        wordView.setText(l.passage ? "翻译" : l.word);
        phoneticView.setText("");
        meaningView.setText(l.passage ? l.word : "…");
        formView.setVisibility(View.GONE);
        noteView.setVisibility(View.GONE);
        translationView.setVisibility(View.GONE);
        translationView.setText("");
        translateButton.setVisibility(l.passage || l.sentence.isEmpty() ? View.GONE : View.VISIBLE);
        translateButton.setEnabled(true);
        translateButton.setAlpha(1f);
        addButton.setVisibility(l.passage ? View.GONE : View.VISIBLE);

        int count = l.passage ? 0 : VocabDb.get(this).recordLookup(l.word, l.sentence, l.url, l.time);
        VocabDb.Entry saved = l.passage ? null : VocabDb.get(this).find(l.word);
        statusView.setText(count > 1 ? "第 " + count + " 次查这个词" : "");
        statusView.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        updateAddButton(saved != null);

        scrim.setVisibility(View.VISIBLE);
        card.setVisibility(View.VISIBLE);
        positionCard(l);

        if (l.passage) {
            translationView.setVisibility(View.VISIBLE);
            translationView.setText("…");
            request(Ai.TRANSLATE_SYSTEM, Ai.translateUser(l.word, ""), 4000, text -> translationView.setText(text), null);
            return;
        }
        String cacheKey = VocabDb.key(l.word) + "|" + l.sentence;
        String cached = meaningCache.get(cacheKey);
        if (cached == null && saved != null && l.sentence.equals(saved.sentence) && saved.meaning != null) {
            l.meaning.phonetic = saved.phonetic;
            l.meaning.meaning = saved.meaning;
            l.meaning.form = saved.form;
            l.meaning.note = saved.note;
            showMeaning(l.meaning);
            return;
        }
        if (cached != null) {
            l.meaning = Ai.Meaning.parse(cached);
            showMeaning(l.meaning);
            return;
        }
        l.loading = true;
        request(Ai.MEANING_SYSTEM, Ai.meaningUser(l.word, l.sentence, l.previous), 4000, text -> {
            l.meaning = Ai.Meaning.parse(text);
            showMeaning(l.meaning);
        }, text -> {
            l.loading = false;
            meaningCache.put(cacheKey, text);
            if (l.pendingAdd) {
                l.pendingAdd = false;
                addToVocab(l);
            }
        });
    }

    private interface TextCallback { void accept(String text); }

    /** 后台请求 AI；onText 每收到一段就在主线程更新，onDone 全部收到后调用。卡片换了或收起了就丢掉结果 */
    private void request(String system, String user, int maxTokens, TextCallback onText, TextCallback onDone) {
        int gen = generation;
        Lookup l = current;
        worker.execute(() -> Ai.stream(this, system, user, maxTokens, new Ai.Listener() {
            @Override public void onText(String soFar) {
                main.post(() -> { if (gen == generation) onText.accept(soFar); });
            }
            @Override public void onDone(String text) {
                main.post(() -> {
                    if (gen != generation) return;
                    onText.accept(text);
                    if (onDone != null) onDone.accept(text);
                });
            }
            @Override public void onError(String message) {
                main.post(() -> {
                    if (gen != generation) return;
                    l.loading = false;
                    l.error = message;
                    // 出错信息放在释义（或翻译）的位置，底下「第几次查」照常显示
                    if (l.passage) translationView.setText(message);
                    else meaningView.setText(message);
                    if (l.pendingAdd) {
                        l.pendingAdd = false;
                        addToVocab(l);
                    }
                });
            }
        }, () -> gen != generation));
    }

    private void showMeaning(Ai.Meaning m) {
        phoneticView.setText(m.phonetic == null ? "" : m.phonetic);
        meaningView.setText(m.meaning == null ? "…" : m.meaning);
        formView.setVisibility(m.form == null ? View.GONE : View.VISIBLE);
        formView.setText(m.form == null ? "" : "原形：" + m.form);
        noteView.setVisibility(m.note == null ? View.GONE : View.VISIBLE);
        noteView.setText(m.note == null ? "" : "其他：" + m.note);
    }

    private void translateSentence() {
        Lookup l = current;
        if (l == null) return;
        translateButton.setEnabled(false);
        translateButton.setAlpha(0.4f);
        translationView.setVisibility(View.VISIBLE);
        translationView.setText("…");
        int gen = generation;
        worker.execute(() -> Ai.stream(this, Ai.TRANSLATE_SYSTEM,
                Ai.translateUser(l.sentence, l.previous), 4000, new Ai.Listener() {
                    @Override public void onText(String soFar) {
                        main.post(() -> { if (gen == generation) translationView.setText(soFar); });
                    }
                    @Override public void onDone(String text) { onText(text); }
                    @Override public void onError(String message) {
                        main.post(() -> { if (gen == generation) translationView.setText(message); });
                    }
                }, () -> gen != generation));
    }

    private void toggleVocab() {
        Lookup l = current;
        if (l == null) return;
        VocabDb db = VocabDb.get(this);
        if (db.find(l.word) != null) {
            db.remove(l.word);
            updateAddButton(false);
            return;
        }
        if (l.loading) {
            // 释义还没生成完：先标成已加入，生成完再存
            l.pendingAdd = true;
            updateAddButton(true);
            return;
        }
        addToVocab(l);
    }

    private void addToVocab(Lookup l) {
        VocabDb.Entry e = new VocabDb.Entry();
        e.word = l.word;
        e.phonetic = l.meaning.phonetic;
        e.meaning = l.meaning.meaning;
        e.form = l.meaning.form;
        e.note = l.meaning.note;
        e.sentence = l.sentence;
        e.videoUrl = l.url;
        e.videoTitle = l.title;
        e.videoTime = l.time;
        VocabDb.get(this).add(e);
        if (l == current) updateAddButton(true);
    }

    private void updateAddButton(boolean added) {
        addButton.setText(added ? "✓生词" : "＋生词");
    }

    private void speak() {
        Lookup l = current;
        if (l == null || !ttsReady) return;
        tts.speak(l.word, TextToSpeech.QUEUE_FLUSH, null, "word");
    }

    /** 释义开着时点了别处：点在字幕的另一个词上就查那个词（网页里调 lookup，视频保持暂停），否则收起 */
    private void tapScrim() {
        View page = customView != null ? fullscreen : web;
        int[] pageLoc = new int[2], scrimLoc = new int[2];
        page.getLocationInWindow(pageLoc);
        scrim.getLocationInWindow(scrimLoc);
        float fx = (scrimLoc[0] + scrimX - pageLoc[0]) / page.getWidth();
        float fy = (scrimLoc[1] + scrimY - pageLoc[1]) / page.getHeight();
        if (fx < 0 || fx > 1 || fy < 0 || fy > 1) { dismissCard(); return; }
        web.evaluateJavascript(String.format(Locale.US,
                "!!(window.__subwordTap && window.__subwordTap(%f, %f))", fx, fy), value -> {
            if (!"true".equals(value)) dismissCard();
        });
    }

    private void dismissCard() {
        if (card.getVisibility() != View.VISIBLE) return;
        generation++;
        Lookup l = current;
        // 释义没生成完就收起了，但点过「加入生词本」：存下已有的部分
        if (l != null && l.pendingAdd) {
            l.pendingAdd = false;
            addToVocab(l);
        }
        current = null;
        card.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
        if (tts != null) tts.stop();
        web.evaluateJavascript("window.__subwordResume && window.__subwordResume()", null);
    }

    /**
     * 悬浮窗贴在词的上方：底边对准词，释义逐字出来时往上长，不会盖住词。上方放不下时放在词的下方。
     * 左右以词为中心，不出屏幕
     */
    private void positionCard(Lookup l) {
        View page = customView != null ? fullscreen : web;
        int[] pageLoc = new int[2], rootLoc = new int[2];
        page.getLocationInWindow(pageLoc);
        root.getLocationInWindow(rootLoc);
        // 网页里的 CSS 像素换算到屏幕像素；位置换算到 root 的内容区（去掉 root 的内边距）
        float scale = l.vw > 0 ? page.getWidth() / l.vw : getResources().getDisplayMetrics().density;
        float offsetX = pageLoc[0] - rootLoc[0] - root.getPaddingLeft();
        float offsetY = pageLoc[1] - rootLoc[1] - root.getPaddingTop();
        float wordTop = offsetY + l.y * scale;
        float wordBottom = wordTop + l.h * scale;
        float wordCenterX = offsetX + (l.x + l.w / 2) * scale;

        float dp = getResources().getDisplayMetrics().density;
        int rootW = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int rootH = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        int cardW = (int) Math.min(rootW - 16 * dp, 300 * dp);
        int edge = (int) (8 * dp), gap = (int) (6 * dp);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(cardW, FrameLayout.LayoutParams.WRAP_CONTENT);
        p.leftMargin = (int) Math.max(edge, Math.min(wordCenterX - cardW / 2f, rootW - cardW - edge));
        // 上方至少要能放下标题行加两三行释义
        boolean above = wordTop - gap - edge >= 130 * dp || wordTop > rootH - wordBottom;
        if (above) {
            p.gravity = Gravity.BOTTOM | Gravity.START;
            p.bottomMargin = (int) Math.max(edge, rootH - wordTop + gap);
        } else {
            p.gravity = Gravity.TOP | Gravity.START;
            p.topMargin = (int) Math.max(edge, wordBottom + gap);
        }
        card.setLayoutParams(p);
    }

    private void styleCard() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? Color.rgb(40, 40, 44) : Color.WHITE);
        bg.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        card.setBackground(bg);
        int text = night ? Color.rgb(235, 235, 235) : Color.rgb(25, 25, 25);
        for (TextView t : new TextView[]{wordView, phoneticView, meaningView, formView, noteView, translationView, statusView}) {
            t.setTextColor(text);
        }
    }

    // ---- 长按选中文字（简介、评论）：菜单里加「AI 查词」 ----

    @Override
    public void onActionModeStarted(ActionMode mode) {
        super.onActionModeStarted(mode);
        if (mode.getType() != ActionMode.TYPE_FLOATING) return;
        Menu menu = mode.getMenu();
        // 悬浮菜单先排「总是显示」的项（复制、全选等），再排其他的；也设成总是显示、序号 0，排到复制前面
        MenuItem ai = menu.add(Menu.NONE, View.generateViewId(), 0, "AI 查词");
        ai.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        ai.setOnMenuItemClickListener(item -> {
            web.evaluateJavascript("window.__subwordSelection && window.__subwordSelection()", value -> {
                try {
                    if (value == null || value.equals("null")) return;
                    String json = new JSONArray("[" + value + "]").getString(0);
                    Lookup l = Lookup.parse(json);
                    if (!l.word.isEmpty()) showCard(l);
                } catch (Exception ignored) {
                }
            });
            mode.finish();
            return true;
        });
    }

    // ---- 全屏播放 ----

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (customView != null) { callback.onCustomViewHidden(); return; }
        customView = view;
        customViewCallback = callback;
        fullscreen.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        fullscreen.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.GONE);
        root.setFitsSystemWindows(false);
        root.setPadding(0, 0, 0, 0);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        dismissCard();
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        topBar.setVisibility(View.VISIBLE);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) c.show(WindowInsets.Type.systemBars());
        root.setFitsSystemWindows(true);
        root.requestApplyInsets();
        // 横屏全屏时 WebView 拿到了摄像头挖孔那一侧的边距，网页按它在左边留出安全区（safe-area-inset-left）。
        // 退出全屏后根布局把边距吃掉、不再往下传，这个值就一直留着，YouTube 左边空着一条。给它一份空的边距清掉
        web.dispatchApplyWindowInsets(WindowInsets.CONSUMED);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // ---- 返回键、外部打开 ----

    private void handleBack() {
        if (card.getVisibility() == View.VISIBLE) dismissCard();
        else if (customView != null) exitFullscreen();
        else if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = urlFrom(intent);
        if (url != null) {
            dismissCard();
            if (customView != null) exitFullscreen();
            web.loadUrl(url);
        }
    }

    private static String urlFrom(Intent intent) {
        if (intent == null) return null;
        String open = intent.getStringExtra(EXTRA_OPEN_URL);
        if (open != null) return open;
        if (Intent.ACTION_SEND.equals(intent.getAction())) return Youtube.fromShared(intent.getStringExtra(Intent.EXTRA_TEXT));
        return null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        worker.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("main", MODE_PRIVATE);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Toast.makeText(this, "读取脚本失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            return null;
        }
    }
}
