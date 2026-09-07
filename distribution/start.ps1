$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

# ============================================================
# UTF-8 Windows
# ============================================================

chcp 65001 > $null

[Console]::InputEncoding =
    [System.Text.UTF8Encoding]::new($false)

[Console]::OutputEncoding =
    [System.Text.UTF8Encoding]::new($false)

$OutputEncoding =
    [System.Text.UTF8Encoding]::new($false)

# ============================================================
# VARIABLES D'ENVIRONNEMENT
# ============================================================

$envFile =
    Join-Path $PSScriptRoot "xiaovv-env.ps1"

if (Test-Path $envFile) {
    . $envFile
}

# ============================================================
# CONFIGURATION MQTT EXTERNE
# ============================================================
#
# Par défaut, mqtt.properties reste à la racine de la distribution.
# Une valeur XIAOVV_MQTT_CONFIG déjà définie garde la priorité.
#

if ([string]::IsNullOrWhiteSpace($env:XIAOVV_MQTT_CONFIG)) {
    $env:XIAOVV_MQTT_CONFIG =
        Join-Path $PSScriptRoot "mqtt.properties"
}

# ============================================================
# APPLICATION
# ============================================================

$jar =
    Join-Path $PSScriptRoot "xiaovv.jar"

$config =
    Join-Path $PSScriptRoot "application.properties"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "Java est introuvable. Java 21 est nécessaire."
    exit 1
}

if (-not (Test-Path $jar)) {
    Write-Error "xiaovv.jar est introuvable."
    exit 1
}

if (-not (Test-Path $config)) {
    Write-Error "application.properties est introuvable."
    exit 1
}

Write-Host "============================================================"
Write-Host " Xiaovv V380 Server"
Write-Host "============================================================"
Write-Host ""

& java `
    "-Dfile.encoding=UTF-8" `
    "-Dsun.stdout.encoding=UTF-8" `
    "-Dsun.stderr.encoding=UTF-8" `
    "-Dxiaovv.config=$config" `
    -jar $jar

exit $LASTEXITCODE