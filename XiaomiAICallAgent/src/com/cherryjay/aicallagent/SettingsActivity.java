package com.cherryjay.aicallagent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;

public class SettingsActivity extends Activity {

    private Switch swEnabled;
    private Switch swFallback;
    private Switch swAiReply;
    private TextView tvAiReplyState;
    private EditText etBaseUrl;
    private EditText etApiKey;
    private EditText etModel;
    private EditText etPrompt;
    private EditText etTimeout;
    private EditText etHistory;
    private TextView tvStatus;

    private Switch swTakeout;
    private Switch swDelivery;
    private Switch swSpam;
    private Switch swService;
    private Switch swContact;
    private Switch swUnknown;

    private TextView tvTakeout;
    private TextView tvDelivery;
    private TextView tvSpam;
    private TextView tvService;
    private TextView tvContact;
    private TextView tvUnknown;

    private Config mCfg;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** 与小爱进程里的 Hook 通讯 */
    private static final String ACTION_CONTROL = "com.cherryjay.aicallagent.CONTROL";
    private static final String ACTION_STATE = "com.cherryjay.aicallagent.STATE";
    private static final String TARGET_PKG = "com.miui.voiceassist";

    private BroadcastReceiver stateReceiver;
    private boolean awaitingReply;

    private static final String[][] CATS = {
            {Category.TAKEOUT, "外卖送餐"},
            {Category.DELIVERY, "快递物流"},
            {Category.SPAM, "广告推销"},
            {Category.SERVICE, "客服官方"},
            {Category.CONTACT, "通讯录联系人"},
            {Category.UNKNOWN, "陌生号码"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        swEnabled = findViewById(R.id.sw_enabled);
        swFallback = findViewById(R.id.sw_fallback);
        etBaseUrl = findViewById(R.id.et_base_url);
        etApiKey = findViewById(R.id.et_api_key);
        etModel = findViewById(R.id.et_model);
        etPrompt = findViewById(R.id.et_prompt);
        etTimeout = findViewById(R.id.et_timeout);
        etHistory = findViewById(R.id.et_history);
        tvStatus = findViewById(R.id.tv_status);

        swTakeout = findViewById(R.id.sw_takeout);
        swDelivery = findViewById(R.id.sw_delivery);
        swSpam = findViewById(R.id.sw_spam);
        swService = findViewById(R.id.sw_service);
        swContact = findViewById(R.id.sw_contact);
        swUnknown = findViewById(R.id.sw_unknown);

        tvTakeout = findViewById(R.id.tv_takeout);
        tvDelivery = findViewById(R.id.tv_delivery);
        tvSpam = findViewById(R.id.tv_spam);
        tvService = findViewById(R.id.tv_service);
        tvContact = findViewById(R.id.tv_contact);
        tvUnknown = findViewById(R.id.tv_unknown);

        mCfg = Config.load(this);
        bind(mCfg);
        bindPromptEntries();

        findViewById(R.id.btn_save).setOnClickListener(v -> {
            mCfg = collect();
            mCfg.save(this);
            Toast.makeText(this, "已保存，下一通电话生效", Toast.LENGTH_SHORT).show();
            setStatus("已保存 · " + currentSummary(), false);
        });

        findViewById(R.id.btn_test).setOnClickListener(this::onTest);

        findViewById(R.id.btn_log).setOnClickListener(v ->
                startActivity(new Intent(this, LogActivity.class)));

        swAiReply = findViewById(R.id.sw_ai_reply);
        tvAiReplyState = findViewById(R.id.tv_ai_reply_state);
        bindAiReplySwitch();
        registerStateReceiver();

        setStatus("当前：" + currentSummary(), false);
    }

    // ------------------------------------------------------- 通话中开关

    /** 开关本身不做配置持久化，只是给 Hook 发一条指令；真实状态由通话进程回传 */
    private void bindAiReplySwitch() {
        swAiReply.setChecked(Config.prefs(this).getBoolean("ui_ai_reply", true));
        swAiReply.setOnCheckedChangeListener((btn, checked) -> sendAiReply(checked));
    }

    private void sendAiReply(boolean on) {
        Config.prefs(this).edit().putBoolean("ui_ai_reply", on).apply();

        Intent i = new Intent(ACTION_CONTROL);
        i.setPackage(TARGET_PKG);
        i.putExtra("cmd", "ai_reply");
        i.putExtra("on", on);
        try {
            sendBroadcast(i);
        } catch (Throwable t) {
            tvAiReplyState.setText("状态：发送失败 —— " + t.getMessage());
            return;
        }

        awaitingReply = true;
        tvAiReplyState.setText("状态：已发送「" + (on ? "继续应答" : "暂停") + "」，等待小爱响应…");
        main.postDelayed(() -> {
            if (awaitingReply) {
                awaitingReply = false;
                tvAiReplyState.setText("状态：小爱没有响应。确认 LSPosed 里已启用本模块，并重启过小爱进程。");
            }
        }, 2500);
    }

    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                awaitingReply = false;
                boolean inCall = intent.getBooleanExtra("inCall", false);
                boolean paused = intent.getBooleanExtra("paused", true);
                boolean acted = intent.getBooleanExtra("acted", false);

                String s;
                if (!inCall) {
                    s = "状态：当前没有通话。开关会在下一通 AI 接听的通话里生效。";
                } else if (!acted) {
                    s = "状态：在通话中，但没能驱动引擎 —— 这通电话可能不是 AI 接听的。";
                } else {
                    s = "状态：通话中 · 小爱" + (paused ? "已暂停，把话头交给你" : "正在自动应答");
                }
                tvAiReplyState.setText(s);
            }
        };
        IntentFilter f = new IntentFilter(ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
    }

    @Override
    protected void onDestroy() {
        if (stateReceiver != null) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            stateReceiver = null;
        }
        super.onDestroy();
    }

    private void bindPromptEntries() {
        TextView[] views = {tvTakeout, tvDelivery, tvSpam, tvService, tvContact, tvUnknown};
        for (int i = 0; i < CATS.length; i++) {
            final String cat = CATS[i][0];
            final String label = CATS[i][1];
            views[i].setOnClickListener(v -> editPrompt(cat, label));
        }
        refreshPromptMarks();
    }

    private void refreshPromptMarks() {
        TextView[] views = {tvTakeout, tvDelivery, tvSpam, tvService, tvContact, tvUnknown};
        for (int i = 0; i < CATS.length; i++) {
            String label = CATS[i][1];
            boolean custom = mCfg != null && mCfg.isPromptCustom(CATS[i][0]);
            views[i].setText(custom ? label + " · 已改" : label);
        }
    }

    private void editPrompt(String category, String label) {
        float d = getResources().getDisplayMetrics().density;

        EditText et = new EditText(this);
        et.setText(mCfg.promptOf(category));
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setMinLines(9);
        et.setTextSize(13);
        et.setLineSpacing(6f, 1f);
        et.setBackground(null);

        TextView hint = new TextView(this);
        hint.setText("会拼在全局人设后面作为【当前场景】。留空则使用默认话术。");
        hint.setTextSize(11);
        hint.setTextColor(0xFF6B7280);
        hint.setPadding(0, (int) (8 * d), 0, 0);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, (int) (10 * d), pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle(label + " 的提示词")
                .setView(box)
                .setPositiveButton("保存", (dlg, w) -> {
                    mCfg.prompts.put(category, et.getText().toString().trim());
                    mCfg = collect();
                    mCfg.save(this);
                    refreshPromptMarks();
                    Toast.makeText(this, label + " 提示词已保存", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("恢复默认", (dlg, w) -> {
                    mCfg.prompts.put(category, Prompts.defaultFor(category));
                    mCfg = collect();
                    mCfg.save(this);
                    refreshPromptMarks();
                    Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void bind(Config c) {
        swEnabled.setChecked(c.enabled);
        swFallback.setChecked(c.fallback);
        etBaseUrl.setText(c.baseUrl);
        etApiKey.setText(c.apiKey);
        etModel.setText(c.model);
        etPrompt.setText(c.systemPrompt);
        etTimeout.setText(String.valueOf(c.timeoutSec));
        etHistory.setText(String.valueOf(c.maxHistory));

        swTakeout.setChecked(c.useForTakeout);
        swDelivery.setChecked(c.useForDelivery);
        swSpam.setChecked(c.useForSpam);
        swService.setChecked(c.useForService);
        swContact.setChecked(c.useForContact);
        swUnknown.setChecked(c.useForUnknown);
    }

    private Config collect() {
        Config c = new Config();
        c.enabled = swEnabled.isChecked();
        c.fallback = swFallback.isChecked();
        c.baseUrl = etBaseUrl.getText().toString().trim();
        c.apiKey = etApiKey.getText().toString().trim();
        c.model = etModel.getText().toString().trim();
        c.systemPrompt = etPrompt.getText().toString().trim();
        c.timeoutSec = parseInt(etTimeout.getText().toString(), 12);
        c.maxHistory = parseInt(etHistory.getText().toString(), 12);
        c.temperature = 0.7f;
        c.stripThinking = true;
        c.debug = true;
        c.useForTakeout = swTakeout.isChecked();
        c.useForDelivery = swDelivery.isChecked();
        c.useForSpam = swSpam.isChecked();
        c.useForService = swService.isChecked();
        c.useForContact = swContact.isChecked();
        c.useForUnknown = swUnknown.isChecked();
        if (mCfg != null) {
            c.prompts.putAll(mCfg.prompts);
        }
        if (TextUtils.isEmpty(c.baseUrl)) {
            c.baseUrl = Config.DEF_BASE_URL;
        }
        if (TextUtils.isEmpty(c.model)) {
            c.model = Config.DEF_MODEL;
        }
        if (TextUtils.isEmpty(c.systemPrompt)) {
            c.systemPrompt = Config.DEF_SYSTEM_PROMPT;
        }
        return c;
    }

    private String currentSummary() {
        Config c = collect();
        String keyState = TextUtils.isEmpty(c.apiKey) ? "未填 Key" : "Key 已填";
        return (c.enabled ? "接管开启" : "接管关闭") + " · " + c.model + " · " + keyState;
    }

    private void onTest(View v) {
        Config c = collect();
        if (TextUtils.isEmpty(c.apiKey)) {
            setStatus("请先填写 API Key", true);
            return;
        }
        v.setEnabled(false);
        setStatus("正在测试 " + c.model + " ...", false);
        new Thread(() -> {
            String result;
            boolean ok;
            long t0 = System.currentTimeMillis();
            try {
                String reply = LlmClient.chat(c, Collections.emptyList(), "你好，请用一句话回复我，确认链路正常。");
                long cost = System.currentTimeMillis() - t0;
                if (reply == null) {
                    ok = false;
                    result = "返回为空";
                } else {
                    ok = true;
                    result = "连通正常（" + cost + "ms）\n模型回复：" + reply;
                }
            } catch (Throwable t) {
                ok = false;
                result = "失败：" + t.getMessage();
            }
            final boolean fok = ok;
            final String fres = result;
            main.post(() -> {
                v.setEnabled(true);
                setStatus(fres, !fok);
            });
        }).start();
    }

    private void setStatus(String text, boolean error) {
        tvStatus.setText(text);
        tvStatus.setTextColor(error ? 0xFFD33A2C : 0xFF6B7280);
        tvStatus.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
