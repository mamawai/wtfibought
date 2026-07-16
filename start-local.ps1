# 本地一键启动 feed / sim / quant 三服务（各开一个窗口，关窗即停）
# 用法:
#   .\start-local.ps1              # 先构建再启动
#   .\start-local.ps1 -SkipBuild   # 跳过构建，直接用已有 jar
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

# feed 先起（sim/quant 消费它写的行情流）；工作目录=根目录，保证 yml 里 optional:file:.env.local 命中
foreach ($m in 'wiib-feed', 'wiib-sim', 'wiib-quant') {
    $jar = Get-ChildItem "$root\$m\target\$m-*.jar" -Exclude '*.original' | Select-Object -First 1
    if (-not $jar) { Write-Error "找不到 $m 的 jar，先跑构建（去掉 -SkipBuild）"; exit 1 }
    Start-Process $javaExe -ArgumentList "-jar", "`"$($jar.FullName)`"" -WorkingDirectory $root
    Write-Host "已启动 $m ($($jar.Name))"
}

Write-Host "`n三服务已拉起: feed:8081 / sim:8080 / quant:8082（前端另起: cd wiib-web; npm run dev）"
