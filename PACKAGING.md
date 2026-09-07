# Packaging portable Xiaovv

Ce package est destiné à être extrait puis lancé avec un Java compatible.

## Construire

Windows :

```powershell
.\gradlew.bat clean packageRelease
```

Linux :

```bash
./gradlew clean packageRelease
```

## Contenu attendu

Exemple :

```text
build/
└── release/
    ├── xiaovv/
    │   ├── xiaovv.jar
    │   ├── README.md
    │   ├── application.properties.example
    │   ├── mqtt.properties.example
    │   ├── start.bat
    │   ├── start.ps1
    │   ├── start.sh
    │   ├── xiaovv-env.example.bat
    │   ├── xiaovv-env.example.ps1
    │   └── xiaovv-env.example.sh
    └── xiaovv-<version>-portable.zip
```

La release ne doit pas contenir :

```text
application.properties réel
mqtt.properties réel
xiaovv-env réel
secrets
captures réseau
logs privés
```

## Installation Windows

1. Extraire le ZIP.
2. Copier :

```text
application.properties.example -> application.properties
mqtt.properties.example        -> mqtt.properties
xiaovv-env.example.ps1          -> xiaovv-env.ps1
```

3. Configurer les caméras et brokers.
4. Mettre les secrets dans `xiaovv-env.ps1`.
5. Lancer :

```powershell
.\start.ps1
```

Alternative BAT :

```text
xiaovv-env.example.bat -> xiaovv-env.bat
start.bat
```

## Installation Linux

```bash
cp application.properties.example application.properties
cp mqtt.properties.example mqtt.properties
cp xiaovv-env.example.sh xiaovv-env.sh

chmod +x start.sh
./start.sh
```

## Configuration MQTT externe

Les lanceurs récents peuvent définir automatiquement :

```text
XIAOVV_MQTT_CONFIG=<répertoire distribution>/mqtt.properties
```

si l’utilisateur n’a pas déjà fourni une autre valeur.

Un chemin explicitement défini par l’utilisateur garde la priorité.

## Java

Cette distribution portable nécessite un Java compatible avec le bytecode du projet, actuellement JDK/JRE 21 recommandé.

Pour une distribution avec runtime Java embarqué, utiliser les tâches `jpackage` spécifiques à la plateforme.

## FFmpeg

FFmpeg n’est pas inclus automatiquement sauf si le packaging du projet a été explicitement configuré pour cela.

Il est nécessaire pour :

- mur MJPEG ;
- snapshots/conversions ;
- Cast.

Configurer éventuellement :

```text
XIAOVV_FFMPEG
```

## Vérification

Avant publication, appliquer [docs/RELEASE-CHECKLIST.md](docs/RELEASE-CHECKLIST.md).
