# 上游问题报告：Chrome 105–109 WebView 上「发消息」与「导入历史」失效

> 针对 `deepseek-ai/chat-demo`（commit `5d5dd75`，v0.1.1）
> 发现时间：2026-11-16，Android 13 真机验证时

## 结论

`deepseek-ai/chat-demo` 的 rspack 编译目标是 `chrome >= 105`，但源码里用了两个**需要 Chrome 110+** 的 ES2023 数组方法。在 Android WebView 105–109 上：

- **发送任意文本消息 → 界面显示「上传失败」**（提示具有误导性，与附件无关）
- **导入历史对话 ZIP → 同样失败**

## 只有两处，但都在主路径上

| 文件 | 行 | 原代码 | 需要 |
| --- | --- | --- | --- |
| `src/services/chat/store.ts` | 847 | `getContextRounds(request.history).toReversed()` | Chrome 110+ |
| `src/services/chat/history/toLocal.ts` | 247 | `file.messages.toSorted((a, b) => a.id - b.id)` | Chrome 110+ |

同类方法中 `toSpliced()` 与 `Array.prototype.with()` 同样需要 110+，本仓库未使用这两者。

## 为什么错误提示是「上传失败」

`store.ts` 用 `preparing` 标志区分「附件补齐阶段」与「请求阶段」：

```ts
let preparing = true                                    // store.ts:980
const prepared = await prepareFiles(...)                // store.ts:983
preparing = false                                       // store.ts:984
// ...
} catch (error) {
  if (preparing) {
    reply = settleReply(reply, 'error', Date.now(), {
      kind: 'upload',
      localMessage: describeUploadError(error),         // → 「上传失败」
```

`prepareFiles()` 内部调用了 `.toReversed()`，方法不存在 → 抛 `TypeError` → 被 `preparing` 分支归类成 upload 错误。因此**纯文本消息也会显示「上传失败」**。

## 复现环境与证据

- 设备：Xiaomi M2007J1SC，Android 13（SDK 33）
- 系统 WebView：**Chrome 108.0.5359.128**

在 WebView 内直接检测：

```js
typeof Array.prototype.toReversed  // "undefined"
typeof Array.prototype.toSorted    // "undefined"
typeof Array.prototype.toSpliced   // "undefined"
Array.prototype.with               // undefined
```

排除网络与 CORS 的干扰（同环境下用页面内 `fetch` 请求 API）：

```js
const r = await fetch('https://api.deepseek.com/models', { headers: { Authorization: 'Bearer <invalid>' } })
// → { status: 401, type: 'cors' }
```

`type: 'cors'` 说明跨域实际成功，请求正常到达服务端，因此失败点在 JS 运行时而非网络。

## 修复（2 行）

```diff
- for (const [user] of getContextRounds(request.history).toReversed()) {
+ for (const [user] of [...getContextRounds(request.history)].reverse()) {

- const sorted = file.messages.toSorted((a, b) => a.id - b.id)
+ const sorted = [...file.messages].sort((a, b) => a.id - b.id)
```

语义等价：`toReversed` / `toSorted` 本身就是非变异方法，返回新数组；spread + `reverse` / `sort` 同样返回新数组且不改原数组，并且支持版本远早于 Chrome 105。

补丁文件：[`0001-fix-es2023-array-methods.patch`](./0001-fix-es2023-array-methods.patch)（`git am` 可直接应用）。

## 修复后真机验证

同一台设备、同样步骤：

| 操作 | 修复前 | 修复后 |
| --- | --- | --- |
| 发送文本消息 | 显示「上传失败」 | 正常收到模型回复 |
| 导入历史 ZIP | 失败 | 「已导入 1 个对话」 |
| 导出 ZIP | — | 结构合规（manifest 首位 + 会话 + index 数组） |

## 为什么没能直接提 PR

`deepseek-ai/chat-demo` 关闭了 Pull Requests 与 Issues 功能：

```
has_pull_requests: false
has_issues: false
```

因此任何人都无法向该仓库提交 PR（API 返回 `404` / GraphQL 报 `HLJ1997 does not have the correct permissions to execute CreatePullRequest`）。

修复分支已推到 fork，待维护者开启 PR 后可立即提交：

- fork：https://github.com/HLJ1997/chat-demo
- 分支：`fix/es2023-array-methods`（commit `f9c1b38`）
- 一键对比：https://github.com/deepseek-ai/chat-demo/compare/main...HLJ1997:fix/es2023-array-methods

## 建议的上游修法（任选其一）

1. **最小改动**：采用上面的 2 行替换（本仓库已采用）
2. **提高目标版本**：把 `rspack.config.ts` 的 `targets` 中 `chrome >= 105` 改为 `chrome >= 110`，代价是放弃部分存量设备
3. **加 polyfill**：在 `src/main.tsx` 引入 `core-js` 或手写 shim，代价是增加约 1–2 KB
