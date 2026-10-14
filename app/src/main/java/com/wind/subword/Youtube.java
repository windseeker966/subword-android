package com.wind.subword;

import android.net.Uri;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** YouTube 地址：取视频 ID、拼带时间点的链接、把分享来的文字转成可以打开的地址 */
final class Youtube {
    static final String HOME = "https://m.youtube.com/";

    private static final Pattern ID = Pattern.compile(
            "(?:youtu\\.be/|[?&]v=|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");

    static String videoId(String url) {
        if (url == null) return null;
        Matcher m = ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** 视频在某个时间点的地址；不是视频页的原样返回 */
    static String linkAt(String url, int seconds) {
        String id = videoId(url);
        if (id == null) return url;
        return "https://m.youtube.com/watch?v=" + id + (seconds > 0 ? "&t=" + seconds + "s" : "");
    }

    /** 分享来的文字里找出第一个链接；是 YouTube 视频的换成手机版地址 */
    static String fromShared(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        if (!m.find()) return null;
        String url = m.group();
        String id = videoId(url);
        if (id == null) return url;
        String t = Uri.parse(url).getQueryParameter("t");
        String link = "https://m.youtube.com/watch?v=" + id;
        return t == null ? link : link + "&t=" + t;
    }

    private Youtube() {}
}
