package com.wind.subword;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 调 AI：Anthropic Messages 或 OpenAI 兼容（Chat Completions）接口，流式返回。设置和 iPad 版 LearningPack 一样 */
final class Ai {
    static final String PROTO_ANTHROPIC = "anthropic";
    static final String PROTO_OPENAI = "openai";

    static final String KEY_PROTO = "proto";
    static final String KEY_BASE = "baseURL";
    static final String KEY_MODEL = "model";
    static final String KEY_API_KEY = "apiKey";

    static final String DEFAULT_PROTO = PROTO_ANTHROPIC;
    static final String DEFAULT_BASE = "https://api.deepseek.com/anthropic";
    static final String DEFAULT_MODEL = "deepseek-flash";

    interface Listener {
        /** 目前收到的全部文字（主线程以外调用） */
        void onText(String soFar);
        void onDone(String text);
        void onError(String message);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ai", Context.MODE_PRIVATE);
    }

    static boolean configured(Context c) {
        return !prefs(c).getString(KEY_API_KEY, "").trim().isEmpty();
    }

    // ---- 提示词（改自 LearningPack 的 VocabAI） ----

    /** 单词或短语：只讲它在这句字幕里的意思 */
    static final String MEANING_SYSTEM =
            "你是英语老师。学生在准备考研，也在看英文 YouTube 视频练听力；看的时候点了字幕里的一个单词或划选了一个短语，请给出它在这句话里的意思，不要翻译整句。\n"
            + "字幕可能是自动生成的：没有标点、大小写不规范，偶尔有听错的词。\n"
            + "只输出下面几行，不要其他文字：\n"
            + "音标：划选内容（按原文中的形式）的 DJ 音标（英式，Daniel Jones 体系），用两条斜线包住，如「/ˈmuːlə/」；短语写整个短语，如「/kɪk ˈbæk/」\n"
            + "意思：词性和在这句里的中文意思，如「n. 钱（俚语）」「phr. 放松，休息」；是熟词僻义时在末尾加「（熟词僻义）」；这个词和后面一两个词组成固定搭配时，写出搭配和它的意思，如「phr. figure out 弄明白」\n"
            + "原形：划选的不是原形时写原形、原形的 DJ 音标和形态，如「draw /drɔː/（draws 为第三人称单数）」。划选的就是原形时省略这一行\n"
            + "其他：最常见的其他一两个意思；字幕明显听错了时写出应该是哪个词。没有值得写的就省略这一行";

    static String meaningUser(String word, String sentence, String previous) {
        StringBuilder s = new StringBuilder();
        s.append("划选：").append(word).append("\n原句：").append(sentence);
        if (previous != null && !previous.isEmpty()) s.append("\n前一句字幕：").append(previous);
        return s.toString();
    }

    /** 整句翻译（只在点了「翻译整句」时请求） */
    static final String TRANSLATE_SYSTEM =
            "你是英语老师。学生在看英文 YouTube 视频，请把这句字幕翻译成准确、通顺的中文。字幕可能是自动生成的，没有标点、偶尔有听错的词，按最合理的理解翻译。"
            + "有值得注意的口语表达或句子结构时，另起一行简短点出。简洁，不要寒暄，不超过 100 字。";

    static String translateUser(String sentence, String previous) {
        String s = "字幕：" + sentence;
        if (previous != null && !previous.isEmpty()) s += "\n前一句字幕：" + previous;
        return s;
    }

    /** 「音标 / 意思 / 原形 / 其他」几行；生成中途也能解析已有的部分 */
    static final class Meaning {
        String phonetic, meaning, form, note;

        static Meaning parse(String text) {
            Meaning m = new Meaning();
            for (String raw : text.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("音标：")) m.phonetic = value(line, "音标：");
                else if (line.startsWith("意思：")) m.meaning = value(line, "意思：");
                else if (line.startsWith("原形：")) m.form = value(line, "原形：");
                else if (line.startsWith("其他：")) m.note = value(line, "其他：");
                else if (m.meaning == null && !line.isEmpty()) m.meaning = line;
            }
            return m;
        }

        private static String value(String line, String prefix) {
            String v = line.substring(prefix.length()).trim();
            return v.isEmpty() || v.equals("无") ? null : v;
        }
    }

    // ---- 请求 ----

    /** 在当前线程里同步请求（调用方放到后台线程）。cancelled 返回 true 时尽早停下 */
    static void stream(Context c, String system, String user, int maxTokens, Listener l, java.util.function.BooleanSupplier cancelled) {
        // 查词要快：关掉思考（deepseek-flash 默认先思考，慢一倍，思考还计在 max_tokens 里）。
        // 不认识 thinking 参数的接口（返回 400）去掉它再请求一次
        if (!stream(c, system, user, maxTokens, true, l, cancelled)) stream(c, system, user, maxTokens, false, l, cancelled);
    }

    /** 返回 false：接口不接受关掉思考的参数，还没有通知 Listener */
    private static boolean stream(Context c, String system, String user, int maxTokens, boolean noThinking,
                                  Listener l, java.util.function.BooleanSupplier cancelled) {
        SharedPreferences p = prefs(c);
        String proto = p.getString(KEY_PROTO, DEFAULT_PROTO);
        String base = p.getString(KEY_BASE, DEFAULT_BASE).trim();
        String model = p.getString(KEY_MODEL, DEFAULT_MODEL).trim();
        String key = p.getString(KEY_API_KEY, "").trim();
        if (key.isEmpty()) { l.onError("还没有设置 API Key：右上角「设置」里填写"); return true; }
        boolean anthropic = PROTO_ANTHROPIC.equals(proto);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint(base, anthropic));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", maxTokens);
            body.put("stream", true);
            JSONArray messages = new JSONArray();
            if (anthropic) {
                conn.setRequestProperty("x-api-key", key);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
                body.put("system", system);
                if (noThinking) body.put("thinking", new JSONObject().put("type", "disabled"));
            } else {
                conn.setRequestProperty("Authorization", "Bearer " + key);
                messages.put(new JSONObject().put("role", "system").put("content", system));
            }
            messages.put(new JSONObject().put("role", "user").put("content", user));
            body.put("messages", messages);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                String error = readAll(conn.getErrorStream());
                if (anthropic && noThinking && code == 400 && error.contains("thinking")) return false;
                l.onError("请求失败（HTTP " + code + "）：" + shorten(error));
                return true;
            }
            String stop = null;
            StringBuilder text = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (cancelled.getAsBoolean()) return true;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) continue;
                    JSONObject event = new JSONObject(data);
                    String reason = stopReason(event, anthropic);
                    if (reason != null) stop = reason;
                    String delta = delta(event, anthropic);
                    if (delta != null && !delta.isEmpty()) {
                        text.append(delta);
                        l.onText(text.toString());
                    }
                }
            }
            if (text.length() == 0) {
                l.onError("AI 没有返回内容" + (stop == null ? "" : "（结束原因：" + stop + "）"));
            } else {
                l.onDone(text.toString());
            }
        } catch (Exception e) {
            if (!cancelled.getAsBoolean()) l.onError("请求失败：" + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return true;
    }

    private static String stopReason(JSONObject o, boolean anthropic) {
        if (anthropic) {
            JSONObject d = o.optJSONObject("delta");
            return "message_delta".equals(o.optString("type")) && d != null && !d.isNull("stop_reason") ? d.optString("stop_reason") : null;
        }
        JSONArray choices = o.optJSONArray("choices");
        JSONObject first = choices == null ? null : choices.optJSONObject(0);
        return first == null || first.isNull("finish_reason") ? null : first.optString("finish_reason");
    }

    private static String delta(JSONObject o, boolean anthropic) {
        if (anthropic) {
            if ("error".equals(o.optString("type"))) throw new IllegalStateException(o.optJSONObject("error") + "");
            if (!"content_block_delta".equals(o.optString("type"))) return null;
            JSONObject d = o.optJSONObject("delta");
            return d != null && "text_delta".equals(d.optString("type")) ? d.optString("text") : null;
        }
        JSONArray choices = o.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return null;
        JSONObject d = choices.optJSONObject(0).optJSONObject("delta");
        return d == null || d.isNull("content") ? null : d.optString("content");
    }

    /** 和 LearningPack 的 LLMEndpoint.url 一样：裸地址按 /v1 处理，已经是完整接口地址的不动 */
    static String endpoint(String base, boolean anthropic) {
        String b = base;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        URL u;
        try { u = new URL(b); } catch (Exception e) { return b; }
        String path = u.getPath();
        if (anthropic) {
            if (!path.endsWith("/messages")) b += path.endsWith("/v1") ? "/messages" : "/v1/messages";
        } else if (!path.endsWith("/chat/completions")) {
            b += (path.isEmpty() ? "/v1" : "") + "/chat/completions";
        }
        return b;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder s = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) s.append(line).append('\n');
            return s.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String shorten(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private Ai() {}
}
