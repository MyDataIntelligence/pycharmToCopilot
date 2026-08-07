param(
    [switch]$BuildOnly,
    [string]$TargetConfigDir,
    [string]$PluginsDir
)

$ErrorActionPreference = 'Stop'
$repoRoot = $PSScriptRoot
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $repoHashBytes = $sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($repoRoot))
} finally {
    $sha256.Dispose()
}
$repoHash = (-join ($repoHashBytes | ForEach-Object { $_.ToString('x2') })).Substring(0, 12)
$externalBuild = Join-Path $env:LOCALAPPDATA "CopilotContextBridge\build\$repoHash"

if (Get-Process -Name 'pycharm64','pycharm' -ErrorAction SilentlyContinue) {
    throw 'Close PyCharm before installing or updating Copilot Context Bridge.'
}

if (-not $env:JAVA_HOME) {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) {
        $jbrCandidates = @(
            Get-ChildItem -Path "$env:ProgramFiles\JetBrains\PyCharm*\jbr\bin\java.exe" -File -ErrorAction SilentlyContinue
            Get-ChildItem -Path "$env:LOCALAPPDATA\Programs\PyCharm*\jbr\bin\java.exe" -File -ErrorAction SilentlyContinue
            Get-ChildItem -Path "$env:LOCALAPPDATA\JetBrains\Toolbox\apps\PyCharm*\*\*\jbr\bin\java.exe" -File -ErrorAction SilentlyContinue
        ) | Sort-Object LastWriteTime -Descending
        $java = $jbrCandidates | Select-Object -First 1
        if ($java) {
            $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java.FullName)
            Write-Host "Using PyCharm's bundled Java runtime: $env:JAVA_HOME"
        } else {
            throw 'Java 17+ or a detectable PyCharm bundled runtime is required to start the Gradle wrapper.'
        }
    }
}

Push-Location $repoRoot
try {
    & "$repoRoot\gradlew.bat" buildPlugin "-PccbBuildDir=$externalBuild" --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Plugin build failed; installation was not changed.' }
} finally {
    Pop-Location
}

$zip = Get-ChildItem -LiteralPath "$externalBuild\distributions" -Filter '*.zip' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $zip) { throw 'No plugin ZIP was produced.' }
Write-Host "Built: $($zip.FullName)"
$repositoryDistributions = Join-Path $repoRoot 'build\distributions'
New-Item -ItemType Directory -Path $repositoryDistributions -Force | Out-Null
$repositoryZip = Join-Path $repositoryDistributions $zip.Name
Copy-Item -LiteralPath $zip.FullName -Destination $repositoryZip -Force
Write-Host "ZIP copied to: $repositoryZip"
if ($BuildOnly) { return }

if (-not $PluginsDir) {
    if (-not $TargetConfigDir) {
        $configs = Get-ChildItem -LiteralPath "$env:APPDATA\JetBrains" -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^PyCharm(2025\.1|2025\.2|2025\.3|2026\.1|2026\.2)$' } |
            Sort-Object Name -Descending
        $TargetConfigDir = $configs | Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $TargetConfigDir) { throw 'No compatible PyCharm 2025.1-2026.2 profile found. Use -TargetConfigDir or install via ZIP.' }
    $PluginsDir = Join-Path $TargetConfigDir 'plugins'
}

$pluginsPath = [System.IO.Path]::GetFullPath($PluginsDir)
New-Item -ItemType Directory -Path $pluginsPath -Force | Out-Null
$profileName = Split-Path -Leaf (Split-Path -Parent $pluginsPath)
$backupRoot = Join-Path $env:LOCALAPPDATA "CopilotContextBridge\plugin-backups\$profileName"
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
# A backup containing plugin.xml must never remain in the IDE plugins folder:
# JetBrains can discover it as a duplicate plugin ID and load stale classes.
Get-ChildItem -LiteralPath $pluginsPath -Directory -Filter '.copilot-context-bridge-backup-*' -ErrorAction SilentlyContinue |
    ForEach-Object {
        $archived = Join-Path $backupRoot $_.Name
        if (Test-Path $archived) { $archived += '-' + [guid]::NewGuid().ToString('N').Substring(0, 6) }
        Move-Item -LiteralPath $_.FullName -Destination $archived
    }
$stage = Join-Path $pluginsPath ('.copilot-context-bridge-install-' + [guid]::NewGuid().ToString('N'))
$destination = Join-Path $pluginsPath 'copilot-context-bridge'
$backup = Join-Path $backupRoot ('copilot-context-bridge-' + (Get-Date -Format 'yyyyMMdd_HHmmss'))

try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    Expand-Archive -LiteralPath $zip.FullName -DestinationPath $stage
    $payload = Get-ChildItem -LiteralPath $stage -Directory | Select-Object -First 1
    if (-not $payload -or -not (Test-Path (Join-Path $payload.FullName 'lib'))) { throw 'Built ZIP has an unexpected plugin structure.' }
    if (Test-Path $destination) { Move-Item -LiteralPath $destination -Destination $backup }
    Move-Item -LiteralPath $payload.FullName -Destination $destination
    Write-Host "Installed to: $destination"
    if (Test-Path $backup) { Write-Host "Previous version kept at: $backup" }
} catch {
    if ((Test-Path $backup) -and -not (Test-Path $destination)) { Move-Item -LiteralPath $backup -Destination $destination }
    throw
} finally {
    if (Test-Path $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}

Write-Host 'Start PyCharm. The Copilot Context Bridge tool window is available on the right.'
