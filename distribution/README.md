# Xiaovv — distribution

## Installation rapide

1. Copier :

```text
application.properties.example -> application.properties
mqtt.properties.example        -> mqtt.properties
```

2. Copier le fichier d’environnement adapté :

Windows PowerShell :

```text
xiaovv-env.example.ps1 -> xiaovv-env.ps1
```

Windows BAT :

```text
xiaovv-env.example.bat -> xiaovv-env.bat
```

Linux :

```text
xiaovv-env.example.sh -> xiaovv-env.sh
```

3. Mettre les secrets uniquement dans le fichier d’environnement.

Exemple :

```text
XIAOVV_API_TOKEN
XIAOVV_CAMERA_GARAGE_PASSWORD
XIAOVV_MQTT_JEEDOM_PASSWORD
```

4. Démarrer.

PowerShell :

```powershell
.\start.ps1
```

Linux :

```bash
chmod +x start.sh
./start.sh
```

## Fichiers de configuration

### `application.properties`

Caméras, RTSP et API.

### `mqtt.properties`

Brokers MQTT.

Ne pas y stocker le mot de passe si une variable d’environnement est disponible.

## FFmpeg

Pour le mur vidéo, les snapshots et Google Cast, FFmpeg doit être disponible.

Si nécessaire :

```text
XIAOVV_FFMPEG=<chemin vers ffmpeg>
```

## Réseau

Par défaut :

```text
RTSP : 8555/TCP
HTTP : 8080/TCP
```

Ne pas exposer directement ces ports sur Internet.
