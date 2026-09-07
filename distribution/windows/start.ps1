$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot

# ============================================================
# UTF-8 WINDOWS
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
# CHEMINS
# ============================================================

$exe =
    Join-Path $PSScriptRoot "Xiaovv.exe"

$config =
    Join-Path $PSScriptRoot "application.properties"

$appDir =
    Join-Path $PSScriptRoot "app"

$appConfig =
    Join-Path $appDir "application.properties"

# ============================================================
# VERIFICATIONS
# ============================================================

if (-not (Test-Path $exe)) {
    Write-Error "Xiaovv.exe est introuvable."
    exit 1
}

if (-not (Test-Path $config)) {
    Write-Error @"
application.properties est introuvable.

Copie :
    application.properties.example

vers :
    application.properties

puis configure tes cameras.
"@
    exit 1
}

if (-not (Test-Path $appDir)) {
    Write-Error "Le dossier app de Xiaovv est introuvable."
    exit 1
}

# ============================================================
# CONFIGURATION
# ============================================================
#
# Le lanceur jpackage utilise :
#
#   app\application.properties
#
# La copie est faite à chaque lancement afin que le fichier
# utilisateur reste à la racine du package.
#

Copy-Item `
    -Path $config `
    -Destination $appConfig `
    -Force

# ============================================================
# DEMARRAGE
# ============================================================

Write-Host "============================================================"
Write-Host " Xiaovv V380 Server"
Write-Host " Windows autonome - Java embarque"
Write-Host "============================================================"
Write-Host ""

& $exe

exit $LASTEXITCODE
