# chat-demo 安卓壳工程

把 chat-demo 的静态产物装进 Android WebView，做成一个可以独立安装、也可被其它 App 集成的壳。

> **前端主线**：不再依赖上游 `deepseek-ai/chat-demo`，改为使用我们的维护 fork
> [`HLJ1997/chat-demo`](https://github.com/HLJ1997/chat-demo)。原因见下方「为什么用自己的 fork」。

**当前状态：已在真机上完成构建、安装与功能验证（2026-11-16）。**


验证设备：Xiaomi M2007J1SC，Android 13（SDK 33），系统 WebView = **Chrome 108.0.5359.128**。

| 验证项 | 结果 |
| --- | --- |
| Gradle 构建 | `BUILD SUCCESSFUL`，首次 11m23s（含依赖下载），增量 12s |
| APK | `app-debug.apk` 3.31 MB，debug 签名，zipalign 校验通过 |
| 安装 | `adb install -r` → Success |
| 页面加载 | `title="API 使用 demo"`，`url=https://appassets.androidplatform.net/assets/www/index.html` |
| 存储 | `localStorage` ✓、`IndexedDB` ✓、`isSecureContext` ✓ |
| 媒体能力 | `navigator.mediaDevices.getUserMedia` 存在 ✓ |
| 真实对话 | 发送消息并收到模型回复 ✓ |
| 历史导入 | 提示「已导入 1 个对话」✓ |
| 导出下载 | 落到 `/sdcard/Download/deepseek_data-2026-11-16.zip`（2198 B，ZIP 结构合规）✓ |

### 验证中发现并修复的上游 bug（重要）

首次在真机发消息时报「**上传失败**」，根因不是网络也不是 CORS（实测 WebView 内 `fetch` 到 `api.deepseek.com` 返回 401，`type: 'cors'`，说明请求正常到达）。

真实原因是两个 **ES2023 数组方法**：

| 位置 | 代码 | 影响 |
| --- | --- | --- |
| `src/services/chat/store.ts:847` | `getContextRounds(...).toReversed()` | 发消息时抛 TypeError → 被 `preparing` 分支记成 upload 错误 → 显示「上传失败」 |
| `src/services/chat/history/toLocal.ts:247` | `file.messages.toSorted(...)` | 导入历史时同样抛错 |

`toReversed` / `toSorted` / `toSpliced` / `with` 需要 **Chrome 110+**，而 chat-demo 的 rspack 目标是 `chrome >= 105`——**存在 105–109 的版本缺口**，在这区间的 Android WebView 上「发消息」和「导入历史」两个核心功能都会挂。

本工程已把这处打包进 `assets/www`，做法是最小替换：

```diff
- for (const [user] of getContextRounds(request.history).toReversed()) {
+ for (const [user] of [...getContextRounds(request.history)].reverse()) {

- const sorted = file.messages.toSorted((a, b) => a.id - b.id)
+ const sorted = [...file.messages].sort((a, b) => a.id - b.id)
```

语义等价（都返回新数组、不改原数组）。

### 为什么用自己的 fork，而不是给上游提 PR

上游 `deepseek-ai/chat-demo` **关闭了 Pull Requests 与 Issues**：

```
has_pull_requests: false
has_issues: false
```

API 创建 PR 返回 `404`，`gh pr create` 报 `does not have the correct permissions to execute CreatePullRequest`——不是权限问题，而是仓库层面关掉了功能，任何人都提不了。

因此改为在 fork `HLJ1997/chat-demo` 的 `main` 上维护这个补丁（fork 里有 [`FORK-NOTES.md`](https://github.com/HLJ1997/chat-demo/blob/main/FORK-NOTES.md) 记录差异与同步上游的方法）。本仓库的前端产物由该 fork 构建而来，不再手工拼接。

如果上游将来开启 PR，可直接用 [这个比较链接](https://github.com/deepseek-ai/chat-demo/compare/main...HLJ1997:main) 提交。



---

## 目录结构

```
android-shell/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
├─ .github/workflows/android.yml    ← CI：构建 APK、打 tag 发 Release
├─ scripts/
│  ├─ sync-frontend.ps1             ← 从 fork 构建前端并同步 assets（Windows）
│  └─ sync-frontend.sh              ← 同上（Linux/macOS/CI）
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/example/chatshell/MainActivity.kt
      └─ assets/www/                 ← 由 scripts/sync-frontend.* 生成（153 文件 / 4.19 MB）
         ├─ index.html
         └─ static/{js,css,media}/…
```

## 同步前端（改了前端之后）

`assets/www` 是从前端 fork 构建出来的产物，**不要手改**。前端改动后重新同步：

```powershell
# Windows
.\scripts\sync-frontend.ps1
```

```bash
# Linux / macOS / CI
./scripts/sync-frontend.sh
```

默认从 `https://github.com/HLJ1997/chat-demo.git` 的 `main` 构建，可覆盖：

```powershell
.\scripts\sync-frontend.ps1 -Repo <仓库> -Ref <分支/提交>
```

脚本流程：浅克隆 fork → `pnpm install --frozen-lockfile` → `rspack build`（直接调用 rspack，绕过上游 `pnpm build` 的 Unix 语法）→ 覆盖 `assets/www`。

实测耗时约 7 秒（依赖已缓存时 3.9s 安装 + 790ms 构建），产物与已提交内容**逐字节一致**，可复现。

## 构建

需要 **JDK 17 + Android SDK 34 + Gradle 8.7+**。最省事的是用 Android Studio 直接打开 `android-shell/` 目录（它会自动补齐 SDK 与 Gradle）。

命令行（需自行安装上述三者）：

```sh
cd android-shell
gradle wrapper --gradle-version 8.7   # 首次生成 wrapper（本仓库未附带 wrapper jar）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或者直接用 CI 产出的 APK（见下）。

安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.chatshell/.MainActivity
```

最小支持 Android 8.0（API 26），目标 API 34。

## 持续集成

`.github/workflows/android.yml` 在 push 到 `main`、提 PR、或手动触发时：

1. 检出壳工程 → 安装 Node/pnpm
2. **从 fork 构建前端并同步 `assets/www`**
3. 校验同步结果与提交内容一致（不一致给 warning，防止「改了前端忘了同步」）
4. 安装 JDK 17 + Gradle 8.7 → `assembleDebug`
5. 上传 `app-debug.apk` 为构建产物

打 `v*` 标签时，会自动创建 GitHub Release 并附上 APK。

### 签名：为什么需要配置一个固定 keystore

GitHub runner 每次构建都会新生成 `~/.android/debug.keystore`，**签名证书因此每次不同**（实测三次运行的证书指纹分别为 `9c670b…`、`58a6b3…`、`3d49bf…`）。后果是：新版本无法覆盖安装旧的，用户必须先卸载——对走 Release 分发的项目不可接受。

解决办法是让 CI 复用同一个 keystore：

1. 本地生成并保存（只需一次）：

   ```sh
   keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
     -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
     -dname "CN=Android Debug,O=Android,C=US"
   base64 -w0 debug.keystore > debug.keystore.b64
   # Windows: [Convert]::ToBase64String([IO.File]::ReadAllBytes('debug.keystore'))
   ```

2. 在仓库 Settings → Secrets and variables → Actions 添加 secret `DEBUG_KEYSTORE_BASE64`，值为上一步的 base64 文本。

3. 工作流会把 base64 还原到 `$RUNNER_TEMP/keystore/debug.keystore`，并通过 **AGP 的注入签名属性**显式指定：

   ```
   -Pandroid.injected.signing.store.file=... -Pandroid.injected.signing.store.password=android
   -Pandroid.injected.signing.key.alias=androiddebugkey -Pandroid.injected.signing.key.password=android
   ```

   这里没有依赖 `~/.android/debug.keystore` 的默认路径——实测把 keystore 放到默认位置**并不会被采用**（三次运行仍是三个不同证书），必须用注入属性显式指定。配置后实测产物证书固定为 keystore 自身指纹。

未配置 secret 时行为不变（每次新 keystore），构建照常成功。

> 已发布的 `v0.1.0` 是在本修复**之前**构建的，其签名证书属于一次性 keystore。配置 secret 后重新打标签（例如 `v0.1.1`）即可获得稳定签名的产物。




## 三个关键设计

### 1. 用 WebViewAssetLoader 提供「属实源」

页面不通过 `file://` 加载，而是映射到：

```
https://appassets.androidplatform.net/assets/www/index.html
```

原因：`file://` 是不透明源，**localStorage / IndexedDB 在其中不可靠**，而 chat-demo 的会话与附件全在 IndexedDB，Key 和设置全在 localStorage。换成 https 属实源后，两者都正常，且 `getUserMedia` 也满足安全上下文要求。

### 2. 不做本地代理（有实测依据）

前端直接请求 `https://api.deepseek.com`。我实测过它的 CORS 行为：

- 预检 `OPTIONS` 返回 `access-control-allow-origin: https://appassets.androidplatform.net`（回显请求的 Origin）
- `access-control-allow-methods: POST`
- `access-control-allow-headers: x-api-key,content-type,anthropic-beta`
- 换任意 Origin（含 `https://evil.example.com` 和 `null`）同样被回显放行

所以 WebView 从 https 属实源直连 API 不会触发跨域拦截，**不需要代理层**。

> ⚠️ 附带的安全含义：该接口对任意 Origin 反射放行，意味着任何网页都能在用户浏览器里直接调用它。用 Key 鉴权是它唯一的防线，不要把 Key 暴露在前端可读的地方（本工程把 Key 仍存在 WebView 的 localStorage，这是 chat-demo 的原设计，若要更安全应改为原生侧持有 + 代理注入）。

### 3. 导出下载的桥接

chat-demo 的导出是 `URL.createObjectURL(blob)` + `a[download]`，而 **WebView 的 `DownloadListener` 接不到 `blob:` 地址**，会导致点「下载」无反应。工程里在 `onPageFinished` 注入了一段 shim：拦截带 `download` 属性的 blob 链接，转成 base64 后调 `window.AndroidBridge.saveFile(...)`：

- Android 10+：写入公共 `Downloads/`（MediaStore，无需存储权限）
- Android 9 及以下：写入应用私有目录 `Android/data/com.example.chatshell/files/Download/`（避免额外授权）

## 已处理的能力

| 能力 | 处理方式 |
| --- | --- |
| 对话 / 流式回复 | 直连 API，无需额外处理 |
| 会话与附件持久化 | AssetLoader 的 https 源 + `domStorageEnabled` / `databaseEnabled` |
| 语音输入（ASR） | `WebChromeClient.onPermissionRequest` 放行 `RESOURCE_AUDIO_CAPTURE` + 运行时申请 `RECORD_AUDIO` |
| 朗读（TTS） | `mediaPlaybackRequiresUserGesture = false`，避免首帧被静音策略拦住 |
| 导出下载 | JS shim + `AndroidBridge.saveFile` |
| 返回键 | 优先 `webView.goBack()` |
| 拼音/中文输入 | `windowSoftInputMode=adjustResize`，前端已有 `visualViewport` 适配 |

## 更新前端

chat-demo 更新后重新构建并覆盖即可：

```sh
cd ../chat-demo && pnpm install && pnpm build     # Windows 上见下面「已知问题」
rm -rf ../android-shell/app/src/main/assets/www/*
cp -r dist/* ../android-shell/app/src/main/assets/www/
```

## 已知限制

1. **本机未编译验证**：缺 JDK / Android SDK。代码按标准 API 写，但首次构建可能有小问题（如 AGP/Kotlin 版本组合）。
2. **WebView 版本**：chat-demo 的编译目标是 `chrome >= 105`。Android WebView 低于 105 的老设备可能报语法错误，建议加版本检测并引导用户更新 WebView。
3. **API Key 仍在 WebView localStorage**：沿用 chat-demo 原设计；若要生产化，建议改成本地代理注入（工程已预留 `apiOrigin` 可配置点，见 chat-demo 的 `createConfig({ apiOrigin })`）。
4. **导出文件名会被清洗**：shim 传给原生的名字做了 `[^A-Za-z0-9._-] → _` 处理，中文文件名会变成下划线，避免 MediaStore 兼容问题。
5. **未实现**：系统分享、多窗口、横竖屏适配的细节打磨、WebView 版本检测。

## 作为 SDK 集成到其它 App

当前是独立 App 工程。若要给别的 App 复用，最小改法是把它变成 **AAR 库**：

- `app/build.gradle.kts` 的 plugin 从 `com.android.application` 改为 `com.android.library`，去掉 `applicationId`
- 把 `MainActivity` 改成 `ChatShellView`（自定义 `FrameLayout`，内部创建 WebView）或提供一个 `ChromeActivity` 供宿主 `startActivity`
- `assets/www` 随库打包；宿主 App 合并 assets 时注意不要把同名目录覆盖掉（建议改成 `assets/chat_shell/www`）

注意：**没有宿主 App 的改动，这个壳无法「进入任意第三方 App」**——安卓没有跨应用注入 UI 的合法机制。
