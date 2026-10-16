# 自动上滑助手（AutoSwipeBall）

安卓悬浮球控制的「按周期上滑」插件：**非 Root**，通过 `AccessibilityService.dispatchGesture` 在屏幕中部执行上滑手势，
用于短视频等需要连续上滑的场景。仅注入上滑手势，**不模拟点击、不读取屏幕内容**。

- 语言 / 构建：Java 8 + Gradle 8.2 + AGP 8.2.2
- 兼容：`minSdk 26`、`targetSdk 34`；默认按运行时实测屏幕尺寸动态计算（真机 iQOO Neo9 Pro 实测 1080×2400 / 420dpi），其他机型自动适配
- 依赖：**零第三方依赖**（不使用 AndroidX / AppCompat），减少构建与 ROM 兼容风险

---

## 一、工程结构

```
app/src/main/java/com/autoswipe/ball/
├── App.java                          # Application：进程级事件总线 StateHub
├── core/                             # 纯 Java 逻辑层（不依赖 Android，可 javac 直接跑测试）
│   ├── ScreenMetrics.java            # 屏幕参数载体
│   ├── SafeArea.java                 # 滑动安全区
│   ├── SwipeConfig.java              # 可调参数 + 上下限收敛
│   ├── SwipeGeometry.java            # 坐标计算（本项目的核心算法）
│   ├── SwipeMotion.java              # 一次手势的几何描述
│   ├── RunState.java                 # 四种运行状态 + 状态色
│   ├── StateMachine.java             # 状态迁移 + 前置条件 + 故障语义
│   ├── StateHub.java                 # 状态/配置变更事件总线
│   ├── PrefsStore.java               # 唯一读写 SharedPreferences 的地方
│   └── MetricsReader.java            # 从 WindowManager 读真实屏幕参数
├── service/
│   ├── AutoSwipeAccessibilityService.java   # 只做手势注入
│   ├── AccessibilityHealer.java             # 无障碍被系统关闭后的自愈（读写安全设置）
│   ├── FloatingBallService.java             # 只做编排：悬浮窗 + 定时器 + 故障检测
│   └── NotificationHelper.java              # 前台服务常驻通知
└── ui/
    ├── BallView.java                 # 只负责画（颜色 + 图形 + 倒计时环）
    ├── BallTouchController.java      # 只负责手势识别（单击/长按拖动/双击）
    ├── MainActivity.java             # 权限引导 + 状态回显
    ├── SettingsActivity.java         # 参数调节 + 实时预览
    └── PermissionUtils.java          # 权限检测与跳转（含厂商自启动页）

verify/                               # 验证工程（不需要 Android SDK）
├── CoreLogicTest.java                # 205 项核心逻辑断言
├── resource_audit.py                 # 静态资源一致性审计
├── run_verify.sh                     # 一键跑完三项验证
├── test_grant_script.sh              # 用「假 adb」回归测试授权脚本（无需真机）
└── fake/adb.bat                      # 上述测试用的 adb 桩

tools/
├── build_apk.sh                      # 无 Android Studio 的 APK 构建脚本
├── grant_permissions.cmd             # 【Windows 双击版】一次性 ADB 授权
└── grant_permissions.sh              # 同上的 bash 版

文档
├── README.md                         # 本文件：架构 / 构建 / 验证 / 限制
├── 使用教程.md                        # 从安装到排障的完整教程
└── 一次性ADB授权指南.md                # 让无障碍不再被系统关掉：手把手步骤 + 失败对照表

dist/                                 # 已编译并签名的成品
└── AutoSwipeBall-1.0.2.apk
```

模块边界刻意做窄：**画 / 手势识别 / 状态机 / 手势注入 / 编排**五件事互不越界，
任何一处替换都不影响其他四处。

---

## 二、安装与构建

### 方式 0：直接装已编译好的 APK（最省事）

**产物**：`dist/AutoSwipeBall-1.0.2.apk`（54,251 字节）
**SHA-256**：`bd1cb2cb4ef2ea51ecffd0c99ff807ef153224d514214dc8e397c6574974e4b7`

已用本机工具链（JDK 17 + build-tools 34.0.0）实际构建并通过校验：
`package=com.autoswipe.ball`、`minSdk 26`、`targetSdk 34`、`foregroundServiceType=specialUse`、
v2 + v3 签名验证通过、zipalign 校验通过、dex 含全部 14 个业务类。

> **v1.0.1 变更**：新增无障碍自愈能力（`AccessibilityHealer` + `WRITE_SECURE_SETTINGS` 声明
> + 首页修复卡片 + 运行期静默自愈），可解决「清理后台后无障碍被系统关掉、每次都要手动重开」的问题。
> 详见 [三、无障碍被系统关掉怎么办](#无障碍被系统关掉怎么办自愈机制)。
>
> **v1.0.2 变更**：修正悬浮球落位坐标（补 `FLAG_LAYOUT_IN_SCREEN`）。
> 此前窗口原点被状态栏高度偏移，实测偏了 108px（球比预期低、且拖不到屏幕最顶端）。
>
> **v1.0.3 – v1.0.5 变更**：新增应用内检查更新（首页「版本更新」卡片 + 设置页可切换更新源）。
> 应用为此新增了 `INTERNET` 权限 —— 这是它第一次具备联网能力，范围**仅限**访问你自己配置的
> 更新源、读取版本信息与下载 APK；可以在设置里关掉自动检查，关掉后应用不会主动发起任何请求。
> v1.0.5 起默认更新源指向 Gitee（国内可达性最好），并修正了仓库所有者 —— 必须填 Gitee 的
> **个人空间地址**，填显示名会 404。
> 详见上文[应用内更新](#应用内更新v104-起)。
>
> 1.0.0 → 1.0.5 均为同签名覆盖升级，直接装即可，不会丢配置，
> **ADB 授予的 `WRITE_SECURE_SETTINGS` 也会保留，无需重跑授权脚本**（真机实测确认）。

> ### ✅ 已在真机上完整验证（2026-10-16，iQOO Neo9 Pro / Android 16）
>
> 见 **[真机验收报告.md](真机验收报告.md)**。核心结论：
> `specialUse` 前台服务被 Android 16 接受；强制停止后无障碍确实失效（名单→`null`、
> 总开关→`0`）；**运行中静默自愈实测 224 毫秒恢复、悬浮球全程不变红**；
> 打开应用一键恢复 21 毫秒；覆盖安装不丢授权。

安装方式任选：

```bash
# A. 手机已开「USB 调试」并用数据线连电脑
adb install -r dist/AutoSwipeBall-1.0.2.apk

# B. 把 APK 文件拷进手机，用文件管理器点击安装
#    需要先允许「安装未知来源应用」；小米需先关掉「纯净模式」
```

### 方式 1：不装 Android Studio，一条命令重新出包

工程自带了完整的命令行构建脚本（`aapt2 → javac → d8 → zipalign → apksigner` 全链路手工编译）：

```bash
# 一次性准备工具链（JDK 17 + Android SDK 34），全部装到 E 盘
export TOOLCHAIN=<工具链目录>
#   $TOOLCHAIN/jdk-17                           JDK 17（Temurin）
#   $TOOLCHAIN/android-sdk/build-tools/34.0.0   aapt2 / d8 / zipalign / apksigner
#   $TOOLCHAIN/android-sdk/platforms/android-34 android.jar

# 构建（签名口令必须通过环境变量传入，见下）
KEYSTORE_PASS='<你的签名口令>' bash tools/build_apk.sh
# 产物：dist/AutoSwipeBall-1.0.2.apk（首次会自动生成自签名密钥）
```

> **关于签名口令**：脚本**不把口令写进源码**（本仓库是公开的），
> 必须通过环境变量 `KEYSTORE_PASS` 传入，否则会直接报错退出。
> 若本机还没有签名密钥，脚本会在首次构建时自动生成一把并用该口令保护它。
> 内部实现用的是 keytool 的 `-storepass:env` 与 apksigner 的 `--ks-pass env:`，
> **口令不会出现在命令行参数里**（避免被 `ps` 看到或落进构建日志）。

> 脚本刻意把构建目录放在**不含空格**的路径下（`$TOOLCHAIN/work`），
> 因为 `d8.bat` / `apksigner.bat` 对带空格的路径支持不佳。
>
> 另外脚本依赖 Git-Bash 的路径自动转换，若外部设了 `MSYS_NO_PATHCONV=1`，
> 脚本会在内部临时关掉它（否则 Java 工具收到 `/e/xxx` 形式的路径会报文件找不到）。

### 方式 2：Android Studio

直接 Open 本目录，IDE 会自动补 `gradle-wrapper.jar` 并同步依赖，然后点 Run。

### 方式 3：Gradle 命令行

```bash
gradle wrapper          # 首次生成 wrapper
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **C 盘洁癖提示**：Gradle 默认把缓存写到 `C:\Users\<你>\.gradle`（轻松几个 GB）。
> 建议在环境变量里设置 `GRADLE_USER_HOME=E:\gradle-home`，把构建缓存挪到 E 盘。

---

## 三、使用流程

1. 打开 App，在「权限准备」里依次点开：
   - **悬浮窗权限**（显示在其他应用上层）
   - **无障碍服务**：找到「自动上滑助手」并开启
   - **电池优化白名单**（非必需，但强烈建议）
   - iQOO / vivo（OriginOS）额外点「自启动 / 后台白名单」，把本应用加入白名单并允许后台高耗电
2. 点「启动悬浮球」→ 悬浮球出现在屏幕右侧偏上位置（可长按拖动）。
3. **单击**悬浮球开始运行（绿色 + 倒计时环）；**再次单击**暂停（琥珀色）；**双击**退出。
4. 需要的间隔/距离/时长在「参数设置」里调，点「保存并立即生效」。

### 无障碍被系统关掉怎么办（自愈机制）

**背景**：Android 把「哪些无障碍服务处于开启状态」记录在系统设置
`enabled_accessibility_services`（冒号分隔的名单）里。当本应用被 **force-stop**
（强行停止、国产 ROM 的「一键清理 / 一键加速」、省电策略杀进程）时，
**系统会把本服务从这份名单里移除**，于是下次打开应用就发现无障碍又关上了。
这是 Android 的设计（防止应用靠无障碍权限赖着不走），不是插件 bug，
而且**任何第三方应用都无法在自己被杀之后把自己加回来**——进程已经死了。

**本项目给出的解法（三级）**：

| 级别 | 机制 | 需要什么 | 效果 |
|---|---|---|---|
| 运行期静默自愈 | `FloatingBallService` 每约 1.5s 体检，发现无障碍断连先调 `AccessibilityHealer.heal()` 最多 3 轮再判故障 | 一次性 ADB 授权 | 不改状态、不掉线，用户完全无感 |
| 打开即修复 | 首页在无障碍缺失时显示红色修复卡片，「一键自动恢复」写回名单 | 一次性 ADB 授权 | 点一下即恢复，不需进系统设置翻列表 |
| 手动路径 | 「手动开启」直达**本服务自己的**无障碍详情页（`ACTION_ACCESSIBILITY_DETAILS_SETTINGS` + `EXTRA_COMPONENT_NAME`） | 无 | 未授权时的最优手动路径，点一下开关即可 |

**一次性授权**（`WRITE_SECURE_SETTINGS` 的 protectionLevel 含 `development`，只能由 ADB 授予）：

```
双击  tools\grant_permissions.cmd          # Windows 推荐
# 或 bash tools/grant_permissions.sh       # bash 环境
# 最小手动版（一条即可）：adb shell pm grant com.autoswipe.ball android.permission.WRITE_SECURE_SETTINGS
```

手把手的步骤、失败对照表、以及「开关是灰的点不动怎么办」等细节见
**[一次性ADB授权指南.md](一次性ADB授权指南.md)**。

**安全边界**：`AccessibilityHealer` 只读写 `enabled_accessibility_services` 与
`accessibility_enabled` 两个键，写入时用 `LinkedHashSet` **保留名单中其他无障碍服务的条目**，
绝不整表覆盖、不触碰任何其他安全设置。授权在卸载重装后失效。

**未授权时**：应用所有功能照常工作，只是无障碍被系统关闭后需要手动开一次
（应用会把用户直接送到正确的开关页）。「参数设置」里可关闭自动恢复开关。

### 悬浮球交互

| 操作 | 行为 |
|---|---|
| 单击 | 未启动/已暂停 → 开始运行；运行中 → 暂停；异常停止 → 尝试恢复 |
| 长按（400ms）后拖动 | 移动悬浮球位置（限制在屏幕内） |
| 双击（300ms 内两次） | 退出插件（停止服务、移除悬浮球） |

### 状态表现

| 状态 | 颜色 | 图形 | 触发条件 | 恢复动作 |
|---|---|---|---|---|
| 未启动 | 灰 `#7A8087` | ▶ | 初始状态 / 主动停止 / 双击退出 | 单击悬浮球 |
| 运行中 | 绿 `#22C55E` | ⏸（提示点击暂停） | 前置条件齐备 + 用户单击开始 | — |
| 已暂停 | 琥珀 `#F59E0B` | ▶ | 运行中单击 | 单击继续 |
| 异常停止 | 红 `#EF4444` | ❗ | 无障碍断开 / 悬浮窗权限被回收 / 服务被系统中断 / 手势连续注入失败 | 处理权限后手动单击恢复 |

异常停止时同时弹出 Toast 说明原因、更新通知栏文案、主界面状态区变红——
**不会静默自动重试**，避免权限缺失时反复触发失败。

---

### 应用内更新（v1.0.4 起）

打开应用 → 「版本更新」卡片 → 点「检查更新」。发现新版本后按钮变成「下载并安装」，
点一下自动下载；系统随后弹出安装确认界面，点「安装」结束。

> **为什么最后仍要点一下？** 非系统应用在 Android 上无法静默安装自己。那一下确认是
> 系统强制的安全设计，任何侧载应用都一样。真正的零点击需要 Shizuku（借 ADB 启动的
> shell 进程）或 root，本应用两者都不依赖。

**自动检查**：打开应用时，若距上次检查超过 6 小时、且当前在 Wi-Fi 上，会静默查一次
（不弹窗，失败也不打扰）。两个条件都可在设置里改，也能整个关掉——关掉之后应用
**不会主动发起任何网络请求**，只有你亲手点按钮时才联网。

**更新源**（设置 → 更新源，可随时切换）：

| 源 | 实际请求的地址 | 备注 |
|---|---|---|
| **Gitee**（默认） | `gitee.com/<用户>/<仓库>/raw/<分支>/dist/version.json` | 国内可达性最好 |
| GitHub | `api.github.com/repos/.../contents/...` | 走 API 而非 raw 域名 —— `raw.githubusercontent.com` 在国内通常不可达 |
| 自定义 | 你填的任意 `http(s)://` 地址 | 指向一个放着 `version.json` 与 APK 的目录，可用于局域网 |

**换源不需要重装应用**——这正是把更新源做成配置项的原因：网络环境会变，
不该为此重新发一次版。

**发现机制**：应用读仓库里的 `dist/version.json`，与本地 `versionCode` 比较。

```json
{
  "versionCode": 8,
  "versionName": "1.0.5",
  "apkFileName": "AutoSwipeBall-1.0.5.apk",
  "sizeBytes": 78000,
  "sha256": "…64 位十六进制…",
  "notes": "会显示在更新提示里"
}
```

把元数据放在仓库文件而不是 GitHub Releases，是为了让「发布」退化成一次 `git push`：
不需要 API token，也不必去网页上点几下。

**本项目当前实际使用的地址**（排查时可直接访问验证）：

```text
元数据  https://gitee.com/xiaohei1234525/auto-swipe-ball/raw/main/dist/version.json
APK     https://gitee.com/xiaohei1234525/auto-swipe-ball/raw/main/dist/AutoSwipeBall-<版本>.apk
```

> **Gitee 有三个实测踩到的特性**，换源或排查时值得知道：
>
> 1. **它有两个名字**：显示名与**个人空间地址**（URL 里用的那段）。两者可能完全不同
>    （例如显示名 `xiaohei123`、空间地址 `xiaohei1234525`）。**URL 必须用空间地址**，
>    用显示名会 404。填错会在任何时候都读不到版本信息。
> 2. **raw 地址不支持 HEAD 请求** —— 用 `curl -I` 测会返回 404，但 `curl -L`（GET）正常。
>    所以不要用 HEAD 来判断"地址是否可用"，会误判。
> 3. **raw 会 302 重定向到 `raw.giteeusercontent.com`**（带签名参数的 CDN 地址），
>    且 **CDN 有约 45 秒缓存**。含义有二：下载实现必须跟随 301/302/307/308
>    （本项目的 `UpdateManager` 已处理）；刚发布完手机上不会立刻看到新版本，等半分钟即可。

**下载安全**：元数据被视为不可信输入（必填字段缺失、版本号非整数、文件名含路径分隔符
一律拒绝）；下载有字节上限；元数据提供 `sha256` 时下载后逐字节校验，不匹配即丢弃。

**发布端**（维护者）：一条命令完成发版。

```bash
KEYSTORE_PASS='<签名口令>' bash tools/release.sh 1.0.5 "这次改了什么"
# 改版本号(versionCode 自动 +1) → 构建 → 生成含 SHA-256 的 version.json
# → 交叉校验 APK 内嵌版本号与元数据一致 → 提交推送
```

### 以后怎么继续迭代

更新链路打通后，改一个功能并送到手机上的完整流程是：

| 步骤 | 谁做 | 做什么 |
|---|---|---|
| 1 | 使用者 | 提出要改什么 |
| 2 | 维护者 | 改代码 → 跑 `verify/run_verify.sh` → `tools/release.sh` 发版并推送 |
| 3 | 使用者 | 手机上：检查更新 → 下载并安装 → 系统确认 |

**不需要**：连电脑、拷贝文件、重装应用、重新授权无障碍。

**维护所需的全部要素**（都在本机 E 盘，不依赖 C 盘）：

| 要素 | 位置 |
|---|---|
| 工程目录 | 本目录 |
| 构建工具链（JDK 17 / Android SDK 34 / adb） | `E:\workbuddy-toolchain\` |
| 签名密钥 | `E:\workbuddy-toolchain\autoswipe-release.jks` |
| 密钥口令与构建说明 | `E:\workbuddy-toolchain\README-构建说明.md` |
| 远程仓库 | `origin`（GitHub）、`gitee`（Gitee —— 应用实际使用的更新源） |
| 项目记忆（跨会话恢复上下文） | 工作区 `.workbuddy/memory/` |

**发版前必做**：`bash verify/run_verify.sh`，要 205 项断言、资源审计、语法检查全绿才发。

**两条不能忘的约束**：

1. **签名密钥绝对不能换。** 换了之后手机上无法覆盖安装，只能卸载重装 —— 那会连带丢掉
   ADB 授予的 `WRITE_SECURE_SETTINGS`（需重跑一次授权脚本）。
   `release.sh` 已内置「APK 内嵌版本号 vs 元数据声明值」的交叉校验，但密钥一致性靠人守。
2. **发版后 Gitee 有约 45 秒 CDN 缓存**，手机上不会立刻看到新版本，稍等即可。

---

## 四、滑动坐标是怎么算的

坐标**全部动态计算，无任何硬编码像素**（`SwipeGeometry`）：

```
安全区（避开系统手势与状态栏/导航栏）
  left   = W × 8%
  right  = W × 92%
  top    = max(状态栏高 + H×5%,  H×12%)
  bottom = min(H − 导航栏高 − H×5%, H×86%)

起点  X = (left+right)/2   ← 水平居中，天然避开右侧点赞/评论/分享按钮列
      Y = top + 安全区高 × 70%
终点  X 同起点（纯竖直上滑）
      Y = 起点Y − 距离
距离  = 安全区高 × 可调比例（默认 40%），并收敛到 [安全区15%, 安全区60%]
      且保证终点不越过安全区上边界、起点不压到下边界
```

系统栏信息读不到（部分 ROM / 异常横屏）时，自动回退到屏幕中部保守比例区（12%~86%），并置 `fallback=true`。

**iQOO Neo9 Pro 实测参数（1080×2400，420dpi，状态栏 108px，导航栏 约 100px）**：

```
安全区 x[101, 1159]  y[336, 2408]
起点 (630, 1786)  →  终点 (630, 958)   位移 828px，时长 300ms
```

起点约在屏幕高度 63.8% 处、终点 34.2% 处，距底部导航栏还有 890px 余量，X 恰好在正中。

---

## 五、参数与生效规则

| 参数 | 默认 | 范围 | 生效时机 |
|---|---|---|---|
| 上滑间隔 | 15 秒 | 5–120 秒 | 保存后**立即**从「当前时间 + 新间隔」重新计时 |
| 滑动时长 | 300 ms | 100–1000 ms | **下一次上滑**时生效 |
| 滑动距离（占安全区高度） | 40% | 15%–60% | **下一次上滑**时生效 |
| 悬浮球倒计时环 | 开 | 开/关 | 立即 |

未点「保存并立即生效」时只在页面内预览，不写入配置。

---

## 六、电量与轮询策略

- 未启动 / 异常停止：**完全无轮询**，只有悬浮球静止在屏幕上。
- 运行中 / 已暂停：250ms 的 UI 节拍用于倒计时环；每 6 拍（约 1.5s）做一次环境体检（悬浮窗权限、无障碍连接）。
  倒计时环关闭时会跳过大部分重绘。
- 拖动时屏幕尺寸走缓存，不在每个 MOVE 事件里查询系统。
- 仅使用 `Handler + postDelayed`，不引入协程/线程池等额外开销。

---

## 七、验证证据（无需 Android SDK 即可复现）

```bash
bash verify/run_verify.sh
```

实测输出（2026-10-16）：

```
[1/3] 核心纯逻辑单元测试（8 个纯 Java 类）
      通过: 205  失败: 0       结果: ALL PASS
      覆盖：iQOO 基准机型坐标 / 5 种分辨率 × 3 档距离的边界不变量 /
            系统栏缺失回退 / 参数上下限收敛（含 0 与 NaN）/
            状态机 7 步迁移 / 故障语义 / 服务中断重启标记 / 事件总线 / 时长跟随
[2/3] 静态资源一致性审计
      Java 资源引用 62 处、findViewById 22 处、带参格式化 7 处、Manifest 5 项
      结果: ALL PASS
[3/3] 全量源码语法级编译检查
      编译报错总数: 413（全部为缺 Android SDK 导致的 package/symbol 未解析）
      语法类错误数: 0
      全部验证通过 (ALL PASS)
```

> 第 3 项的原理：本机无 Android SDK，`javac` 会产出大量「找不到符号 / 程序包不存在」，
> 但**语法错误与符号错误是两类独立诊断**。脚本只断言「语法类错误 = 0」，
> 配合第 2 项静态资源审计，可在无 SDK 环境下拦掉绝大多数编译期问题。

### 7.1 APK 构建与产物校验（已于 2026-10-16 实际执行）

工具链：JDK 17（Temurin 17.0.20.1）+ build-tools 34.0.0 + platforms;android-34，全部装在 `<工具链目录>/`。
构建命令：`KEYSTORE_PASS='<口令>' bash tools/build_apk.sh`，全链路无报错。
（签名证书 SHA-256：`c809571d9f83f7121ddbb09cc75bde24413a1add9e54b24a54a68404d39d79e6`，
改动口令读取方式后重新构建，指纹不变，可覆盖升级。）

```
产物      dist/AutoSwipeBall-1.0.2.apk        54,251 字节
SHA-256   bd1cb2cb4ef2ea51ecffd0c99ff807ef153224d514214dc8e397c6574974e4b7

aapt2 dump badging
  package   com.autoswipe.ball  versionCode=3  versionName=1.0.2
  sdkVersion 26   targetSdkVersion 34
  6 项权限全部写入（SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE /
  FOREGROUND_SERVICE_SPECIAL_USE / POST_NOTIFICATIONS / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS /
  WRITE_SECURE_SETTINGS）
  launchable-activity com.autoswipe.ball.ui.MainActivity

aapt2 dump xmltree
  FloatingBallService.foregroundServiceType = 0x40000000（specialUse，API 34 要求）
  AutoSwipeAccessibilityService 带 BIND_ACCESSIBILITY_SERVICE 权限 + meta-data 配置

apkanalyzer dex packages
  14 个业务类（含 AccessibilityHealer）+ R 及其子类 + lambda 合成类全部进入 classes.dex
  未引入任何第三方库

apksigner verify -v
  Verifies / v2 scheme: true / v3 scheme: true / 1 signer
zipalign -c -v 4
  Verification succesful
```

> **✅ 已由真机验证（原「未验证项」现已关闭）**：2026-10-16 在 iQOO Neo9 Pro
> （V2339A / Android 16 / API 36）上实测通过。关键数据：
>
> | 场景 | 结果 |
> |---|---|
> | 运行中清空无障碍名单 | **224 毫秒**自动写回，服务重新绑定，悬浮球**全程保持绿色** |
> | 强制停止后打开应用点「一键自动恢复」 | **21 毫秒**恢复，无障碍总开关 `0 → 1` 一并写回 |
> | 清后台 → 打开应用 → 点「启动悬浮球」 | 无障碍自动恢复 + 悬浮球正常出现 |
>
> 同时发现：force-stop **不只删名单里的条目，还会把总开关 `accessibility_enabled` 置 0**，
> 而 `heal()` 本来就同时写两个键，实测确认生效。
>
> 逐步复现方法与原始日志见 **[真机验收报告.md](真机验收报告.md)**。

### 7.2 授权脚本的回归测试（无需真机）

授权脚本在没有手机的情况下也被回归测试过：`verify/test_grant_script.sh` 会造一个**有状态的假 adb**
（按命令返回预设输出、且 `put` 后能被 `get` 读回），把真实 `.cmd` 的 adb 定位块替换成指向它，
跑完整流程并断言结果。两个场景都覆盖：

```bash
bash verify/test_grant_script.sh
# 场景 1（手机装的是 1.0.2，已声明权限）→ 应走成功路径
#    [ok] 设备已连接 / 应用已安装 / 版本 1.0.2
#    [ok] 确认权限 granted=true
#    [ok] 写入值保留原有其他服务条目   ← 关键：不破坏别人的无障碍服务
#    [ok] 回读校验：名单中已包含本服务  ← 端到端验证写入真的落盘
#    [ok] 结论：成功
# 场景 2（手机装的是 1.0.0，未声明权限）→ 应识别并引导安装新版本
#    [ok] 提示「没有声明 WRITE_SECURE_SETTINGS」/ 给出 APK 路径 / 不误写名单
# 结果: ALL PASS
```

> 这个测试先后抓到四个真实问题：
> ① `.cmd` 里**不加 `call`** 直接调用另一个 `.bat`，控制权不返回、脚本提前结束；
> ② 变量为空时 `echo !VAR!` 打印「ECHO 处于关闭状态」，看着像报错；
> ③ **拼接无障碍名单前没有归一化空白** —— 若设备返回值带行尾空格，
> 会拼成 `com.a/b :com.c/d` 这种非法名，**反而弄坏用户原有的无障碍服务**。
> 现在读取后统一去掉空格（组件名不可能含空格），并有断言守着。
> ④ **【真机踩到】权限判定把成功误报成失败**：`for /f` 只保留最后一行，
> 而真机 `dumpsys` 里同一条权限有 3 行（机主 `granted=true` /
> `userId=666` false / `userId=999` false，后两个是 XSpace 与应用分身空间），
> 最后一行恰好是应用分身的 false。现在只认 `granted=true` 那一行，
> 且假 adb 会刻意输出这三行来守住回归。

---

## 八、已知限制与未验证项（如实披露）

1. **已完成的验证**：APK 构建、签名、静态校验，以及**在 iQOO Neo9 Pro（Android 16）上的安装与运行**
   （详见 [真机验收报告.md](真机验收报告.md)）。已确认：`specialUse` 前台服务被 ROM 接受、
   通知栏常驻通知正常、悬浮球位置正确、无障碍自愈全链路可用。
   **仍未做的**：打开真实短视频 App 连续观察十几分钟，确认每次上滑都到位、无误触。
   厂商「一键清理」与 `am force-stop` 并不完全等价，但无论谁清掉名单，自愈机制的结果一致。
2. **短视频 App 界面差异**：若某个 App 的上滑手势与「下拉刷新 / 侧滑返回」冲突，
   需通过「滑动距离」调小或把悬浮球拖到更靠中间的位置来规避，代码层面已保证 X 居中。
3. **应用被彻底杀掉时，它自己救不了自己**：进程都死了就没有代码能执行，这是平台限制，
   不是插件的缺陷。所以自愈总有「触发时机」——运行中的 1.5 秒体检，或下次打开应用时。
4. **厂商自启动页 Intent 会随 ROM 版本变化**：已内置 vivo/iQOO、小米、华为、OPPO 等候选组件，
   全部不可用时自动退到「应用详情页」，需用户手动找「自启动」入口。
5. **双击退出有误触风险**：300ms 内的两次点击即退出。若觉得容易误触，可把
   `BallTouchController.DOUBLE_TAP_MS` 调大，或改为长按 + 侧滑退出。
6. **前台服务被系统清理后的重启**：靠 `START_STICKY` + 上次运行状态落盘判断，
   重启后进入「异常停止」而非自动继续运行（刻意为之，避免用户以为还在跑）。
   注意此时**无障碍可能已经被自动修好了**，球变红只是因为「服务被中断过」，
   点一下球即可继续 —— 不需要再去设置里开无障碍。
7. **应用分身 / 多用户空间的坑**：若在「应用分身」里也装了本应用，ADB 授权只作用于机主空间，
   分身空间需另行处理。授权脚本的判定逻辑已针对多用户空间做过修正（见 7.2）。

---

## 九、合规与安全边界

- 不申请 `canRetrieveWindowContent`，不读取任何界面内容与用户数据；
- 只注入上滑手势，不模拟点击，不触发点赞/评论/关注；
- 不依赖 Root，不注入非必要权限，不写死坐标；
- 所有权限跳转都带 `ActivityNotFoundException` 降级，不在国产 ROM 上崩溃。
