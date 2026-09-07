# Package Linux autonome

La tâche :

```bash
./gradlew clean packageLinux
```

doit être exécutée **sous Linux**, avec un **JDK 21 contenant `jpackage`**.

Le package produit est natif pour l’architecture de la machine de build.

Exemples :

```text
Linux x86_64       -> xiaovv-<version>-linux-x64.tar.gz
Linux ARM64/aarch64 -> xiaovv-<version>-linux-arm64.tar.gz
```

## Installation

```bash
tar -xzf xiaovv-<version>-linux-arm64.tar.gz
cd xiaovv
```

Créer les configurations :

```bash
cp application.properties.example application.properties
cp mqtt.properties.example mqtt.properties
cp xiaovv-env.example.sh xiaovv-env.sh
```

Éditer :

```bash
nano application.properties
nano mqtt.properties
nano xiaovv-env.sh
```

Puis :

```bash
chmod +x start.sh
./start.sh
```

## Secrets

Exemple `xiaovv-env.sh` :

```bash
export XIAOVV_API_TOKEN='...'
export XIAOVV_CAMERA_GARAGE_PASSWORD='...'
export XIAOVV_MQTT_JEEDOM_PASSWORD='...'
```

Le mot de passe MQTT ne doit pas être écrit dans `mqtt.properties`.

## Runtime Java

Java est embarqué dans le package autonome généré par `jpackage`.

Le JDK 21 + `jpackage` est nécessaire uniquement sur la machine de build.

## Architecture

`jpackage` ne fait pas de cross-compilation.

Pour produire un ARM64 Linux, construire sur un environnement Linux ARM64.

## FFmpeg

Le runtime Java embarqué n’implique pas que FFmpeg soit embarqué.

Si FFmpeg est installé ailleurs :

```bash
export XIAOVV_FFMPEG='/usr/bin/ffmpeg'
```

## Service systemd

Le projet peut être lancé depuis un service `systemd`, mais le fichier d’unité dépend du chemin d’installation et n’est pas généré automatiquement dans cette documentation.

Veiller à déclarer les variables d’environnement du service, car les variables de votre shell interactif ne sont pas automatiquement héritées par `systemd`.
