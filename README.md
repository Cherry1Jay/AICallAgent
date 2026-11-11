# AICallAgent

把小米「AI 通话」的大脑换成你自己的大模型。

一个 LSPosed 模块：接管 HyperOS 的 AI 自动接听，用任意 OpenAI 兼容接口（DeepSeek / Kimi / Qwen / GLM / Ollama / one-api 中转…）生成对话，并按来电话类型分流——想接的走你的模型，不想接的交回小米原生话术。

> 只在 **小米 17 Pro / HyperOS 4.0 / Android 17 / 小爱同学 8.2.70** 上实测。其它机型可能可用，也可能会失效，见「已知限制」。

---

## 它解决什么问题

小米的 AI 通话本身能自动接、能说话，但说什么由小爱决定：话术固定、不会随机应变、不能接入你自己的模型，也没法按来电类型区分对待——推销电话和外卖小哥听到的是同一套词。

本模块把「生成回复」这一步换掉，接听、字幕、语音合成都还是系统原生，所以：

- 界面、录音、字幕不变，通话记录照常写；
- 不碰音频流，全程在文本态工作，不需要任何音频权限；
- 换成任何 OpenAI 兼容模型，提示词随你写；
- 外卖、快递、推销、客服、联系人、陌生号分别配置，每类可单独关掉（关掉就是原生小米回复）。

---

## 为什么 hook 在这里

网上流传的方案说「AI 通话逻辑在 `com.android.incallui`」，本机实测**不成立**。把三个 APK 全部反编译比对后的实际分工：

| 包 | 实际职责 |
|---|---|
| `com.xiaomi.phone`（HyperPhone） | 只有配置 Provider `content://ai-assist-call-config`，一张 SQLite 键值表 |
| `com.android.incallui` | 只画字幕 UI，**没有 ASR、没有 TTS**，那几个 `KEY_AI_CALL_*` 只是读配置 |
| **`com.miui.voiceassist`（小爱）** | **ASR → LLM → TTS 全在这里**，`classes29.dex` |

真正的实现是明文、未混淆的：

```
com.aios.osbot.tools.phone.service.OsbotInCallService    // InCallService，状态机
com.aios.osbot.tools.phone.engine.CallConversationEngine // Agent 引擎
  └─ handleCallerSentence(String)                        // 单轮对话入口 ← 唯一 hook 点
       ├─ 私有字段 speakFn(Function2, suspend)            // 协程 TTS，AudioTrack 注入通话
       └─ 私有字段 onAgentTurnComplete(Function0)         // 回合结束信号
```

来电类型来自系统黄页：`miui.yellowpage.YellowPageUtils.getPhoneInfo(ctx, number, true).getTag()`，返回「外卖」「快递」「骚扰电话」这类标签，模块直接复用，不自己猜。

---

## 工作原理

```
对方说话
  └─ 系统 ASR 出文本
       └─ CallConversationEngine.handleCallerSentence(text)   ← 这里被拦下
            ├─ [原生] 小爱 LLM 生成回复          → 换成 →
            ├─ [本模块] 查来电类型 → 挑提示词 → HTTP 调你的模型
            ├─ 写 ai_call_reply_log_content（字幕照常显示）
            └─ 借引擎自己的 speakFn 播报（系统 TTS + 音频通道）
                 └─ 播完补发 onAgentTurnComplete，状态机继续监听下一轮
```

三个设计要点：

1. **只换大脑，不换嘴**。播报调用引擎自己的 `speakFn`，音频注入、回声消除、打断处理全部原生，不需要碰 AudioTrack，也不会和系统抢麦克风。
2. **必须补发回合结束信号**。接管后原生状态机收不到 `onAgentTurnComplete` 会停在「思考中」不再监听，所以播报完成后反射调用它收尾。
3. **超时必回落**。模型 12 秒没回（可配）就放弃接管、把这一轮交还原生 LLM，避免通话里长时间静默。

---

## 功能

**按来电类型分流**（6 类，各自独立开关）

| 类型 | 识别依据（黄页标签关键词） | 默认 |
|---|---|---|
| 外卖送餐 | 外卖、送餐、美团、饿了么、配送、跑腿、闪送… | 接入 |
| 快递物流 | 快递、物流、驿站、顺丰、圆通、中通、菜鸟、包裹… | 接入 |
| 广告推销 | 骚扰、推销、广告、中介、保险、贷款、理财、催收、诈骗… | **交回原生** |
| 客服官方 | 客服、官方、银行、航空、运营商、政务、医院、售后… | 接入 |
| 通讯录联系人 | 号码在通讯录里 | 接入 |
| 陌生号码 | 以上都不命中 | 接入 |

黄页查不到标签时按号码特征兜底：5–6 位短号、`400`/`800` 开头判客服，`95` 开头短号判客服。

**每类可自定义提示词**

点类别名（蓝色字）直接编辑，改过的会标「· 已改」，弹窗里有「恢复默认」。内置 6 套按语音场景写的默认话术——纯口语、短句、不带列表符号，因为这句话是要被 TTS 念出来的。

**通话中开关**

设置页顶部有「通话中 · 小爱自动回复」开关，通话中途随时切换：

- 关闭 → 小爱立刻停嘴，把话头交给你（`pauseAiAssist()`）
- 打开 → 小爱继续自动应答（`resumeAiAssist()`）

实现上直接调用引擎的 `pauseAiAssist` / `resumeAiAssist`，而不是只写配置键——这样不依赖引擎内部状态判断，点了就生效。同时写一次 `ai_call_trigger_answer` 让原生 UI 的开关状态同步，通话结束自动复位，不会影响下一通电话的自动接听策略。

> 只有 **AI 自动接听的通话** 才有这个能力。人工接听的电话小爱本来就不参与（`onCallActive()` 里 `autoAnsweredByAi=false` 直接返回，不创建引擎），此时开关会提示「这通电话可能不是 AI 接听的」。

**模型接入**

- 任意 OpenAI 兼容 `/chat/completions` 端点，填 Base URL + 模型名 + API Key
- 默认 `https://api.deepseek.com/v1` + `deepseek-chat`
- 支持 `reasoning_content` / `思考` 区块自动剥离，thinking 模型不会把内心戏念出来
- 超时秒数、保留对话轮数、全局人设提示词均可配

**通话记录**

每次通话逐轮记录「对方说 / 我说 / 耗时 / 判定类型」，用于调提示词和排查。

---

## 安装

**前提**：已 root（KernelSU / Magisk / APatch）+ LSPosed，Android 12 以上；系统「AI 通话」本身可用（设置 → 电话 → AI 通话，开启自动接听）。

1. 安装 `AICallAgent-x.x.apk`
2. 打开 LSPosed → 模块 → 启用 **AICallAgent**
3. 作用域**只勾选「小爱同学」**（`com.miui.voiceassist`），不需要勾系统框架或拨号
4. 软重启小爱（**不用重启手机**）：
   ```bash
   adb shell am force-stop com.miui.voiceassist
   adb shell am start -n com.miui.voiceassist/com.xiaomi.voiceassistant.ShareReceiverActivity
   ```
5. 打开 AICallAgent → 填 Base URL / 模型 / API Key → **保存设置**
6. 打一通电话，开 AI 接听，听它说的是不是你的模型的话

验证注入成功：

```bash
adb logcat | grep AICallAgent
# AICallAgent: 注入成功：com.miui.voiceassist (com.miui.voiceassist)
# AICallAgent: Hook 就绪，suspend 哨兵=COROUTINE_SUSPENDED
```

> 手机刚开机时小爱进程起得早，可能拿不到模块配置；遇到「注入了但没生效」，强停小爱再拉起即可。

### 配置示例

| 服务 | Base URL | 模型 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| 月之暗面 | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| Ollama（局域网） | `http://192.168.1.x:11434/v1` | `qwen2.5:7b` |
| one-api 中转 | 你的中转地址 + `/v1` | 对应模型名 |

---

## 构建

不需要 Gradle / Android Studio，只要有 JDK 11+ 和 Android SDK（build-tools + 任一 platform）：

```bash
export ANDROID_HOME=/path/to/Android/Sdk
bash build.sh
# 产物：AICallAgent-1.0.apk
```

脚本流程：`aapt2 compile` → `aapt2 link` → `javac` → `d8` → `jar` 塞 dex → `zipalign` → `apksigner`。

- 首次构建会自动生成 `keystore.jks`（密码 `aicallagent`），**请自行保管**，后续升级要用同一个签名
- 已有 keystore 时用 `KS_PASS=你的密码 bash build.sh`
- `libs/api82.jar` 是 Xposed API 82（Apache-2.0），已内置，clone 即可构建
- 换图标：改 `genicon.py` 后 `python genicon.py`

---

## 已知限制

- **强依赖类名**。`CallConversationEngine`、`handleCallerSentence`、`speakFn` 都是按名字反射找的。小爱版本升级后如果改名或混淆，模块会静默失效（日志里有「引擎钩子失败」）。目前没做方法签名兜底。
- **`speakFn` 的协程语义**。模块传了一个手搓的 `Continuation` 去调这个 suspend 函数。如果某版本改成非 `suspendCancellableCoroutine` 实现，可能出现「话播了但回合信号没回来」，表现是这一轮之后不再监听。日志里会缺「播报完成」。
- **只在文本态工作**。语音识别错误、方言、噪音，模块无能为力——它拿到的就是 ASR 的输出。
- **不做身份伪装**。默认话术是「机主现在不方便接电话」，但模型仍可能被套话。涉及验证码、转账、密码的场景，建议直接关掉对应类型的接入。

## 免责声明

仅供个人学习与研究。通话录音、语音内容涉及他人隐私，各地法律对自动接听与录音的规定不同，请自行确保合法合规使用。因使用本模块产生的一切后果由使用者承担。

## License

MIT
