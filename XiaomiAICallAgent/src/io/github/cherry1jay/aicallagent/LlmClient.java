package io.github.cherry1jay.aicallagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OpenAI 兼容的 /chat/completions 客户端。无第三方依赖。
 */
public final class LlmClient {

    public static final class Msg {
        final String role;
        final String content;

        Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private LlmClient() {
    }

    /** 最近一次调用的原始情况，供日志排查用 */
    static volatile String lastDebug = "";

    public static String chat(Config cfg, List<Msg> history, String userText) throws Exception {
        return chat(cfg, history, userText, null);
    }

    public static String chat(Config cfg, List<Msg> history, String userText, String systemPrompt) throws Exception {
        JSONArray arr = new JSONArray();
        String sys = (systemPrompt == null || systemPrompt.trim().isEmpty()) ? cfg.systemPrompt : systemPrompt;
        // 通用输出约束：推理型模型很容易把思考一起写进正文，直接念出来就是一场灾难
        sys = (sys == null ? "" : sys)
                + "\n\n【输出要求】直接给出你要对电话那头说的话，不要写思考过程、不要解释、不要自问自答、"
                + "不要加引号、不要列点。最多三句话，每一句都要能直接念出来。";
        arr.put(new JSONObject().put("role", "system").put("content", sys));
        if (history != null) {
            for (Msg m : history) {
                arr.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
        }
        arr.put(new JSONObject().put("role", "user").put("content", userText));

        JSONObject body = new JSONObject();
        body.put("model", cfg.model);
        body.put("messages", arr);
        body.put("temperature", cfg.temperature);
        body.put("stream", false);
        // 推理型模型的思考也吃 token。原来固定给 320，对方一长问就把预算全用在思考上，
        // 正文直接空掉，才引出「念思维链」那一幕。
        // 现在按「思考开关」分配：关掉思考就给小预算逼它直接答，打开才留足余量。
        body.put("max_tokens", cfg.allowThinking ? 700 : 320);

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(cfg.endpoint()).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(Math.min(4000, Math.max(2500, cfg.timeoutSec * 500)));
            conn.setReadTimeout(Math.min(8000, Math.max(4000, cfg.timeoutSec * 1000)));
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            if (cfg.apiKey != null && !cfg.apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey.trim());
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(is);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + brief(text));
            }
            JSONObject json = new JSONObject(text);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new IllegalStateException("no choices: " + brief(text));
            }
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) {
                throw new IllegalStateException("no message: " + brief(text));
            }
            String content = msg.optString("content", "");
            String reasoning = msg.optString("reasoning_content", "");
            String stats = "content=" + content.length() + "字 reason=" + reasoning.length() + "字";
            if (content.trim().isEmpty()) {
                // 这里以前是 content = reasoning_content，直接把模型的思考过程当成答案。
                // 推理型模型一旦把 max_tokens 全烧在思考上，正文就是空的，
                // 于是整段「我需要…规则说…检查每个句子…」被写进字幕、塞给 TTS，
                // TTS 读不动直接放弃播放——表现就是字幕一大串、一点声音都没有、通话卡死。
                // 现在默认不允许模型思考，正文空着就当这轮失败，绝不念思考过程。
                String picked = cfg.allowThinking ? pickFromReasoning(reasoning) : null;
                lastDebug = stats + " → 正文为空，"
                        + (cfg.allowThinking
                        ? (picked == null ? "思考里也没捞到正文" : "从思考中提取 " + picked.length() + " 字")
                        : "思考开关已关闭，按失败处理");
                if (picked == null) {
                    throw new IllegalStateException("模型只回了思考过程（" + stats + "）");
                }
                content = picked;
            } else {
                lastDebug = stats;
            }
            return postProcess(content, cfg);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 清理模型输出：去掉思考过程、markdown、换行，压成适合 TTS 的一句话。 */
    static String postProcess(String raw, Config cfg) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (cfg.stripThinking) {
            s = s.replaceAll("（[^）]*）", "");
            s = s.replaceAll("\\([^)]*\\)", "");
            s = s.replaceAll("(?s)```.*?```", "");
        }
        s = s.replaceAll("[*#`>_~]", "");
        s = s.replaceAll("\\s*\\n+\\s*", "，");
        s = s.replaceAll("[，,]{2,}", "，");
        s = s.trim();
        if (s.startsWith("，")) {
            s = s.substring(1);
        }
        while (s.startsWith("\"") || s.startsWith("“")) {
            s = s.substring(1);
        }
        while (s.endsWith("\"") || s.endsWith("”")) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.trim();
        s = stripThinkingChain(s);
        s = limitForTts(s, 80);
        return s.isEmpty() ? null : s;
    }

    /** 思维链里常见的元话语，出现在「要说的话」里基本可以确定不是给对方的台词 */
    private static final String[] META = {
            "我需要", "规则说", "我尽量", "我应当", "我应该", "检查", "确保", "最终输出", "最终回复",
            "简洁回答", "保持简短", "不要编造", "可以这样", "可以这么", "这样几", "合并", "字数",
            "每个句子", "关键词", "上面", "下面", "这里", "首先", "其次", "总结", "分析", "思考",
            "用户", "对方问", "问题很多", "不超过", "语气要", "我觉得", "输出格式", "格式要求",
    };

    /** 从思考过程里捞「回复：」之后的内容，捞不到返回 null */
    private static String pickFromReasoning(String reasoning) {
        if (reasoning == null || reasoning.trim().isEmpty()) {
            return null;
        }
        String[] markers = {"最终回复：", "最终输出：", "最终输出:", "回复：", "回复:", "答案：", "答案:"};
        int cut = -1;
        for (String m : markers) {
            int i = reasoning.lastIndexOf(m);
            if (i >= 0) {
                cut = Math.max(cut, i + m.length());
            }
        }
        if (cut <= 0 || cut >= reasoning.length()) {
            return null;
        }
        String tail = reasoning.substring(cut).trim();
        return tail.isEmpty() ? null : tail;
    }

    /**
     * 剥掉混在正文里的思考过程：优先按「回复：」这类标记截取，再逐句丢掉元话语。
     * 只要滤完还剩东西就用滤过的，全滤光了说明判断过激，退回原文。
     */
    private static String stripThinkingChain(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String body = s;
        String[] markers = {"最终回复：", "最终输出：", "最终输出:", "回复：", "回复:", "要说的话：", "答案："};
        int cut = -1;
        for (String m : markers) {
            int i = body.lastIndexOf(m);
            if (i >= 0) {
                cut = Math.max(cut, i + m.length());
            }
        }
        if (cut > 0 && cut < body.length()) {
            body = body.substring(cut);
        }

        // 模型明显在自问自答（长文 + 一堆元话语）时，只认引号里的台词。
        // 这种情况下逐句过滤仍会漏掉「这样3句。可以。合并。」这类残渣。
        if (body.length() > 90 && countMeta(body) >= 3) {
            String quoted = pickQuoted(body);
            if (quoted != null) {
                return quoted;
            }
        }

        StringBuilder keep = new StringBuilder();
        for (String piece : body.split("(?<=[。！？!?])|\\n")) {
            String t = piece.trim();
            if (t.isEmpty()) {
                continue;
            }
            // 口语台词一句不会太长；超过 40 字基本都是思考
            if (t.length() > 40) {
                continue;
            }
            // 碎句（可以。这样。）也不是台词
            if (t.length() < 6) {
                continue;
            }
            if (isMetaTalk(t)) {
                continue;
            }
            keep.append(t);
        }
        String out = keep.toString().trim();
        return out.isEmpty() ? s : out;
    }

    private static boolean isMetaTalk(String t) {
        for (String k : META) {
            if (t.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static int countMeta(String s) {
        int n = 0;
        for (String k : META) {
            if (s.contains(k)) {
                n++;
            }
        }
        return n;
    }

    /** 只挑引号里的台词，思维链污染时的最后一道闸 */
    private static String pickQuoted(String s) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\u201c\"]([^\u201d\"]{4,80})[\u201d\"]")
                .matcher(s);
        while (m.find()) {
            String q = m.group(1).trim();
            if (q.isEmpty() || isMetaTalk(q)) {
                continue;
            }
            sb.append(q);
            if (sb.length() > 120) {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** TTS 对超长文本会直接放弃，按句边界截到 max 字以内 */
    private static String limitForTts(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        int end = 0;
        int acc = 0;
        for (String piece : s.split("(?<=[。！？!?])")) {
            if (acc + piece.length() > max) {
                break;
            }
            acc += piece.length();
            end += piece.length();
        }
        if (end == 0) {
            return s.substring(0, max);
        }
        String head = s.substring(0, end).trim();
        return head.isEmpty() ? s.substring(0, max) : head;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }
}
