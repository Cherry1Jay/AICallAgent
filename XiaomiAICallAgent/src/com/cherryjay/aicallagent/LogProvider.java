package com.cherryjay.aicallagent;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;

/**
 * 只做一件事：让运行在小爱进程里的 Hook 能把日志写到模块应用这边。
 */
public class LogProvider extends ContentProvider {

    public static final String AUTHORITY = "com.cherryjay.aicallagent.log";
    static final String METHOD_APPEND = "append";
    static final String METHOD_CLEAR = "clear";
    static final String METHOD_DUMP = "dump";
    /** Hook 在小爱进程里没有我们的 data 目录权限，配置由本进程代读 */
    static final String METHOD_CONFIG = "config";
    /** 唯一允许读取配置的调用方 */
    static final String CALLER_PKG = "com.miui.voiceassist";

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/");
    }

    /** Hook 端调用：写一条记录。任何异常都吞掉，绝不能影响通话。 */
    public static void appendFromHook(Context ctx, String type, String text) {
        if (ctx == null) {
            return;
        }
        try {
            Bundle in = new Bundle();
            in.putString("type", type);
            in.putString("text", text);
            ctx.getContentResolver().call(uri(), METHOD_APPEND, null, in);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        try {
            if (METHOD_APPEND.equals(method) && extras != null) {
                CallLogStore.append(ctx, extras.getString("type", CallLogStore.TYPE_INFO), extras.getString("text", ""));
            } else if (METHOD_CONFIG.equals(method)) {
                // 这份配置里有 API Key，只给小爱进程（也就是 Hook）读
                String caller = getCallingPackage();
                if (CALLER_PKG.equals(caller)) {
                    return Config.snapshot(ctx);
                }
                return null;
            } else if (METHOD_CLEAR.equals(method)) {
                CallLogStore.clear(ctx);
            } else if (METHOD_DUMP.equals(method)) {
                List<CallLogStore.Item> items = CallLogStore.read(ctx);
                StringBuilder sb = new StringBuilder();
                for (CallLogStore.Item it : items) {
                    sb.append(it.timeString()).append("  [").append(it.type).append("] ").append(it.text).append('\n');
                }
                Bundle out = new Bundle();
                out.putString("text", sb.toString());
                return out;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
