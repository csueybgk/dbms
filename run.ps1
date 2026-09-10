# DBMS 一键启动脚本（PowerShell 原生，Unicode 安全）
# 用法：
#   .\run.ps1 build           编译并打包 jar（如已存在跳过）
#   .\run.ps1 create [目录]    清空并新建一个库，启动服务端（前台）
#   .\run.ps1 open   [目录]    打开已有库，启动服务端（前台）
#   .\run.ps1 client [args...] 连接服务端的客户端（交互 REPL / -e / -f）
#   .\run.ps1 demo             一键演示：后台起服务端 -> 跑 DEMO.sql -> 停止服务端
#   .\run.ps1 test             运行全部测试

$ErrorActionPreference = "Stop"
$root    = $PSScriptRoot
$jar     = "$root\target\dbms-1.0.0.jar"

function Build-Jar {
    if (Test-Path $jar) { return }
    Write-Host "==> 编译打包 (首次运行) ..."
    Push-Location $root
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "mvn package 失败" }
    Pop-Location
}

function Wait-Port([int]$port) {
    for ($i = 0; $i -lt 50; $i++) {
        $ok = Test-NetConnection -ComputerName 127.0.0.1 -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue
        if ($ok) { return }
        Start-Sleep -Milliseconds 200
    }
    throw "端口 $port 未就绪"
}

$cmd = $args[0]

switch ($cmd) {
    "build" {
        Build-Jar
        Write-Host "OK: $jar"
    }
    "create" {
        Build-Jar
        $dir = if ($args.Length -gt 1) { $args[1] } else { "data\db" }
        java -cp $jar com.course.dbms.server.ServerLauncher create $dir 9999
    }
    "open" {
        Build-Jar
        $dir = if ($args.Length -gt 1) { $args[1] } else { "data\db" }
        java -cp $jar com.course.dbms.server.ServerLauncher open $dir 9999
    }
    "client" {
        Build-Jar
        $rest = @($args | Select-Object -Skip 1)
        java -cp $jar com.course.dbms.client.ClientLauncher @rest
    }
    "demo" {
        Build-Jar
        # 先删旧库，保证从零开始
        if (Test-Path "$root\data\db") { Remove-Item "$root\data\db" -Recurse -Force }
        Write-Host "==> 后台启动服务端 (create data\db, 9999)"
        $proc = Start-Process java -ArgumentList @('-cp', $jar, 'com.course.dbms.server.ServerLauncher', 'create', 'data/db', '9999') -PassThru -RedirectStandardOutput "$root\target\demo-server.log" -RedirectStandardError "$root\target\demo-server.err"
        try {
            Wait-Port 9999
            Write-Host "==> 客户端执行 DEMO.sql"
            java -cp $jar com.course.dbms.client.ClientLauncher -f "$root\DEMO.sql"
        } finally {
            if ($proc -and -not $proc.HasExited) { Stop-Process $proc -Force }
        }
        Write-Host "==> 服务端已停止。日志: target\demo-server.log"
    }
    "test" {
        Push-Location $root
        mvn -q test
        Pop-Location
    }
    default {
        Write-Host @'
DBMS 用法:
  .\run.ps1 build             编译打包
  .\run.ps1 create [目录]     新建库并启动服务端(前台)
  .\run.ps1 open   [目录]     打开已有库并启动服务端(前台)
  .\run.ps1 client [args...]  交互客户端 (或 -e SQL / -f 文件)
  .\run.ps1 demo              一键演示 DEMO.sql
  .\run.ps1 test              运行全部测试
'@
    }
}
