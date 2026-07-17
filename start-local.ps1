# 本地一键启动 feed / sim / quant 三服务（各开一个窗口，关窗即停）
# 用法:
#   .\start-local.ps1              # 先构建再启动
#   .\start-local.ps1 -SkipBuild   # 跳过构建，直接用已有 jar
#   （或直接双击 start-local.bat，参数透传）
# 前置: 根目录已有 .env.local（cp .env.example .env.local 填值）；本地 PG/Redis 已启动
param([switch]$SkipBuild)

$root = $PSScriptRoot

if (-not (Test-Path "$root\.env.local")) {
    Write-Error "缺 $root\.env.local — 先执行: cp .env.example .env.local 并填值"
    exit 1
}

if (-not $SkipBuild) {
    mvn clean package -pl wiib-feed,wiib-quant,wiib-sim -am -DskipTests
    if ($LASTEXITCODE -ne 0) { Write-Error "构建失败，未启动服务"; exit 1 }
}

# 优先 JAVA_HOME（裸 java 可能指向老版本 JRE，跑不了 21 编译的 jar）
$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

# Spring Boot 到启动收尾才绑 HTTP 端口，所以"端口能连通"就等于服务真就绪，不需要 actuator
function Wait-Ready([string]$name, [int]$port, [int]$timeoutSec = 180) {
    Write-Host "等待 $name (127.0.0.1:$port) 就绪 " -NoNewline
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $tcp = New-Object Net.Sockets.TcpClient
        try {
            if ($tcp.ConnectAsync('127.0.0.1', $port).Wait(1000) -and $tcp.Connected) {
                Write-Host "OK"
                return $true
            }
        } catch {} finally { $tcp.Dispose() }
        Write-Host "." -NoNewline
        Start-Sleep -Seconds 2
    }
    Write-Host ""
    Write-Warning "$name 在 ${timeoutSec}s 内未就绪，继续启动后续服务（缺行情时下游只会干等，不会崩）"
    return $false
}

# 就绪门控顺序启动：feed 先起（sim/quant 消费它写的行情流），端口通了再起下一个
# 工作目录=根目录，保证 yml 里 optional:file:.env.local 命中
$services = @(
    @{ Name = 'wiib-feed';  Port = 8081 },
    @{ Name = 'wiib-sim';   Port = 8080 },
    @{ Name = 'wiib-quant'; Port = 8082 }
)
foreach ($s in $services) {
    $jar = Get-ChildItem "$root\$($s.Name)\target\$($s.Name)-*.jar" -Exclude '*.original' | Select-Object -First 1
    if (-not $jar) { Write-Error "找不到 $($s.Name) 的 jar，先跑构建（去掉 -SkipBuild）"; exit 1 }
    Start-Process $javaExe -ArgumentList "-jar", "`"$($jar.FullName)`"" -WorkingDirectory $root
    Write-Host "已启动 $($s.Name) ($($jar.Name))"
    Wait-Ready $s.Name $s.Port | Out-Null
}

Write-Host "`n三服务已就绪: feed:8081 / sim:8080 / quant:8082（前端另起: cd wiib-web; npm run dev）"
