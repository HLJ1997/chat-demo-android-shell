# chat-demo 安卓壳工程（最小可跑）

把 [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo) 的静态产物装进 Android WebView，做成一个可以独立安装、也可被其它 App 集成的壳。

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

语义等价（都返回新数组、不改原数组）。**如果要向 chat-demo 提 PR，这两行就是最小补丁**；更彻底的做法是把 `targets` 里的 `chrome >= 105` 提高到 110，或在构建里加 polyfill。


---

## 目录结构

```
android-shell/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/example/chatshell/MainActivity.kt
      └─ assets/www/                 ← chat-demo 的 dist/ 全量拷贝（153 文件 / 4.19 MB）
         ├─ index.html
         └─ static/{js,css,media}/…
```

## 构建

需要 **JDK 17 + Android SDK 34 + Gradle 8.7+**。最省事的是用 Android Studio 直接打开 `android-shell/` 目录（它会自动补齐 SDK 与 Gradle）。

命令行（需自行安装上述三者）：

```sh
cd android-shell
gradle wrapper --gradle-version 8.7   # 首次生成 wrapper（本仓库未附带 wrapper jar）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.chatshell/.MainActivity
```

最小支持 Android 8.0（API 26），目标 API 34。

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
