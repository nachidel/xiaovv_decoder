#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(
    CDPATH= cd -- "$(dirname -- "$0")" &&
    pwd
)"

cd "$SCRIPT_DIR"

# ============================================================
# VARIABLES D'ENVIRONNEMENT
# ============================================================

ENV_FILE="$SCRIPT_DIR/xiaovv-env.sh"

if [ -f "$ENV_FILE" ]; then
    # shellcheck disable=SC1090
    . "$ENV_FILE"
fi

# ============================================================
# CHEMINS
# ============================================================

LAUNCHER="$SCRIPT_DIR/bin/Xiaovv"
CONFIG="$SCRIPT_DIR/application.properties"
APP_DIR="$SCRIPT_DIR/lib/app"
APP_CONFIG="$APP_DIR/application.properties"

# ============================================================
# VERIFICATIONS
# ============================================================

if [ ! -x "$LAUNCHER" ]; then
    echo "ERREUR : le lanceur Xiaovv est introuvable ou non executable :" >&2
    echo "  $LAUNCHER" >&2
    exit 1
fi

if [ ! -f "$CONFIG" ]; then
    echo "ERREUR : application.properties est introuvable." >&2
    echo >&2
    echo "Copie :" >&2
    echo "  application.properties.example" >&2
    echo "vers :" >&2
    echo "  application.properties" >&2
    echo "puis configure tes cameras." >&2
    exit 1
fi

if [ ! -d "$APP_DIR" ]; then
    echo "ERREUR : le dossier applicatif jpackage est introuvable :" >&2
    echo "  $APP_DIR" >&2
    exit 1
fi

# ============================================================
# CONFIGURATION
# ============================================================
#
# Le lanceur jpackage reçoit :
#
#   -Dxiaovv.config=$APPDIR/application.properties
#
# Sous Linux, APPDIR correspond au dossier lib/app.
# On conserve le fichier utilisateur à la racine du package
# et on le synchronise avant chaque démarrage.
#

cp -f "$CONFIG" "$APP_CONFIG"

# ============================================================
# DEMARRAGE
# ============================================================

echo "============================================================"
echo " Xiaovv V380 Server"
echo " Linux autonome - Java embarque"
echo "============================================================"
echo

exec "$LAUNCHER"
