#!/usr/bin/env pwsh
# VinaBeef M2 developer entry point (owner: Đông A). Requires only Docker + PowerShell 7.
# On macOS/Linux without pwsh, use ./dev — it runs this script inside a devshell container.
#
#   dev doctor
#   dev up   [-Profile infra|local|gate|full]      (default local)
#   dev down                                        (stops everything, KEEPS volumes)
#   dev ps | dev logs [service...]
#   dev mvn <maven args>                            e.g. dev mvn -B verify
#   dev emulator <emulator args>                    e.g. dev emulator --kind GATE --count 5
#   dev flutter <flutter args>                      e.g. dev flutter --version
#   dev reset -Profile <...> -Force                 DELETES data volumes (db, broker). Never implicit.
#
# No param() block on purpose: pass-through args such as `-P integration` or `-f` must reach mvn/flutter untouched.

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$ProfileMap = [ordered]@{
    infra = @()
    local = @('apps')
    gate  = @('apps', 'gate')
    full  = @('full')
}
$DataVolumes = @('central-db-data', 'edge-db-data', 'rabbitmq-data')
# Explicit list: pwsh on Unix glob-expands a bare '*' passed to native commands.
$AllProfiles = @('--profile', 'apps', '--profile', 'gate', '--profile', 'full', '--profile', 'tools')

function Get-Option([string[]]$argv, [string]$name, [string]$default) {
    for ($i = 0; $i -lt $argv.Count; $i++) {
        if ($argv[$i] -ieq "-$name" -and $i + 1 -lt $argv.Count) { return $argv[$i + 1] }
    }
    return $default
}

function Get-ProfileArgs([string]$name) {
    if (-not $ProfileMap.Contains($name)) {
        throw "Unknown profile '$name'. Use one of: $($ProfileMap.Keys -join ', ')"
    }
    $result = @()
    foreach ($p in $ProfileMap[$name]) { $result += @('--profile', $p) }
    return , $result
}

function Invoke-Compose([string[]]$composeArgs) {
    Write-Host "> docker compose $($composeArgs -join ' ')" -ForegroundColor DarkGray
    & docker compose @composeArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

function Assert-EnvFile {
    if (-not (Test-Path '.env')) {
        throw ".env not found. Create it once with: Copy-Item .env.example .env  (or: cp .env.example .env)"
    }
}

function Read-EnvKeys([string]$path) {
    Get-Content $path | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } |
        ForEach-Object { ($_ -split '=', 2)[0].Trim() }
}

function Test-PortFree([int]$port) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $port)
    try { $listener.Start(); return $true } catch { return $false } finally { $listener.Stop() }
}

function Invoke-Doctor {
    $ok = $true
    function Report([bool]$pass, [string]$msg) {
        if ($pass) { Write-Host "  [ok]   $msg" -ForegroundColor Green }
        else { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:ok = $false }
    }
    Write-Host 'VinaBeef doctor'
    Write-Host "  PowerShell $($PSVersionTable.PSVersion) on $([System.Runtime.InteropServices.RuntimeInformation]::OSDescription) ($([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture))"

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    Report ($null -ne $docker) 'docker CLI on PATH'
    if ($docker) {
        $engine = (& docker version --format '{{.Server.Version}} {{.Server.Arch}}' 2>$null)
        Report ($LASTEXITCODE -eq 0) "Docker Engine reachable ($engine)"
        $compose = (& docker compose version --short 2>$null)
        Report ($LASTEXITCODE -eq 0 -and $compose -match '^v?([2-9]|\d{2,})\.') "Docker Compose v2+ ($compose)"
    }

    $hasEnv = Test-Path '.env'
    Report $hasEnv '.env present'
    if ($hasEnv) {
        $missing = Read-EnvKeys '.env.example' | Where-Object { $_ -notin @(Read-EnvKeys '.env') }
        Report (-not $missing) ("all .env.example keys set in .env" + $(if ($missing) { " - missing: $($missing -join ', ')" }))
    }

    if ($env:VINABEEF_DEVSHELL -eq '1') {
        Write-Host '  [skip] host port check (running inside devshell container; use `docker compose ps` to see bindings)'
    } else {
        $running = (& docker compose ps --format '{{.Publishers}}' 2>$null) -join ' '
        foreach ($p in 8080, 8081) {
            Report ((Test-PortFree $p) -or $running -match ":$p->") "host port $p free or used by this stack"
        }
    }

    if ($docker -and $hasEnv) {
        & docker compose @AllProfiles config --quiet 2>&1 | Out-Null
        Report ($LASTEXITCODE -eq 0) 'compose.yaml valid for all profiles'
    }

    if (-not $ok) { Write-Host 'doctor: problems found (see FAIL lines). Nothing was changed.' -ForegroundColor Red; exit 1 }
    Write-Host 'doctor: all checks passed.' -ForegroundColor Green
}

$cmd = if ($args.Count -gt 0) { $args[0] } else { 'help' }
$rest = if ($args.Count -gt 1) { [string[]]$args[1..($args.Count - 1)] } else { [string[]]@() }

switch ($cmd) {
    'doctor' { Invoke-Doctor }

    'up' {
        Assert-EnvFile
        $p = Get-Option $rest 'Profile' 'local'
        Invoke-Compose ((Get-ProfileArgs $p) + @('up', '-d', '--wait', '--wait-timeout', '900'))
        Invoke-Compose ((Get-ProfileArgs $p) + @('ps'))
    }

    'down' {
        Assert-EnvFile
        # Stop every profile so nothing is left behind; volumes are kept.
        Invoke-Compose ($AllProfiles + @('down'))
    }

    'ps' { Invoke-Compose ($AllProfiles + @('ps')) }

    'logs' { Invoke-Compose ($AllProfiles + @('logs', '--tail', '200') + $rest) }

    'mvn' {
        Assert-EnvFile
        Invoke-Compose (@('run', '--rm', 'toolbox', 'mvn') + $rest)
    }

    'emulator' {
        Assert-EnvFile
        $execArgs = ($rest | ForEach-Object { if ($_ -match '\s') { "'$_'" } else { $_ } }) -join ' '
        Invoke-Compose @('run', '--rm', '-T', 'toolbox', 'mvn', '-B', '-q', '-pl', 'tools/reader-emulator',
            'compile', 'exec:java', "-Dexec.args=$execArgs")
    }

    'flutter' {
        Assert-EnvFile
        if (-not (Test-Path 'mobile')) { New-Item -ItemType Directory 'mobile' | Out-Null }
        Invoke-Compose (@('run', '--rm', 'flutter', 'flutter') + $rest)
    }

    'reset' {
        Assert-EnvFile
        if ($rest -notcontains '-Force') {
            throw "reset deletes data volumes ($($DataVolumes -join ', ')). Re-run with -Force if you really mean it."
        }
        $project = (& docker compose config --format json | ConvertFrom-Json).name
        Invoke-Compose ($AllProfiles + @('down'))
        foreach ($v in $DataVolumes) {
            $full = "${project}_$v"
            & docker volume inspect $full *> $null
            if ($LASTEXITCODE -eq 0) { Write-Host "removing volume $full"; & docker volume rm $full | Out-Null }
        }
        Write-Host 'reset done. Build caches (m2-cache, pub-cache) were kept.'
    }

    default {
        Get-Content $PSCommandPath | Select-Object -Skip 1 -First 13 | ForEach-Object { $_ -replace '^# ?', '' }
        if ($cmd -notin @('help', '-h', '--help')) { exit 2 }
    }
}
