#!/usr/bin/env bash

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

cd "$SCRIPT_DIR"

echo "============================================================"
echo " Xiaovv V380 Server"
echo "============================================================"
echo

if [ -f "$SCRIPT_DIR/xiaovv-env.sh" ]; then
    # shellcheck disable=SC1091
    . "$SCRIPT_DIR/xiaovv-env.sh"
fi

# ============================================================
# CONFIGURATION MQTT EXTERNE
# ============================================================
#
# Une valeur XIAOVV_MQTT_CONFIG déjà définie garde la priorité.
#

if [ -z "${XIAOVV_MQTT_CONFIG:-}" ]; then
    export XIAOVV_MQTT_CONFIG="$SCRIPT_DIR/mqtt.properties"
fi

if ! command -v java >/dev/null 2>&1; then
    echo "ERREUR : Java est introuvable." >&2
    echo "Installe Java 21 ou une version compatible." >&2
    exit 1
fi

if [ ! -f "$SCRIPT_DIR/xiaovv.jar" ]; then
    echo "ERREUR : xiaovv.jar est introuvable." >&2
    exit 1
fi

if [ ! -f "$SCRIPT_DIR/application.properties" ]; then
    echo "ERREUR : application.properties est introuvable." >&2
    echo "Copie application.properties.example en application.properties puis configure tes cameras." >&2
    exit 1
fi

exec java \
    "-Dxiaovv.config=$SCRIPT_DIR/application.properties" \
    -jar "$SCRIPT_DIR/xiaovv.jar"
