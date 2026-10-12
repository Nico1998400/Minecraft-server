<#
 One command to deploy your changes to the dev server: saves the world, stops the server cleanly, builds the plugin jar,
 and starts the server again (in the background, with the debug port 5005 open for hot reload). Takes about 30-40 seconds;
 players just reconnect.

   powershell -File tools/restart-server.ps1            # restart with the latest code
   powershell -File tools/restart-server.ps1 -NoBuild   # restart without rebuilding the jar

 The server log goes to build/dev-server.log (relative to the repository root).
#>
param([switch]$NoBuild)

$root = Split-Path -Parent $PSScriptRoot
$cmd = Join-Path $PSScriptRoot "servercmd.ps1"
$log = Join-Path $root "swedencore\build\dev-server.log"

function Get-ServerPid {
    Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty OwningProcess
}

$server = Get-ServerPid
if ($server) {
    Write-Host "Saving and stopping the running server (pid $server)..."
    & powershell -NoProfile -File $cmd "save-all flush" | Out-Null
    Start-Sleep -Seconds 4
    & powershell -NoProfile -File $cmd "stop" | Out-Null
    for ($i = 0; $i -lt 90 -and (Get-Process -Id $server -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Seconds 1 }
    if (Get-Process -Id $server -ErrorAction SilentlyContinue) { Write-Error "The server did not stop within 90 seconds"; exit 1 }
    Start-Sleep -Seconds 3
} else {
    Write-Host "No server was running."
}

Set-Location $root
if (-not $NoBuild) {
    Write-Host "Building the plugin..."
    & .\gradlew.bat :swedencore:shadowJar -q
    if ($LASTEXITCODE -ne 0) { Write-Error "The build failed; the server was NOT started"; exit 1 }
}

Write-Host "Starting the server..."
New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
Start-Process -FilePath (Join-Path $root "gradlew.bat") -ArgumentList ":swedencore:runServer", "-Pdebug" `
    -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError ($log + ".err")

for ($i = 0; $i -lt 120; $i++) {
    Start-Sleep -Seconds 2
    if ((Test-Path $log) -and (Select-String -Path $log -Pattern "Done \(" -Quiet)) {
        Write-Host "The server is up. Connect to localhost."
        exit 0
    }
}
Write-Error "The server did not report 'Done' within 4 minutes; see $log"
exit 1
