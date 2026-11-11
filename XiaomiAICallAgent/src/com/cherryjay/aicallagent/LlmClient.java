package com.cherryjay.aicallagent;

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

    public static String chat(Config cfg, List<Msg> history, String userText) throws Exception {
        return chat(cfg, history, userText, null);
    }

    public static String chat(Config cfg, List<Msg> history, String userText, String systemPrompt) throws Exception {
        JSONArray arr = new JSONArray();
        String sys = (systemPrompt == null || systemPrompt.trim().isEmpty()) ? cfg.systemPrompt : systemPrompt;
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
        body.put("max_tokens", 320);

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
            if (content.trim().isEmpty()) {
                String reasoning = msg.optString("reasoning_content", "");
                content = reasoning;
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
        return s.isEmpty() ? null : s;
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
