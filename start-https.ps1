param(
    [string]$ConfigFile = "",
    [ValidateRange(1, 65535)]
    [int]$HttpsPort = 8443
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($ConfigFile)) {
    $ConfigFile = Join-Path $PSScriptRoot "config\application.properties"
}
$ConfigFile = [System.IO.Path]::GetFullPath($ConfigFile)
$configDirectory = Split-Path -Parent $ConfigFile
$certificateFile = Join-Path $PSScriptRoot "config\certs\xiaovv.p12"
$jarFile = Join-Path $PSScriptRoot "build\libs\xiaovv.jar"
$javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue

foreach ($requiredFile in @($ConfigFile, $certificateFile, $jarFile)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Fichier introuvable : $requiredFile"
    }
}
if ($null -eq $javaCommand) {
    throw "Java 21 est necessaire pour lancer Xiaovv."
}

# Reutiliser les secrets locaux sans les afficher dans la console.
$powershellEnvironmentFile = Join-Path $configDirectory "xiaovv-env.ps1"
$batchEnvironmentFile = Join-Path $configDirectory "xiaovv-env.bat"
if (Test-Path -LiteralPath $powershellEnvironmentFile) {
    . $powershellEnvironmentFile
} elseif (Test-Path -LiteralPath $batchEnvironmentFile) {
    $environmentLines = & $env:ComSpec /d /c "call `"$batchEnvironmentFile`" >nul && set XIAOVV_" 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de charger les variables depuis xiaovv-env.bat."
    }
    foreach ($line in $environmentLines) {
        if ($line -match '^(XIAOVV_[A-Z0-9_]+)=(.*)$') {
            [Environment]::SetEnvironmentVariable($matches[1], $matches[2], "Process")
        }
    }
    $environmentLines = $null
}

if ([string]::IsNullOrWhiteSpace($env:XIAOVV_API_TOKEN)) {
    throw "XIAOVV_API_TOKEN absent : verifier le fichier local xiaovv-env.bat ou xiaovv-env.ps1."
}

function Get-ConfiguredPort([string]$Property, [string]$EnvironmentName, [int]$Default) {
    $environmentValue = [Environment]::GetEnvironmentVariable($EnvironmentName, "Process")
    if (-not [string]::IsNullOrWhiteSpace($environmentValue)) {
        return [int]$environmentValue
    }
    $portLine = Select-String -LiteralPath $ConfigFile -Pattern ('^\s*' + [regex]::Escape($Property) + '\s*[=:]\s*(\d+)\s*$')
    if ($portLine) {
        return [int]$portLine[-1].Matches[0].Groups[1].Value
    }
    return $Default
}

$httpPort = Get-ConfiguredPort "api.port" "XIAOVV_API_PORT" 8080
$rtspPort = Get-ConfiguredPort "rtsp.port" "XIAOVV_RTSP_PORT" 8555
if ($HttpsPort -in @($httpPort, $rtspPort)) {
    throw "Le port HTTPS doit etre different des ports HTTP et RTSP."
}
$listeningPorts = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
    Select-Object -ExpandProperty Port -Unique
foreach ($port in @($httpPort, $rtspPort, $HttpsPort)) {
    if ($port -in $listeningPorts) {
        throw "Le port $port est deja utilise. Arrete l'instance Xiaovv existante puis relance ce script."
    }
}

# Conserver le MQTT de ce projet lors du passage a une configuration externe.
$mqttFile = $env:XIAOVV_MQTT_CONFIG
if ([string]::IsNullOrWhiteSpace($mqttFile)) {
    $mqttFile = Join-Path $configDirectory "mqtt.properties"
    if (-not (Test-Path -LiteralPath $mqttFile)) {
        $mqttFile = Join-Path $PSScriptRoot "src\main\resources\mqtt.properties"
    }
}

$env:XIAOVV_HTTPS_ENABLED = "true"
$env:XIAOVV_HTTPS_BIND = "0.0.0.0"
$env:XIAOVV_HTTPS_PORT = [string]$HttpsPort
$env:XIAOVV_HTTPS_KEY_STORE = $certificateFile
$env:XIAOVV_HTTPS_KEY_STORE_TYPE = "PKCS12"

$previousCertificatePassword = $env:XIAOVV_HTTPS_KEY_STORE_PASSWORD
$certificatePassword = $null
try {
    if ([string]::IsNullOrWhiteSpace($previousCertificatePassword)) {
        $certificatePassword = Read-Host "Mot de passe d'export du certificat Jeedom" -AsSecureString
        $env:XIAOVV_HTTPS_KEY_STORE_PASSWORD = [System.Net.NetworkCredential]::new("", $certificatePassword).Password
    }
    if ([string]::IsNullOrWhiteSpace($env:XIAOVV_HTTPS_KEY_STORE_PASSWORD)) {
        throw "Le mot de passe du certificat est obligatoire."
    }

    Write-Host "HTTPS : https://www.nachidel.ovh:$HttpsPort/"
    Write-Host "Garde ce terminal ouvert. Ctrl+C arrete Xiaovv."
    & $javaCommand.Source "-Dfile.encoding=UTF-8" "-Dxiaovv.config=$ConfigFile" "-Dxiaovv.mqtt.config=$mqttFile" -jar $jarFile
    $applicationExitCode = $LASTEXITCODE
} finally {
    $env:XIAOVV_HTTPS_KEY_STORE_PASSWORD = $previousCertificatePassword
    if ($null -ne $certificatePassword) {
        $certificatePassword.Dispose()
    }
}
exit $applicationExitCode
