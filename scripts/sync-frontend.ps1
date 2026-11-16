# 从「我们自己的前端 fork」构建 dist 并同步到 Android assets。
#
# 用法：
#   .\scripts\sync-frontend.ps1
#   .\scripts\sync-frontend.ps1 -Repo https://github.com/HLJ1997/chat-demo.git -Ref main
#
# 说明：chat-demo 的 `pnpm build` 脚本是 Unix 语法（`rm -rf` + `NODE_ENV=` 前缀），
# Windows 上会失败，所以这里直接调用 rspack。

param(
    [string]$Repo = 'https://github.com/HLJ1997/chat-demo.git',
    [string]$Ref  = 'main',
    [string]$Work = '.frontend-work'
)

$ErrorPolicy = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

$dest = Join-Path $root 'app\src\main\assets\www'

# 这台机器（以及某些受限网络）访问 github.com 需要走代理：
# 环境里配了 HTTPS_PROXY 时，把代理只作用于 github.com，并切换 SSL 后端避开 schannel 握手问题。
$proxy = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } elseif ($env:https_proxy) { $env:https_proxy } else { '' }
$gitOpts = @()
if ($proxy) {
    $gitOpts = @(
        '-c', "http.https://github.com/.proxy=$proxy",
        '-c', "http.https://raw.githubusercontent.com/.proxy=$proxy",
        '-c', 'http.sslBackend=openssl'
    )
    "==> 使用代理: $proxy"
}

function Invoke-Git {
    param([string[]]$GitArgs)
    & git @gitOpts @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs[0]) 失败" }
}

"==> 前端仓库: $Repo ($Ref)"

if (Test-Path (Join-Path $Work '.git')) {
    Invoke-Git @('-C', $Work, 'remote', 'set-url', 'origin', $Repo)
    Invoke-Git @('-C', $Work, 'fetch', '--depth', '1', 'origin', $Ref)
    Invoke-Git @('-C', $Work, 'checkout', '-f', 'FETCH_HEAD')
} else {
    if (Test-Path $Work) { Remove-Item -Recurse -Force $Work }
    Invoke-Git @('clone', '--depth', '1', '--branch', $Ref, $Repo, $Work)
}

$head = (git -C $Work rev-parse --short HEAD).Trim()
$subject = (git -C $Work log -1 --format=%s).Trim()
"==> 前端提交: $head  $subject"

Push-Location $Work
try {
    pnpm install --frozen-lockfile
    if ($LASTEXITCODE -ne 0) { throw 'pnpm install 失败' }

    $env:NODE_ENV = 'production'
    pnpm exec rspack build -c rspack.config.ts
    if ($LASTEXITCODE -ne 0) { throw 'rspack build 失败' }
} finally {
    Pop-Location
}

if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item -Path (Join-Path $Work 'dist\*') -Destination $dest -Recurse -Force

$count = (Get-ChildItem $dest -Recurse -File).Count
"==> 同步完成：$count 个文件 → app\src\main\assets\www"
