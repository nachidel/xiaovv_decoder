# Configuration Xiaovv

Ce document décrit la configuration actuelle de Xiaovv et la manière recommandée de conserver les secrets hors du projet.

## 1. `application.properties`

La configuration principale contient :

- RTSP ;
- API HTTP ;
- caméras.

Exemple :

```properties
rtsp.bind-address=0.0.0.0
rtsp.port=8555

api.bind-address=0.0.0.0
api.port=8080
api.token-env=XIAOVV_API_TOKEN

camera.garage.enabled=true
camera.garage.stream-name=garage
camera.garage.host=192.168.1.100
camera.garage.port=8800
camera.garage.device-id=XXXXXXXX
camera.garage.username=XXXXXXXX
camera.garage.password-env=XIAOVV_CAMERA_GARAGE_PASSWORD
camera.garage.resolution=high
camera.garage.reconnect-delay-ms=5000
```

### Emplacement du fichier principal

Priorité :

1. propriété JVM `-Dxiaovv.config=...` ;
2. variable `XIAOVV_CONFIG` ;
3. fichier/ressource `application.properties` selon le mode de lancement.

Exemple :

```powershell
$env:XIAOVV_CONFIG="D:\xiaovv\application.properties"
```

## 2. Configuration RTSP

| Clé | Défaut | Description |
|---|---:|---|
| `rtsp.bind-address` | `0.0.0.0` | adresse d’écoute |
| `rtsp.port` | `8555` | port RTSP |

Le serveur actuel utilise **RTSP/TCP interleaved**.

## 3. Configuration API

| Clé | Défaut | Description |
|---|---:|---|
| `api.bind-address` | `127.0.0.1` | adresse d’écoute HTTP |
| `api.port` | `8080` | port HTTP |
| `api.token-env` | `XIAOVV_API_TOKEN` | nom de la variable contenant le token |
| `api.token` | — | fallback en clair, déconseillé |

Pour le dashboard accessible depuis le LAN et pour Google Cast :

```properties
api.bind-address=0.0.0.0
```

## 4. Caméras

Format :

```text
camera.<id>.<clé>
```

| Clé | Obligatoire | Défaut |
|---|---:|---|
| `enabled` | non | `true` |
| `stream-name` | non | ID |
| `host` | oui | — |
| `port` | non | `8800` |
| `device-id` | oui | — |
| `username` | non | Device ID |
| `password-env` | recommandé | — |
| `password` | non | — |
| `resolution` | non | `high` |
| `reconnect-delay-ms` | non | `5000` |

IDs et noms de stream acceptent :

```text
A-Z a-z 0-9 _ -
```

Deux caméras actives ne doivent pas utiliser le même `stream-name`.

## 5. Variables par caméra

Convention automatique :

```text
XIAOVV_CAMERA_<ID>_<CLE>
```

Exemple pour `garage` :

```text
XIAOVV_CAMERA_GARAGE_ENABLED
XIAOVV_CAMERA_GARAGE_STREAM_NAME
XIAOVV_CAMERA_GARAGE_HOST
XIAOVV_CAMERA_GARAGE_PORT
XIAOVV_CAMERA_GARAGE_DEVICE_ID
XIAOVV_CAMERA_GARAGE_USERNAME
XIAOVV_CAMERA_GARAGE_PASSWORD
XIAOVV_CAMERA_GARAGE_RESOLUTION
XIAOVV_CAMERA_GARAGE_RECONNECT_DELAY_MS
```

Le mot de passe peut aussi être associé à une variable choisie explicitement :

```properties
camera.garage.password-env=MON_SECRET_CAMERA
```

## 6. Configuration MQTT

La configuration MQTT est volontairement séparée dans `mqtt.properties`.

Emplacement, par ordre de priorité :

1. `-Dxiaovv.mqtt.config=/chemin/mqtt.properties` ;
2. `XIAOVV_MQTT_CONFIG` ;
3. `mqtt.properties` à côté du fichier principal lorsque `XIAOVV_CONFIG` ou `xiaovv.config` est utilisé ;
4. `src/main/resources/mqtt.properties` en développement ;
5. `mqtt.properties` dans le répertoire courant.

Exemple :

```properties
mqtt.ids=jeedom

mqtt.jeedom.name=MQTT Jeedom
mqtt.jeedom.host=192.168.1.10
mqtt.jeedom.port=1883
mqtt.jeedom.tls=false
mqtt.jeedom.enabled=true
mqtt.jeedom.username=dashboard
mqtt.jeedom.password-env=XIAOVV_MQTT_JEEDOM_PASSWORD
```

### Mot de passe MQTT

Priorité :

1. variable indiquée par `mqtt.<id>.password-env` ;
2. variable automatique `XIAOVV_MQTT_<ID>_PASSWORD` ;
3. ancien `mqtt.<id>.password` en clair.

Le troisième mode n’est conservé que pour compatibilité.

## 7. FFmpeg

Résolution du binaire :

1. `-Dxiaovv.ffmpeg=...`
2. `XIAOVV_FFMPEG`
3. `ffmpeg` dans le `PATH`

Exemple Windows :

```powershell
$env:XIAOVV_FFMPEG="C:\ffmpeg\bin\ffmpeg.exe"
```

FFmpeg est utilisé par le mur MJPEG, les conversions vidéo HTTP, les snapshots et le Cast.

## 8. Configuration à chaud

L’interface Web permet de gérer les caméras.

Une sauvegarde :

1. met à jour le fichier externe éditable ;
2. recharge la liste des caméras ;
3. conserve les superviseurs inchangés ;
4. recrée uniquement les caméras modifiées ;
5. ajoute et retire les streams RTSP nécessaires.

Si une réapplication runtime échoue après l’écriture disque, l’API peut signaler qu’un redémarrage est nécessaire. La réapplication runtime n’est pas une transaction complète avec rollback.

## 9. IntelliJ IDEA

Chemin :

```text
Run → Edit Configurations → Environment variables
```

Exemple :

```text
XIAOVV_API_TOKEN=...
XIAOVV_CAMERA_GARAGE_PASSWORD=...
XIAOVV_MQTT_JEEDOM_PASSWORD=...
XIAOVV_CONFIG=D:\xiaovv\application.properties
XIAOVV_MQTT_CONFIG=D:\xiaovv\mqtt.properties
```

Ne pas mettre les valeurs réelles des secrets dans la documentation ou dans Git.
