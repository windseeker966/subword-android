package com.wind.subword;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** AI 设置：接口类型、地址、模型、API Key；可以测试一下 */
public class SettingsActivity extends Activity {
    private RadioButton anthropic, openai;
    private EditText base, model, key;
    private TextView result;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = Ai.prefs(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);

        label(box, "接口");
        RadioGroup group = new RadioGroup(this);
        anthropic = new RadioButton(this);
        anthropic.setText("Anthropic Messages API");
        anthropic.setId(android.view.View.generateViewId());
        openai = new RadioButton(this);
        openai.setText("OpenAI 兼容（Chat Completions）");
        openai.setId(android.view.View.generateViewId());
        group.addView(anthropic);
        group.addView(openai);
        box.addView(group);
        boolean isOpenAI = Ai.PROTO_OPENAI.equals(p.getString(Ai.KEY_PROTO, Ai.DEFAULT_PROTO));
        (isOpenAI ? openai : anthropic).setChecked(true);

        label(box, "地址");
        base = field(box, p.getString(Ai.KEY_BASE, Ai.DEFAULT_BASE), InputType.TYPE_TEXT_VARIATION_URI);
        label(box, "模型");
        model = field(box, p.getString(Ai.KEY_MODEL, Ai.DEFAULT_MODEL), InputType.TYPE_TEXT_VARIATION_NORMAL);
        label(box, "API Key");
        key = field(box, p.getString(Ai.KEY_API_KEY, ""), InputType.TYPE_TEXT_VARIATION_PASSWORD);

        TextView hint = new TextView(this);
        hint.setText("默认用 DeepSeek（和 iPad 上的 LearningPack 一样）。Key 只存在这台手机上。");
        hint.setAlpha(0.6f);
        hint.setPadding(0, dp(8), 0, dp(8));
        box.addView(hint);

        LinearLayout buttons = new LinearLayout(this);
        Button save = new Button(this);
        save.setText("保存");
        save.setOnClickListener(v -> { save(); finish(); });
        Button test = new Button(this);
        test.setText("保存并测试");
        test.setOnClickListener(v -> { save(); test(); });
        buttons.addView(save);
        buttons.addView(test);
        box.addView(buttons);

        result = new TextView(this);
        result.setPadding(0, dp(12), 0, 0);
        result.setTextIsSelectable(true);
        box.addView(result);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(box);
        setContentView(scroll);
    }

    private void save() {
        Ai.prefs(this).edit()
                .putString(Ai.KEY_PROTO, openai.isChecked() ? Ai.PROTO_OPENAI : Ai.PROTO_ANTHROPIC)
                .putString(Ai.KEY_BASE, base.getText().toString().trim())
                .putString(Ai.KEY_MODEL, model.getText().toString().trim())
                .putString(Ai.KEY_API_KEY, key.getText().toString().trim())
                .apply();
    }

    private void test() {
        result.setText("测试中…");
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> Ai.stream(this, Ai.MEANING_SYSTEM,
                Ai.meaningUser("figure", "i still can't figure out why he left", ""), 300, new Ai.Listener() {
                    @Override public void onText(String soFar) { main.post(() -> result.setText(soFar)); }
                    @Override public void onDone(String text) { main.post(() -> result.setText("成功：\n" + text)); }
                    @Override public void onError(String message) { main.post(() -> result.setText(message)); }
                }, () -> isDestroyed())).start();
    }

    private void label(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(12), 0, dp(4));
        t.setAlpha(0.7f);
        box.addView(t);
    }

    private EditText field(LinearLayout box, String value, int variation) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        box.addView(e);
        return e;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
