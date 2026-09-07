@echo off

REM ============================================================
REM EXEMPLE UNIQUEMENT
REM
REM Copie ce fichier sous :
REM
REM     xiaovv-env.bat
REM
REM puis remplace les valeurs ci-dessous.
REM
REM Ne publie jamais xiaovv-env.bat sur GitHub.
REM ============================================================

set "XIAOVV_API_TOKEN=REMPLACER_PAR_UN_VRAI_JETON"

set "XIAOVV_CAMERA_EXAMPLE_PASSWORD=REMPLACER_PAR_LE_MOT_DE_PASSE"

REM Mot de passe du broker dont l'ID MQTT est "example".
REM Le mot de passe réel reste uniquement dans l'environnement.
set "XIAOVV_MQTT_EXAMPLE_PASSWORD=REMPLACER_PAR_LE_MOT_DE_PASSE_MQTT"

REM Facultatif : chemin personnalisé du fichier de configuration MQTT.
REM Le lanceur utilise automatiquement mqtt.properties à sa racine si absent.
REM set "XIAOVV_MQTT_CONFIG=D:\chemin\vers\mqtt.properties"

