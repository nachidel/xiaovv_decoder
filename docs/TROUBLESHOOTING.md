# Dépannage

## Caméra V380

### `Connect timed out`

Vérifier :

1. caméra allumée ;
2. IP actuelle ;
3. DHCP ;
4. port `8800/TCP` ;
5. firewall ;
6. VLAN ;
7. isolation Wi-Fi.

Linux :

```bash
nc -vz IP_CAMERA 8800
```

### Authentification refusée

Vérifier :

```text
XIAOVV_CAMERA_<ID>_PASSWORD
```

et que `password-env` contient le **nom** de la variable, pas le secret.

## RTSP

### Lecteur ne reçoit rien

Forcer TCP :

```bash
ffplay -rtsp_transport tcp rtsp://IP_SERVEUR:8555/garage
```

### Écran noir au début

Le serveur attend une keyframe H.265 avant de démarrer l’envoi. Un délai court peut être normal.

### `Unsupported Transport`

Le client tente probablement RTP/UDP.

Le serveur actuel attend RTP interleaved sur RTSP/TCP.

## API

### 401

Vérifier :

```text
XIAOVV_API_TOKEN
```

et le header.

### Interface inaccessible depuis une autre machine

Vérifier :

```properties
api.bind-address=0.0.0.0
```

et le firewall.

## FFmpeg

### Mur vidéo vide

Tester :

```bash
ffmpeg -version
```

Puis vérifier :

```text
XIAOVV_FFMPEG
```

### `Unknown encoder 'libx264'`

Le Cast nécessite actuellement `libx264`.

## MQTT

### Broker `DISCONNECTED`

Vérifier :

- host ;
- port ;
- TLS ;
- user ;
- secret ;
- ACL.

### Variable automatique

Pour un broker `jeedom` :

```text
XIAOVV_MQTT_JEEDOM_PASSWORD
```

### Bulle vide

Vérifier :

- topic exact ;
- pas de `#` ou `+` ;
- valeur déjà publiée ;
- extraction JSON ;
- broker actif.

## Google Cast

### Aucun device

Vérifier mDNS / multicast / firewall / VLAN.

### Device visible mais pas d’image

Vérifier :

- API bind LAN ;
- firewall 8080 ;
- FFmpeg ;
- libx264.

### Buffering

Logs utiles :

```text
état Cast avant relance : state=BUFFERING
```

```text
encodeur DASH vivant mais aucun nouveau fragment depuis ...
```

## Timestamps

Des lignes comme :

```text
Timestamp caméra discontinu : ... resynchronisation
```

indiquent une discontinuité produite par la caméra/source.

Xiaovv resynchronise le RTP et le pipeline Cast régénère sa propre timeline.

Ces WARN ne signifient pas automatiquement que le serveur ou le Hub est en panne.

## Configuration à chaud

Si la sauvegarde réussit sur disque mais que la réapplication runtime échoue, l’API peut demander un redémarrage.

Toujours vérifier le message retourné par l’interface avant de conclure que la configuration n’a pas été enregistrée.
