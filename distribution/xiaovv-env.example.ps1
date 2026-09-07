# ============================================================
# EXEMPLE UNIQUEMENT
#
# Copie ce fichier sous :
#
#     xiaovv-env.ps1
#
# puis remplace les valeurs ci-dessous.
#
# Ne publie jamais xiaovv-env.ps1 sur GitHub.
# ============================================================

$env:XIAOVV_API_TOKEN = "REMPLACER_PAR_UN_VRAI_JETON"

$env:XIAOVV_CAMERA_EXAMPLE_PASSWORD = "REMPLACER_PAR_LE_MOT_DE_PASSE"

# Mot de passe du broker dont l'ID MQTT est "example".
# Le mot de passe réel reste uniquement dans l'environnement.
$env:XIAOVV_MQTT_EXAMPLE_PASSWORD = "REMPLACER_PAR_LE_MOT_DE_PASSE_MQTT"

# Facultatif : chemin personnalisé du fichier de configuration MQTT.
# $env:XIAOVV_MQTT_CONFIG = "D:\chemin\vers\mqtt.properties"

