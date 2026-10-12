<#
 Runs a console command on the Minecraft server that is already running (the dev server started with runServer), without
 needing its console. Handy for a clean stop from a script:

   powershell -File tools/servercmd.ps1 "save-all flush"
   powershell -File tools/servercmd.ps1 "stop"
   powershell -File tools/servercmd.ps1 "brage rutt info"

 It attaches to the server JVM and runs the command on the server thread (tools/spawn/build/cmd-agent.jar).
#>
param([Parameter(Mandatory = $true)][string]$Command)

$root = Split-Path -Parent $PSScriptRoot
$java = Get-ChildItem "$env:USERPROFILE\.gradle\jdks" -Directory -Filter "*25*" -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName "bin\java.exe" } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $java) { Write-Error "Java 25 was not found under ~/.gradle/jdks (run ./gradlew build once)"; exit 1 }
$server = Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty OwningProcess
if (-not $server) { Write-Error "No server is listening on port 25565"; exit 1 }
$build = Join-Path $root "tools\spawn\build"
& $java -cp $build AttachReload $server (Join-Path $build "cmd-agent.jar") $Command
