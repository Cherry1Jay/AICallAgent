package com.cherryjay.aicallagent;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 模块配置。所有字段都从 SharedPreferences 读，Hook 端与设置页共用。
 */
public final class Config {

    static final String PREFS = "aicall_agent";

    public static final String K_ENABLED = "enabled";
    public static final String K_BASE_URL = "base_url";
    public static final String K_API_KEY = "api_key";
    public static final String K_MODEL = "model";
    public static final String K_SYSTEM_PROMPT = "system_prompt";
    public static final String K_TIMEOUT = "timeout";
    public static final String K_TEMPERATURE = "temperature";
    public static final String K_STRIP_THINKING = "strip_thinking";
    public static final String K_MAX_HISTORY = "max_history";
    public static final String K_FALLBACK = "fallback";
    public static final String K_DEBUG = "debug";

    // 按来电类型分流：true = 用我们的模型，false = 走小米原生
    public static final String K_USE_TAKEOUT = "use_takeout";
    public static final String K_USE_DELIVERY = "use_delivery";
    public static final String K_USE_SPAM = "use_spam";
    public static final String K_USE_SERVICE = "use_service";
    public static final String K_USE_CONTACT = "use_contact";
    public static final String K_USE_UNKNOWN = "use_unknown";

    // 通话中手动点「小爱帮我回」时的接管策略
    public static final String K_MANUAL_TAKEOVER = "manual_takeover";
    public static final String K_MANUAL_IGNORE_CATEGORY = "manual_ignore_category";
    // 开场白（对应小爱自己的 ai_custom_opening_* 配置键，留空则用系统默认）
    public static final String K_OPENING_ACQ = "opening_acquaintance";
    public static final String K_OPENING_STR = "opening_stranger";

    public static final String DEF_BASE_URL = "https://api.deepseek.com/v1";
    public static final String DEF_MODEL = "deepseek-chat";
    public static final String DEF_SYSTEM_PROMPT =
            "你正在代替机主接听一通电话。你在用语音和对方实时对话，所以："
            + "1) 每次只说一到两句、不超过40个字；2) 口语化，不用书面语，不要用列表、markdown、表情符号；"
            + "3) 如果对方问你是不是AI，如实说明你是机主的AI助手；"
            + "4) 涉及转账、验证码、密码、银行卡、借钱、点击链接等敏感请求，一律礼貌拒绝，并告知会转告机主；"
            + "5) 不清楚的事情不要编造，可以说\"我记一下，稍后让机主回你\"。";

    public boolean enabled = true;
    public String baseUrl = DEF_BASE_URL;
    public String apiKey = "";
    public String model = DEF_MODEL;
    public String systemPrompt = DEF_SYSTEM_PROMPT;
    public int timeoutSec = 12;
    /** 兜底：我们的模型失败时，是否回落到小爱原生回复 */
    public boolean fallback = true;
    /** 是否过滤掉模型输出的括号思考内容 */
    public boolean stripThinking = true;
    /** 保留多少轮对话历史 */
    public int maxHistory = 12;
    public float temperature = 0.7f;
    public boolean debug = true;

    /** 外卖送餐类：默认交给大模型（能应对"放门口/改时间"等灵活沟通） */
    public boolean useForTakeout = true;
    /** 快递物流类 */
    public boolean useForDelivery = true;
    /** 广告推销/骚扰/中介：默认走原生，小米的话术更"标准化"，也省 token */
    public boolean useForSpam = false;
    /** 客服/官方号码 */
    public boolean useForService = true;
    /** 通讯录里的联系人 */
    public boolean useForContact = true;
    /** 完全陌生的号码 */
    public boolean useForUnknown = true;

    /** 通话中点右上角「AI 应答 → 小爱帮我回」时，是否用我们的大模型接管 */
    public boolean manualTakeover = true;
    /** 手动接管时忽略上面的分类开关：用户主动点了按钮，就是要 AI 接，不该被类型规则挡掉 */
    public boolean manualIgnoreCategory = true;
    /** 开场白：熟人 / 陌生号。空字符串 = 用系统默认 */
    public String openingAcquaintance = "";
    public String openingStranger = "";

    /** 各类型的场景提示词，key 为 Category 常量；空则用 Prompts 的默认值 */
    public final java.util.Map<String, String> prompts = new java.util.HashMap<>();

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Config load(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        Config c = new Config();
        c.enabled = sp.getBoolean(K_ENABLED, true);
        c.baseUrl = sp.getString(K_BASE_URL, DEF_BASE_URL);
        c.apiKey = sp.getString(K_API_KEY, "");
        c.model = sp.getString(K_MODEL, DEF_MODEL);
        c.systemPrompt = sp.getString(K_SYSTEM_PROMPT, DEF_SYSTEM_PROMPT);
        c.timeoutSec = sp.getInt(K_TIMEOUT, 12);
        c.temperature = sp.getFloat(K_TEMPERATURE, 0.7f);
        c.stripThinking = sp.getBoolean(K_STRIP_THINKING, true);
        c.maxHistory = sp.getInt(K_MAX_HISTORY, 12);
        c.fallback = sp.getBoolean(K_FALLBACK, true);
        c.debug = sp.getBoolean(K_DEBUG, true);
        c.useForTakeout = sp.getBoolean(K_USE_TAKEOUT, true);
        c.useForDelivery = sp.getBoolean(K_USE_DELIVERY, true);
        c.useForSpam = sp.getBoolean(K_USE_SPAM, false);
        c.useForService = sp.getBoolean(K_USE_SERVICE, true);
        c.useForContact = sp.getBoolean(K_USE_CONTACT, true);
        c.useForUnknown = sp.getBoolean(K_USE_UNKNOWN, true);
        c.manualTakeover = sp.getBoolean(K_MANUAL_TAKEOVER, true);
        c.manualIgnoreCategory = sp.getBoolean(K_MANUAL_IGNORE_CATEGORY, true);
        c.openingAcquaintance = sp.getString(K_OPENING_ACQ, "");
        c.openingStranger = sp.getString(K_OPENING_STR, "");
        for (String cat : new String[]{Category.TAKEOUT, Category.DELIVERY, Category.SPAM,
                Category.SERVICE, Category.CONTACT, Category.UNKNOWN}) {
            c.prompts.put(cat, sp.getString(Prompts.prefKey(cat), Prompts.defaultFor(cat)));
        }
        return c;
    }

    public void save(Context ctx) {
        SharedPreferences.Editor e = prefs(ctx).edit()
                .putBoolean(K_ENABLED, enabled)
                .putString(K_BASE_URL, baseUrl)
                .putString(K_API_KEY, apiKey)
                .putString(K_MODEL, model)
                .putString(K_SYSTEM_PROMPT, systemPrompt)
                .putInt(K_TIMEOUT, timeoutSec)
                .putFloat(K_TEMPERATURE, temperature)
                .putBoolean(K_STRIP_THINKING, stripThinking)
                .putInt(K_MAX_HISTORY, maxHistory)
                .putBoolean(K_FALLBACK, fallback)
                .putBoolean(K_DEBUG, debug)
                .putBoolean(K_USE_TAKEOUT, useForTakeout)
                .putBoolean(K_USE_DELIVERY, useForDelivery)
                .putBoolean(K_USE_SPAM, useForSpam)
                .putBoolean(K_USE_SERVICE, useForService)
                .putBoolean(K_USE_CONTACT, useForContact)
                .putBoolean(K_USE_UNKNOWN, useForUnknown)
                .putBoolean(K_MANUAL_TAKEOVER, manualTakeover)
                .putBoolean(K_MANUAL_IGNORE_CATEGORY, manualIgnoreCategory)
                .putString(K_OPENING_ACQ, openingAcquaintance)
                .putString(K_OPENING_STR, openingStranger);
        for (java.util.Map.Entry<String, String> en : prompts.entrySet()) {
            e.putString(Prompts.prefKey(en.getKey()), en.getValue());
        }
        e.apply();
    }

    /** 这类来电是否交给大模型处理 */
    public boolean shouldHandle(String category) {
        if (Category.TAKEOUT.equals(category)) {
            return useForTakeout;
        }
        if (Category.DELIVERY.equals(category)) {
            return useForDelivery;
        }
        if (Category.SPAM.equals(category)) {
            return useForSpam;
        }
        if (Category.SERVICE.equals(category)) {
            return useForService;
        }
        if (Category.CONTACT.equals(category)) {
            return useForContact;
        }
        return useForUnknown;
    }

    /** 针对不同场景拼出最终提示词：全局人设 + 该类型的场景说明 */
    public String promptFor(String category) {
        String scene = prompts.get(category);
        if (scene == null || scene.trim().isEmpty()) {
            scene = Prompts.defaultFor(category);
        }
        return systemPrompt + "\n\n【当前场景：" + Category.label(category) + "】\n" + scene;
    }

    /** 某类型当前生效的提示词（用于设置页展示） */
    public String promptOf(String category) {
        String scene = prompts.get(category);
        return (scene == null || scene.trim().isEmpty()) ? Prompts.defaultFor(category) : scene;
    }

    public boolean isPromptCustom(String category) {
        String scene = prompts.get(category);
        return scene != null && !scene.trim().isEmpty()
                && !scene.trim().equals(Prompts.defaultFor(category).trim());
    }

    public String endpoint() {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) {
            b = DEF_BASE_URL;
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith("/chat/completions")) {
            return b;
        }
        return b + "/chat/completions";
    }
}
