package com.cherryjay.aicallagent;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.AndroidAppHelper;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 接管小米「AI 通话」的对话大脑，并按来电类型分流。
 *
 * 实测链路（HyperOS 4.0 / 小爱 8.2.70）：
 *   OsbotInCallService(ASR 分句)
 *     -> CallConversationEngine.handleCallerSentence(String)
 *     -> 原生 LLM -> speakFn(协程 TTS via AudioTrack) -> 写 ai_call_reply_log_content
 *
 * 我们替换 handleCallerSentence：先按黄页标签判定来电类型，命中规则的才接管，
 * 其余原样交给小米原生模型。接管时借引擎的 speakFn 播报，并在播报结束后补发
 * onAgentTurnComplete，保证原生状态机继续正常监听。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AICallAgent";
    private static final String TARGET_PKG = "com.miui.voiceassist";
    private static final String ENGINE_CLS = "com.aios.osbot.tools.phone.engine.CallConversationEngine";
    private static final String SERVICE_CLS = "com.aios.osbot.tools.phone.service.OsbotInCallService";
    private static final String YELLOW_PAGE_CLS = "miui.yellowpage.YellowPageUtils";
    private static final String CONFIG_AUTHORITY = "ai-assist-call-config";
    private static final String KEY_REPLY_LOG = "ai_call_reply_log_content";
    private static final String KEY_TRIGGER_ANSWER = "ai_call_trigger_answer";

    /** App -> Hook：通话中开关指令 */
    private static final String ACTION_CONTROL = "com.cherryjay.aicallagent.CONTROL";
    /** Hook -> App：执行结果回传 */
    private static final String ACTION_STATE = "com.cherryjay.aicallagent.STATE";
    private static final String APP_PKG = "com.cherryjay.aicallagent";
    private static final String CMD_AI_REPLY = "ai_reply";

    private static final long SESSION_GAP_MS = 5 * 60 * 1000L;
    private static final long SPEAK_TIMEOUT_MS = 60 * 1000L;
    private static final long CATEGORY_TTL_MS = 10 * 60 * 1000L;

    private static ExecutorService sWorker;
    private static final Object LOCK = new Object();
    private static final List<LlmClient.Msg> sHistory = new ArrayList<>();
    private static long sLastTurnAt;

    private static Object sSuspendedSentinel;

    /** 当前通话信息，由 OsbotInCallService.onCallAdded 捕获 */
    private static volatile String sNumber = "";
    private static volatile String sCallerName = "";

    /** InCallService 实例，通话中开关要直接驱动它的 pause/resume */
    private static volatile Object sService;
    private static volatile boolean sReceiverInstalled;

    private static final Map<String, Cached> sCategoryCache = new ConcurrentHashMap<>();

    private static final class Cached {
        final String category;
        final String tag;
        final long at;

        Cached(String category, String tag) {
            this.category = category;
            this.tag = tag;
            this.at = System.currentTimeMillis();
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET_PKG.equals(lp.packageName)) {
            return;
        }
        log("注入成功：" + lp.packageName + " (" + lp.processName + ")");
        final ClassLoader cl = lp.classLoader;

        try {
            Class<?> serviceCls = XposedHelpers.findClass(SERVICE_CLS, cl);
            XposedBridge.hookAllMethods(serviceCls, "onCallAdded", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sService = param.thisObject;
                    if (!sReceiverInstalled) {
                        scheduleInstallReceiver();
                    }
                    captureCall(param.args);
                }
            });
            XposedBridge.hookAllMethods(serviceCls, "onCallRemoved", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sService = null;
                    // 通话结束把开关键复位，避免影响下一通来电的自动接听策略
                    try {
                        Context ctx = AndroidAppHelper.currentApplication();
                        if (ctx != null) {
                            putConfig(ctx, KEY_TRIGGER_ANSWER, "");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log("通话信息钩子失败：" + t);
        }

        try {
            Class<?> engineCls = XposedHelpers.findClass(ENGINE_CLS, cl);
            XposedBridge.hookAllMethods(engineCls, "handleCallerSentence", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        onCallerSentence(param, cl);
                    } catch (Throwable t) {
                        log("处理回合出错：" + t);
                    }
                }
            });
            sSuspendedSentinel = resolveSuspendedSentinel(cl);
            log("Hook 就绪，suspend 哨兵=" + sSuspendedSentinel);
        } catch (Throwable t) {
            log("引擎钩子失败：" + t);
        }

        // 通话中开关：App 发广播 -> 这里以小爱进程的权限写配置 / 直接驱动引擎
        // 小爱有 core / voice_trigger 等多个进程，只有主进程持有 InCallService，只在主进程注册
        if (TARGET_PKG.equals(lp.processName)) {
            scheduleInstallReceiver();
        }
    }

    // ------------------------------------------------------- 通话中开关

    /** 注入瞬间 Application 还没建好，需要重试；通话开始时也会再补一次 */
    private void scheduleInstallReceiver() {
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] tries = {0};
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (sReceiverInstalled) {
                    return;
                }
                Context ctx = AndroidAppHelper.currentApplication();
                if (ctx != null) {
                    installControlReceiver(ctx);
                    return;
                }
                if (++tries[0] < 20) {
                    h.postDelayed(this, 500);
                } else {
                    log("控制通道：拿不到 Context，等通话开始再补注册");
                }
            }
        };
        h.postDelayed(r, 300);
    }

    private void installControlReceiver(final Context ctx) {
        if (sReceiverInstalled) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    try {
                        handleControl(ctx, intent);
                    } catch (Throwable t) {
                        log("处理开关指令失败：" + t);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_CONTROL);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            sReceiverInstalled = true;
            log("控制通道就绪，等待 App 指令");
        } catch (Throwable t) {
            log("registerReceiver 失败：" + t);
        }
    }

    /**
     * on=true  -> 恢复 AI 应答（resumeAiAssist）
     * on=false -> 暂停 AI 应答（pauseAiAssist），把话头交回机主
     * 两个方法都是 public，且内部幂等，可以直接调。
     */
    private void handleControl(Context ctx, Intent in) {
        String cmd = in.getStringExtra("cmd");
        if (!CMD_AI_REPLY.equals(cmd)) {
            return;
        }
        boolean on = in.getBooleanExtra("on", false);

        Object svc = sService;
        boolean inCall = false;
        if (svc != null) {
            try {
                inCall = XposedHelpers.getObjectField(svc, "currentCall") != null;
            } catch (Throwable ignored) {
            }
        }

        boolean acted = false;
        if (svc != null && inCall) {
            try {
                XposedHelpers.callMethod(svc, on ? "resumeAiAssist" : "pauseAiAssist");
                acted = true;
            } catch (Throwable t) {
                log("调用 " + (on ? "resumeAiAssist" : "pauseAiAssist") + " 失败：" + t);
            }
        }

        // 同步给 InCallUI 的开关状态（通话结束时会复位）
        if (inCall) {
            putConfig(ctx, KEY_TRIGGER_ANSWER, on ? "true" : "false");
        }

        boolean paused = true;
        if (svc != null) {
            try {
                paused = XposedHelpers.getBooleanField(svc, "isAiAssistPaused");
            } catch (Throwable ignored) {
            }
        }

        log("通话中开关：" + (on ? "开" : "关")
                + "，通话中=" + inCall + "，已调用=" + acted + "，当前暂停=" + paused);
        LogProvider.appendFromHook(ctx, CallLogStore.TYPE_INFO,
                "通话中开关 → " + (on ? "小爱继续应答" : "小爱暂停")
                        + (inCall ? "" : "（当前没有通话，仅记录）"));

        try {
            Intent out = new Intent(ACTION_STATE);
            out.setPackage(APP_PKG);
            out.putExtra("inCall", inCall);
            out.putExtra("paused", paused);
            out.putExtra("requested", on);
            out.putExtra("acted", acted);
            ctx.sendBroadcast(out);
        } catch (Throwable ignored) {
        }
    }

    private static boolean putConfig(Context ctx, String key, String value) {
        try {
            Uri uri = Uri.parse("content://" + CONFIG_AUTHORITY + "/settings/" + key);
            ContentValues cv = new ContentValues();
            cv.put("key", key);
            cv.put("value", value);
            return ctx.getContentResolver().insert(uri, cv) != null;
        } catch (Throwable t) {
            log("写配置失败 " + key + "：" + t);
            return false;
        }
    }

    // ------------------------------------------------------------ 来电信息

    private void captureCall(Object[] args) {
        try {
            if (args == null || args.length == 0 || args[0] == null) {
                return;
            }
            Object call = args[0];
            Object details = XposedHelpers.callMethod(call, "getDetails");
            if (details == null) {
                return;
            }
            String number = "";
            Object handle = XposedHelpers.callMethod(details, "getHandle");
            if (handle instanceof Uri) {
                String ssp = ((Uri) handle).getSchemeSpecificPart();
                if (ssp != null) {
                    number = ssp;
                }
            }
            String name;
            try {
                name = (String) XposedHelpers.callMethod(details, "getCallerDisplayName");
            } catch (Throwable t) {
                name = null;
            }
            sNumber = number;
            sCallerName = name == null ? "" : name;
            sCategoryCache.clear();
            log("来电：" + number + " / " + sCallerName);
        } catch (Throwable t) {
            log("解析来电失败：" + t);
        }
    }

    /** 查黄页标签并归类，带缓存 */
    private String[] resolveCategory(Context ctx, ClassLoader cl) {
        String number = sNumber;
        if (number == null) {
            number = "";
        }
        Cached c = sCategoryCache.get(number);
        if (c != null && System.currentTimeMillis() - c.at < CATEGORY_TTL_MS) {
            return new String[]{c.category, c.tag};
        }

        String tag = null;
        try {
            tag = queryYellowPageTag(ctx, cl, number);
        } catch (Throwable t) {
            log("黄页查询异常：" + t);
        }

        boolean isContact = sCallerName != null && !sCallerName.isEmpty()
                && !sCallerName.equals(number) && !isPhoneLike(sCallerName);
        String category = Category.classify(tag, isContact, number);

        sCategoryCache.put(number, new Cached(category, tag));
        return new String[]{category, tag};
    }

    private String queryYellowPageTag(Context ctx, ClassLoader cl, String number) throws Exception {
        if (number == null || number.isEmpty()) {
            return null;
        }
        Class<?> yp = Class.forName(YELLOW_PAGE_CLS, false, cl);
        for (Method m : yp.getMethods()) {
            if (!"getPhoneInfo".equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 3 && ps[0].isAssignableFrom(ctx.getClass()) && ps[1] == String.class) {
                Object third = (ps[2] == boolean.class || ps[2] == Boolean.class) ? Boolean.TRUE : (Object) 1;
                Object phone = m.invoke(null, ctx, number, third);
                if (phone != null) {
                    Object tag = XposedHelpers.callMethod(phone, "getTag");
                    return tag == null ? null : String.valueOf(tag);
                }
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 主流程

    private void onCallerSentence(XC_MethodHook.MethodHookParam param, ClassLoader cl) {
        final Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) {
            log("拿不到 Context，放行原生");
            return;
        }

        final Config cfg = Config.load(ctx);
        if (!cfg.enabled) {
            log("模块已关闭，走原生回复");
            return;
        }
        if (cfg.apiKey == null || cfg.apiKey.trim().isEmpty()) {
            log("未填写 API Key，走原生回复");
            return;
        }

        final String sentence = String.valueOf(param.args[0]);
        if (sentence.trim().isEmpty()) {
            return;
        }

        final Object engine = param.thisObject;
        final Member originalMethod = param.method;
        final Object[] originalArgs = param.args;

        // 先把这一回合拦下，避免原生模型也说话（重了会串音）
        param.setResult(null);
        LogProvider.appendFromHook(ctx, CallLogStore.TYPE_REMOTE, sentence);

        worker().execute(() -> {
            // 1) 判定来电类型，决定是否由我们接管
            String[] ct = resolveCategory(ctx, cl);
            String category = ct[0];
            String tag = ct[1];
            String label = Category.label(category);
            if (!cfg.shouldHandle(category)) {
                log("来电类型=" + label + "（标签：" + tag + "），按设置交回原生");
                LogProvider.appendFromHook(ctx, CallLogStore.TYPE_INFO,
                        "来电类型【" + label + "】→ 走小米原生" + (tag == null ? "" : "（标签：" + tag + "）"));
                fallbackToNative(originalMethod, engine, originalArgs);
                return;
            }

            // 2) 调我们的模型
            long t0 = System.currentTimeMillis();
            String reply = null;
            Throwable error = null;
            List<LlmClient.Msg> history = snapshotHistory(t0);
            try {
                reply = LlmClient.chat(cfg, history, sentence, cfg.promptFor(category));
            } catch (Throwable t) {
                error = t;
            }

            if (reply == null) {
                String why = error == null ? "模型返回空" : String.valueOf(error.getMessage());
                log("模型调用失败：" + why);
                LogProvider.appendFromHook(ctx, CallLogStore.TYPE_ERROR, "模型失败：" + why);
                if (cfg.fallback) {
                    log("回落到原生回复");
                    fallbackToNative(originalMethod, engine, originalArgs);
                } else {
                    speakAndFinish(engine, "不好意思，我这边不太方便，稍后让机主回你电话可以吗？");
                }
                return;
            }

            long cost = System.currentTimeMillis() - t0;
            log("[" + label + "] 回复(" + cost + "ms)：" + reply);
            LogProvider.appendFromHook(ctx, CallLogStore.TYPE_LOCAL,
                    reply + "  ·" + cost + "ms · " + label);
            pushHistory(sentence, reply, cfg.maxHistory);

            writeReplyLog(ctx, reply);
            speakAndFinish(engine, reply);
        });
    }

    private void fallbackToNative(Member originalMethod, Object engine, Object[] args) {
        try {
            XposedBridge.invokeOriginalMethod(originalMethod, engine, args);
        } catch (Throwable t) {
            log("回落原生失败：" + t);
        }
    }

    /**
     * 借用引擎自己的 speakFn 播报，播报完成后补发 onAgentTurnComplete。
     */
    private void speakAndFinish(final Object engine, final String text) {
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());

        Runnable completeTurn = () -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                Object cb = XposedHelpers.getObjectField(engine, "onAgentTurnComplete");
                if (cb != null) {
                    XposedHelpers.callMethod(cb, "invoke");
                    log("回合结束信号已补发");
                }
            } catch (Throwable t) {
                log("补发回合结束失败：" + t);
            }
        };

        try {
            Object speakFn = XposedHelpers.getObjectField(engine, "speakFn");
            if (speakFn == null) {
                log("speakFn 为空，跳过播报");
                completeTurn.run();
                return;
            }
            Object continuation = newContinuation(speakFn.getClass().getClassLoader(), () -> {
                log("播报完成");
                main.post(completeTurn);
            });
            Object ret = XposedHelpers.callMethod(speakFn, "invoke", text, continuation);
            if (ret != sSuspendedSentinel) {
                log("播报同步返回，直接收尾");
                completeTurn.run();
            } else {
                main.postDelayed(completeTurn, SPEAK_TIMEOUT_MS);
            }
        } catch (Throwable t) {
            log("播报失败：" + t);
            completeTurn.run();
        }
    }

    // ------------------------------------------------------------ 工具

    private static ExecutorService worker() {
        synchronized (LOCK) {
            if (sWorker == null) {
                sWorker = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "aicall-agent-worker");
                    t.setDaemon(true);
                    return t;
                });
            }
            return sWorker;
        }
    }

    private static List<LlmClient.Msg> snapshotHistory(long now) {
        synchronized (LOCK) {
            if (now - sLastTurnAt > SESSION_GAP_MS) {
                if (!sHistory.isEmpty()) {
                    log("距上次对话较久，清空上下文");
                }
                sHistory.clear();
            }
            return new ArrayList<>(sHistory);
        }
    }

    private static void pushHistory(String user, String assistant, int maxKeep) {
        synchronized (LOCK) {
            sLastTurnAt = System.currentTimeMillis();
            sHistory.add(new LlmClient.Msg("user", user));
            sHistory.add(new LlmClient.Msg("assistant", assistant));
            while (sHistory.size() > Math.max(2, maxKeep)) {
                sHistory.remove(0);
            }
        }
    }

    private static void writeReplyLog(Context ctx, String text) {
        try {
            Uri uri = Uri.parse("content://" + CONFIG_AUTHORITY + "/settings/" + KEY_REPLY_LOG);
            ContentValues cv = new ContentValues();
            cv.put("key", KEY_REPLY_LOG);
            cv.put("value", text);
            ctx.getContentResolver().insert(uri, cv);
        } catch (Throwable t) {
            log("写字幕失败：" + t);
        }
    }

    private static Object newContinuation(ClassLoader cl, final Runnable onResume) throws Exception {
        Class<?> contCls = Class.forName("kotlin.coroutines.Continuation", false, cl);
        final Object emptyCtx;
        try {
            Class<?> eccCls = Class.forName("kotlin.coroutines.EmptyCoroutineContext", false, cl);
            emptyCtx = eccCls.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            throw new IllegalStateException("找不到 EmptyCoroutineContext", t);
        }
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if ("getContext".equals(name)) {
                return emptyCtx;
            }
            if ("resumeWith".equals(name)) {
                if (onResume != null) {
                    onResume.run();
                }
                return null;
            }
            if ("toString".equals(name)) {
                return "AICallAgent-Continuation";
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return proxy == (args == null ? null : args[0]);
            }
            return null;
        };
        return Proxy.newProxyInstance(contCls.getClassLoader(), new Class<?>[]{contCls}, h);
    }

    private static Object resolveSuspendedSentinel(ClassLoader cl) {
        try {
            Class<?> k = Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt", false, cl);
            for (Method m : k.getMethods()) {
                if ("getCOROUTINE_SUSPENDED".equals(m.getName())) {
                    return m.invoke(null);
                }
            }
        } catch (Throwable ignored) {
        }
        return new Object();
    }

    private static boolean isPhoneLike(String s) {
        return s.matches("[+0-9\\-\\s()]{5,}");
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
