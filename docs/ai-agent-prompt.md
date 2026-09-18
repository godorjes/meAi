# 任务：在 Windows 电脑上运行并修改 Android 应用 Polyglot

你是一名可以直接操作本机命令行的 AI 助手。请按本文的既定方式运行、调试并修改这个安卓项目。本文的命令全部实测可用，**请优先复用，不要自行发明新路径或重新安装环境**。

## 0. 使用方式

把本文件整份作为提示词提供给 AI 即可。它假设 AI 能执行 shell 命令（Windows + Git Bash）。

## 1. 项目概况

**后续已确认要求：先阅读 `docs/requirements-follow-up.md`。其中的数据不丢失要求是最高优先级待办，目前尚未实现和验收；不能把现有持久化代码当作已满足要求。**

| 项目 | 值 |
| --- | --- |
| 工作目录 | `F:\code\meAi`（Git Bash 下为 `/f/code/meAi`） |
| 应用名 | Polyglot（自备模型 API Key 的对话 App） |
| 包名 / applicationId | `app.polyglot.dev` |
| 启动 Activity | `app.polyglot.dev/.MainActivity` |
| 语言与框架 | Kotlin 2.0.21 + Jetpack Compose（Material3）+ Room + KSP |
| 构建工具 | Gradle 8.9（wrapper）+ AGP 8.7.3 |
| 版本 | versionCode 1 / versionName 0.1.0 |
| minSdk / targetSdk / compileSdk | 26 / 34 / 35 |
| APK 产物 | `app/build/outputs/apk/debug/app-debug.apk` |

**重要：本工作区不是 git 仓库**，没有版本控制兜底。改动前先读清楚相关文件，避免覆盖他人未提交的工作。

源码结构（`app/src/main/java/app/polyglot/dev/`，共 22 个 kt 文件）：

```
MainActivity.kt, PolyglotApp.kt
data/crypto/KeyCrypto.kt
data/local/{AppDatabase,Daos,Entities,Prefs}.kt
data/net/OpenAiClient.kt
data/repo/{CatalogRepository,ChatRepository,ConfigRepository}.kt
feature/chat/{ChatScreen,ChatViewModel,MarkdownText,SessionDrawer,SwitchModelSheet}.kt
feature/config/{ConfigEditScreen,ConfigListScreen,ConfigViewModel}.kt
feature/onboarding/OnboardingScreen.kt
ui/theme/Theme.kt
work/CatalogRefreshWorker.kt
```

两份必须参考的资料：

- `docs/android-v1-spec.md` —— 产品与交互规格，是需求的事实来源（首版范围、页面导航、交互约定都在里面）。
- `ui-preview.html` —— 视觉参考稿。

规格里部分功能属"后续再评估"（登录、云同步、附件、语音、知识库等），**首版不应实现**，也不要在界面上放置未实现的入口。

## 2. 环境（已全部就绪，不要重装）

| 组件 | 位置 |
| --- | --- |
| Android SDK | `F:\android-sdk`（`build-tools/34.0.0`、`platform-tools`、`platforms/android-35`、`emulator`、`system-images/android-35/google_apis/x86_64`） |
| JDK 17 | `F:\gradle\jdk17`（Temurin 17） |
| AVD | `polyglot_api35`（Pixel 9 / Android 15 / x86_64） |
| 硬件加速 | AEHD 2.2.0 驱动，服务名 `aehd`，状态应为 RUNNING |

**本机 Hyper-V / WHPX / VirtualMachinePlatform 均为关闭状态，模拟器靠 AEHD 加速。不要再启用 Hyper-V，否则 AEHD 会失效。** AEHD 是 `SYSTEM_START`，开机自启，无需手动启动。

环境若需重建，执行 `bash scripts/android-sdk-setup.sh`（幂等）；卸载驱动用 `--remove-driver`。

## 3. 命令速查（在 Git Bash 中执行）

先设好变量，后续命令统一用它：

```bash
export JAVA_HOME="F:/gradle/jdk17"
SDK=/f/android-sdk
ADB="$SDK/platform-tools/adb.exe"
EMU="$SDK/emulator/emulator.exe"
PKG=app.polyglot.dev
```

**关键：`JAVA_HOME` 必须指向 JDK 17。** 系统 PATH 里的默认 `java` 是 JDK 11，AGP 8.7.3 无法用它构建。同理，`adb` / `emulator` 都不在 PATH，一律用全路径。

### 启动与关机

```bash
# 启动模拟器（后台运行；有快照时会快速启动）
"$EMU" -avd polyglot_api35 -no-boot-anim &

# 等待开机完成（返回 1 才算开机结束）
"$ADB" wait-for-device
"$ADB" shell getprop sys.boot_completed

# 干净重启 Android 系统（秒级，窗口不关）
"$ADB" reboot

# 关闭模拟器
"$ADB" emu kill
```

需要全新状态（清空 App 数据和账号）时，启动命令追加 `-wipe-data`。

### 构建、安装、启动

```bash
cd /f/code/meAi

# 一条命令完成：构建 + 安装到已运行的模拟器（实测约 10s，增量）
JAVA_HOME="F:/gradle/jdk17" ./gradlew installDebug --console=plain

# installDebug 只安装不拉起页面，需再启动 App
"$ADB" shell am start -n app.polyglot.dev/.MainActivity
```

**改完 APK / 代码后不需要重启模拟器**，覆盖安装即可生效。手动安装用：

```bash
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am force-stop app.polyglot.dev
"$ADB" shell am start -n app.polyglot.dev/.MainActivity
```

### 客观验证（重要：不要只凭"命令执行成功"就宣称界面正常）

如果模型无法查看图片，用下面的手段**读取界面的真实内容**：

```bash
# 1) 读取当前屏幕的全部文本（最可靠的验收手段）
"$ADB" shell uiautomator dump /sdcard/ui.xml
"$ADB" shell cat /sdcard/ui.xml | tr -d '\r' > /tmp/ui.xml
grep -oE 'text="[^"]+"' /tmp/ui.xml | sed 's/text="//;s/"$//' | grep -v '^$'
grep -oE 'content-desc="[^"]+"' /tmp/ui.xml | sed 's/content-desc="//;s/"$//' | grep -v '^$'
# 节点数量用 -oE 计数（XML 是单行，grep -c 会永远返回 1，不要用它数节点）
grep -oE '<node ' /tmp/ui.xml | wc -l

# 2) 截图（需要人眼确认时）
"$ADB" exec-out screencap -p > /tmp/shot.png

# 3) 崩溃与异常
"$ADB" logcat -d -b crash | tail -30
"$ADB" shell pidof app.polyglot.dev        # 进程还在说明没崩

# 4) 确认当前前台页面
"$ADB" shell dumpsys activity activities | grep -iE "topResumedActivity" | head -1
```

无法看图时，还可以用像素统计判断"界面是不是空白"：用 PowerShell + `System.Drawing` 对 PNG 按 8px 网格采样，统计不同颜色数。正常界面约 300 色，纯空白页只有 3~13 色。

模拟屏幕分辨率 1080×2424。**点击控件**：先 dump，从目标节点的 `bounds="[x1,y1][x2,y2]"` 算中心点，再执行：

```bash
"$ADB" shell input tap <x> <y>
"$ADB" shell input keyevent KEYCODE_BACK      # 返回
```

### 排查日志

```bash
"$ADB" logcat -d | grep -iE "polyglot|AndroidRuntime|FATAL EXCEPTION" | tail -30
"$ADB" shell dumpsys package app.polyglot.dev | grep -iE "versionCode|lastUpdateTime"
```

## 4. 已知陷阱（踩过一次，务必避开）

1. **`JAVA_HOME` 不设或指向 JDK 11 → 构建直接失败。** 每条 gradle 命令都要带上 `JAVA_HOME="F:/gradle/jdk17"`。
2. **debug 包首次进入某个 Compose 页面会白屏数秒，这是正常的。** 首次组合新页面要 JIT 编译，日志里能看到 `Compiler allocated ...KB to compile <Screen>Kt`，首帧耗时可能达数十秒。**观察到白屏时先等 10~20 秒再判断**，不要立刻判定为 UI bug。第二次进入就很快。
3. **系统的 `signal 3`（SIGQUIT，抓取堆栈）不等于 App 的 ANR。** 若同一时刻多个互不相关的进程（`gms.unstable`、`messaging`、`wellbeing` 等）都出现 `reacting to signal 3`，那是 Android 的例行全系统 trace 收集。只有单独针对本 App 且伴随 `ANR in` 才是真问题。
4. **Git Bash 向 `cmd` 传参时引号会被破坏。** 不要用 `cmd //c "cd /d ... && xxx.bat"` 这种写法（实测静默不执行）。需要调用 `.bat` 时，用 `cmd /c "完整路径.bat 参数"`；调用 Java 类时直接用 `java.exe` 加 `MSYS_NO_PATHCONV=1` 前缀，避免 MSYS 改写路径。
5. **`MSYS_NO_PATHCONV=1` 下不要给 `adb pull` 传 `/tmp/...` 之类 Unix 路径** 作本地目标，会报 cannot create file。改用 `adb shell cat` 管道读回。
6. **`avdmanager` 的 toolsdir 属性名是 `com.android.sdkmanager.toolsdir`**，classpath 用 `lib/avdmanager-classpath.jar`（不是 `lib/*`），否则报 "tools directory property is not set"。
7. 模拟器日志里 `Failed to load opengl32sw → Falling back to system OpenGL` 与 `UpdateLayeredWindowIndirect failed` 均为无害提示（本机 CPU 无核显）。截图与渲染不受影响。

## 5. 工作循环

每次修改都按这个闭环走，不要跳过验证：

1. **读规格与现有实现**：先看 `docs/android-v1-spec.md` 对应章节，再读要改的 kt 文件，保持既有代码风格（Compose 惯例、命名、注释密度）。
2. **改代码**：只改必要文件；不要引入规格里标记为"后续再评估"的功能。
3. **构建安装**：`JAVA_HOME="F:/gradle/jdk17" ./gradlew installDebug --console=plain`
4. **启动并验证**：`am start` → 等界面稳定 → `uiautomator dump` 读出文本核对，必要时截图。
5. **检查回归**：`logcat -d -b crash` 应为空；`pidof` 应返回进程号；确认前台仍是 `MainActivity`。
6. **报告**：说明改了什么、验证到什么程度、哪些没验证。**不要把"命令成功"当成"功能正确"**，也不要在没实际验证时声称已完成。

## 6. 交付要求

- 回复用中文。
- 说明改动涉及的文件与理由；如果发现规格与实现不一致，指出来而不是默默改掉规格。
- 只在真实验证后宣称成功；失败就贴出关键日志和报错。
- 需要人眼确认界面时，把截图存到 `F:\code\meAi\docs\screenshots\` 并给出路径。
