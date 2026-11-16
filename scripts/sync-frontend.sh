#!/usr/bin/env bash
# 从「我们自己的前端 fork」构建 dist 并同步到 Android assets。
# 用法：
#   ./scripts/sync-frontend.sh                    # 默认 fork + main
#   FRONTEND_REPO=... FRONTEND_REF=... ./scripts/sync-frontend.sh
set -euo pipefail

REPO="${FRONTEND_REPO:-https://github.com/HLJ1997/chat-demo.git}"
REF="${FRONTEND_REF:-main}"
WORK="${FRONTEND_WORK:-.frontend-work}"
DEST="app/src/main/assets/www"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

echo "==> 前端仓库: $REPO ($REF)"

if [ -d "$WORK/.git" ]; then
  git -C "$WORK" remote set-url origin "$REPO"
  git -C "$WORK" fetch --depth 1 origin "$REF"
  git -C "$WORK" checkout -f FETCH_HEAD
else
  rm -rf "$WORK"
  git clone --depth 1 --branch "$REF" "$REPO" "$WORK"
fi

echo "==> 前端提交: $(git -C "$WORK" rev-parse --short HEAD) $(git -C "$WORK" log -1 --format=%s)"

cd "$WORK"
if command -v pnpm >/dev/null 2>&1; then
  pnpm install --frozen-lockfile
else
  corepack enable && corepack prepare pnpm@latest --activate
  pnpm install --frozen-lockfile
fi

# 注意：package.json 的 build 脚本是 Unix 语法（rm -rf + NODE_ENV 前缀），
# 这里直接调用 rspack，避免依赖 shell。
NODE_ENV=production pnpm exec rspack build -c rspack.config.ts
cd "$root"

rm -rf "$DEST"
mkdir -p "$DEST"
cp -R "$WORK/dist/." "$DEST/"

count="$(find "$DEST" -type f | wc -l | tr -d ' ')"
echo "==> 同步完成：$count 个文件 → $DEST"
