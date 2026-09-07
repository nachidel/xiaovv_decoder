#!/usr/bin/env bash

# ============================================================
# EXEMPLE UNIQUEMENT
#
# Copie ce fichier sous :
#
#     xiaovv-env.sh
#
# puis remplace les valeurs ci-dessous.
#
# Ne publie jamais xiaovv-env.sh sur GitHub.
# ============================================================

export XIAOVV_API_TOKEN='REMPLACER_PAR_UN_VRAI_JETON'

export XIAOVV_CAMERA_EXAMPLE_PASSWORD='REMPLACER_PAR_LE_MOT_DE_PASSE'

# Mot de passe du broker dont l'ID MQTT est "example".
# Le mot de passe réel reste uniquement dans l'environnement.
export XIAOVV_MQTT_EXAMPLE_PASSWORD='REMPLACER_PAR_LE_MOT_DE_PASSE_MQTT'

# Facultatif : chemin personnalisé du fichier de configuration MQTT.
# export XIAOVV_MQTT_CONFIG='/chemin/vers/mqtt.properties'

