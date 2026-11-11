package com.cherryjay.aicallagent;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;

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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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
    /** 小爱自己的开场白配置键：就在 playGreeting 里读，写进去即生效 */
    private static final String KEY_OPENING_ACQ = "ai_custom_opening_acquaintance";
    private static final String KEY_OPENING_STR = "ai_custom_opening_stranger";

    private static final long SESSION_GAP_MS = 5 * 60 * 1000L;
    private static final long SPEAK_TIMEOUT_MS = 8 * 1000L;
    private static final long CATEGORY_TTL_MS = 10 * 60 * 1000L;

    private static ExecutorService sWorker;
    private static ExecutorService sLogPool;
    private static final Object LOCK = new Object();
    private static final List<LlmClient.Msg> sHistory = new ArrayList<>();
    private static long sLastTurnAt;

    private static Object sSuspendedSentinel;

    /** 当前通话信息，由 OsbotInCallService.onCallAdded 捕获 */
    private static volatile String sNumber = "";
    private static volatile String sCallerName = "";

    /**
     * 这通电话是不是用户手动点「AI 应答 → 小爱帮我回」升级来的。
     * 手动接管算明确指令，不该再被来电分类开关挡掉。
     */
    private static volatile boolean sManualTakeover;

    /** 这通电话是否已经初始化过接管，避免重复回调反复清空对话上下文 */
    private static volatile boolean sTakeoverInit;

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
                    sTakeoverInit = false;
                    captureCall(param.args);
                }
            });
            XposedBridge.hookAllMethods(serviceCls, "onCallRemoved", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sManualTakeover = false;
                    sTakeoverInit = false;
                    resetHistory();
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

        // 通话中点右上角「AI 应答 → 小爱帮我回」
        // 内部会走 takeOverCallWithAi -> createEngine(Call)，与自动接听共用同一个引擎。
        try {
            Class<?> svcCls = XposedHelpers.findClass(SERVICE_CLS, cl);
            java.util.Set<XC_MethodHook.Unhook> unhooks =
                    XposedBridge.hookAllMethods(svcCls, "takeOverCallWithAi", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                onManualTakeover(param);
                            } catch (Throwable t) {
                                log("处理手动接管出错：" + t);
                            }
                        }
                    });
            log("手动接管钩子：" + unhooks.size() + " 个方法（0 表示没匹配到，接管会走原生）");
        } catch (Throwable t) {
            log("手动接管钩子失败：" + t);
        }
    }

    // ------------------------------------- 手动「小爱帮我回」

    /**
     * 用户点通话页右上角 AI 应答、选「小爱帮我回」时，系统调
     * OsbotInCallService.takeOverCallWithAi(Call)，内部 createEngine(Call) 建出
     * CallConversationEngine —— 和自动接听是同一个引擎，所以对话回合照样落在
     * handleCallerSentence 上，我们的模型天然能接住。
     *
     * 这里只解决两个会让用户觉得"没接上"的问题：
     *  1) 若这通电话曾被 pauseAiAssist 暂停（通话中开关关过），takeOverCallWithAi 打开头
     *     第一个 shouldContinueAiAssist("start takeover") 就会中断，表现为点了没反应。
     *     接管前必须把 isAiAssistPaused 清掉。
     *  2) 用户主动点按钮 = 明确要 AI 接，不该再被来电分类开关挡回去（默认豁免）。
     */
    private void onManualTakeover(XC_MethodHook.MethodHookParam param) {
        final Object svc = param.thisObject;
        try {
            if (XposedHelpers.getBooleanField(svc, "isAiAssistPaused")) {
                XposedHelpers.setBooleanField(svc, "isAiAssistPaused", false);
                log("接管前清掉暂停标记（否则本轮接管会被立刻中断）");
            }
        } catch (Throwable t) {
            log("复位暂停标记失败：" + t);
        }

        // 同一通电话里 takeOverCallWithAi 会被反复回调（实测一通电话触发七八次），
        // 每次都重置历史会让多轮对话丢掉上下文，这里按通话去重：每通电话只初始化一次。
        if (sTakeoverInit) {
            return;
        }

        final Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) {
            sManualTakeover = false;
            log("手动接管：拿不到 Context，按原生走");
            return;
        }

        final Config cfg = Config.load(ctx);
        sManualTakeover = cfg.enabled && cfg.manualTakeover;
        resetHistory();
        writeOpening(ctx, cfg);
        sTakeoverInit = true;

        log("检测到手动接管（小爱帮我回）→ " + (sManualTakeover ? "本通用大模型" : "仍走原生话术"));
        logAsync(ctx, CallLogStore.TYPE_INFO,
                sManualTakeover ? "手动 AI 接管 · 已接入大模型" : "手动 AI 接管 · 使用原生话术");
    }

    /** 新一通电话从零开始，别把上一通的上下文串进来 */
    private static void resetHistory() {
        synchronized (LOCK) {
            if (!sHistory.isEmpty()) {
                log("手动接管，清空上一通对话上下文");
            }
            sHistory.clear();
            sLastTurnAt = 0L;
        }
    }

    /**
     * 开场白走小爱自己的配置键（playGreeting 里读 ai_custom_opening_*）。
     * 模块里留空表示不干预，保留用户在小爱原生设置里填的值。
     */
    private static void writeOpening(Context ctx, Config cfg) {
        final String acq = cfg.openingAcquaintance == null ? "" : cfg.openingAcquaintance.trim();
        final String str = cfg.openingStranger == null ? "" : cfg.openingStranger.trim();
        try {
            if (!acq.isEmpty()) {
                putConfig(ctx, KEY_OPENING_ACQ, acq);
            }
            if (!str.isEmpty()) {
                putConfig(ctx, KEY_OPENING_STR, str);
            }
            if (!acq.isEmpty() || !str.isEmpty()) {
                log("已下发自定义开场白");
            }
        } catch (Throwable t) {
            log("写开场白失败：" + t);
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

        // Telecom 的 callerDisplayName 在日常来电里基本都是空的，
        // 只靠它会把熟人误判成陌生号码、用错场景话术。这里自己查通讯录兜底。
        if (!isContact) {
            String contactName = lookupContactName(ctx, number);
            if (contactName != null && !contactName.isEmpty()) {
                sCallerName = contactName;
                isContact = true;
                log("通讯录匹配到：" + contactName);
            }
        }

        String category = Category.classify(tag, isContact, number);
        log("归类：" + Category.label(category) + "（联系人=" + isContact + "，黄页=" + tag + "）");

        sCategoryCache.put(number, new Cached(category, tag));
        return new String[]{category, tag};
    }

    /** 用号码反查通讯录姓名，小爱进程自带 READ_CONTACTS 权限 */
    private String lookupContactName(Context ctx, String number) {
        if (number == null || number.isEmpty()) {
            return null;
        }
        Cursor c = null;
        try {
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            c = ctx.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Throwable t) {
            log("通讯录查询失败：" + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
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
        logAsync(ctx, CallLogStore.TYPE_REMOTE, sentence);

        // 看门狗：回合已经被我们拦下，任何一环卡死都必须由我们补一句话推进状态机，
        // 否则界面会永久停在「AI 自动回复中」。
        final AtomicBoolean turnDone = new AtomicBoolean(false);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!turnDone.get()) {
                log("本轮 15 秒未收尾，强制播报兜底");
                speakAndFinish(engine, "不好意思，我这边有点卡，稍后让机主回你电话好吗？");
            }
        }, 15 * 1000L);

        worker().execute(() -> {
            try {
                final long t0 = System.currentTimeMillis();
                final Turn turn = new Turn();

            // 整轮（分类 + 模型）都放到子线程里跑，并加硬超时。
            // 之前这里是裸调用，一旦 DNS 解析或通讯录查询卡住，
            // 原生回合已被 setResult(null) 拦下，通话就会一直卡在「AI 自动回复中」。
            FutureTask<Void> task = new FutureTask<>(() -> {
                String[] ct = resolveCategory(ctx, cl);
                turn.category = ct[0];
                turn.tag = ct[1];
                turn.label = Category.label(turn.category);

                boolean manualBypass = sManualTakeover && cfg.manualIgnoreCategory;
                if (!cfg.shouldHandle(turn.category) && !manualBypass) {
                    turn.toNative = true;
                    return null;
                }
                if (manualBypass && !cfg.shouldHandle(turn.category)) {
                    log("来电类型=" + turn.label + " 本应走原生，但用户手动接管，改用大模型");
                }
                turn.reply = LlmClient.chat(cfg, snapshotHistory(t0), sentence,
                        cfg.promptFor(turn.category));
                return null;
            });
            Thread th = new Thread(task, "aicall-agent-turn");
            th.setDaemon(true);
            th.start();
            try {
                // 通话里静默超过十秒对方就会以为断线，所以整轮硬上限压到 9 秒
                task.get(9, TimeUnit.SECONDS);
            } catch (Throwable t) {
                turn.error = t;
                task.cancel(true);
            }

            if (turn.toNative) {
                log("来电类型=" + turn.label + "（标签：" + turn.tag + "），按设置交回原生");
                logAsync(ctx, CallLogStore.TYPE_INFO,
                        "来电类型【" + turn.label + "】→ 走小米原生"
                                + (turn.tag == null ? "" : "（标签：" + turn.tag + "）"));
                fallbackToNative(originalMethod, engine, originalArgs);
                return;
            }

            long cost = System.currentTimeMillis() - t0;
            if (turn.reply == null) {
                String why = turn.error == null ? "模型返回空" : String.valueOf(turn.error);
                log("模型调用失败（" + cost + "ms）：" + why);
                logAsync(ctx, CallLogStore.TYPE_ERROR, "模型失败：" + why);
                // 已经把原生回合拦掉了，这里必须自己说一句把状态机推下去，否则会卡住
                speakAndFinish(engine, "不好意思，这边信号不太好，我记一下，稍后让机主回你电话好吗？");
                return;
            }

            log("[" + turn.label + "] 回复(" + cost + "ms)：" + turn.reply);
            logAsync(ctx, CallLogStore.TYPE_LOCAL,
                    turn.reply + "  ·" + cost + "ms · " + turn.label);
            pushHistory(sentence, turn.reply, cfg.maxHistory);

            writeReplyLog(ctx, turn.reply);
            speakAndFinish(engine, turn.reply);
            } finally {
                turnDone.set(true);
            }
        });
    }

    /** 一轮处理的结果容器 */
    private static final class Turn {
        String category;
        String tag;
        String label;
        String reply;
        Throwable error;
        boolean toNative;
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

    /**
     * 跨进程写通话日志一律走独立线程。
     *
     * 之前这里是同步 ContentResolver.call：一旦模块进程被回收或正在重启，
     * 这个调用会把引擎的 hook 线程（也就是通话线程）一起卡住，
     * 表现就是界面停在「AI 自动回复中」再也不动。
     */
    private static void logAsync(Context ctx, String type, String text) {
        if (ctx == null) {
            return;
        }
        final Context app;
        try {
            Context c = ctx.getApplicationContext();
            app = c != null ? c : ctx;
        } catch (Throwable t) {
            return;
        }
        try {
            logPool().execute(() -> LogProvider.appendFromHook(app, type, text));
        } catch (Throwable ignored) {
        }
    }

    private static ExecutorService logPool() {        synchronized (LOCK) {
            if (sLogPool == null) {
                sLogPool = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "aicall-agent-log");
                    t.setDaemon(true);
                    return t;
                });
            }
            return sLogPool;
        }
    }

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
