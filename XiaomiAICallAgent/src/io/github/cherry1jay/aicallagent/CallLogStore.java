package io.github.cherry1jay.aicallagent;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 通话对话记录。由模块应用进程写盘，Hook 端通过 LogProvider 跨进程追加。
 */
public final class CallLogStore {

    private static final String FILE = "calllog.tsv";
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_LINES = 400;

    public static final String TYPE_REMOTE = "R"; // 对方说
    public static final String TYPE_LOCAL = "L";  // 我说
    public static final String TYPE_INFO = "I";
    public static final String TYPE_ERROR = "E";

    private CallLogStore() {
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE);
    }

    public static synchronized void append(Context ctx, String type, String text) {
        if (ctx == null || text == null) {
            return;
        }
        String clean = text.replace("\t", " ").replace("\n", " ").trim();
        if (clean.isEmpty()) {
            return;
        }
        String line = System.currentTimeMillis() + "\t" + type + "\t" + clean + "\n";
        File f = file(ctx);
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                List<String> keep = readLines(f);
                int from = Math.max(0, keep.size() - MAX_LINES / 2);
                try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, false), StandardCharsets.UTF_8)) {
                    for (int i = from; i < keep.size(); i++) {
                        w.write(keep.get(i));
                        w.write('\n');
                    }
                }
            }
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                w.write(line);
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized List<Item> read(Context ctx) {
        List<Item> out = new ArrayList<>();
        File f = file(ctx);
        if (!f.exists()) {
            return out;
        }
        for (String line : readLines(f)) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) {
                continue;
            }
            try {
                out.add(new Item(Long.parseLong(p[0]), p[1], p[2]));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    public static synchronized void clear(Context ctx) {
        File f = file(ctx);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    out.add(line);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static final class Item {
        public final long time;
        public final String type;
        public final String text;

        Item(long time, String type, String text) {
            this.time = time;
            this.type = type;
            this.text = text;
        }

        public String timeString() {
            return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(time));
        }
    }
}
