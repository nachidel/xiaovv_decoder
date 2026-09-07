# Xiaovv / V380

Serveur Kotlin/JVM autonome pour caméras **Xiaovv / V380** utilisant le protocole natif de la caméra sur le réseau local.

Xiaovv sait aujourd’hui publier les caméras en **RTSP H.265 + AAC**, fournir un **mur vidéo Web**, piloter les caméras, gérer la configuration à chaud, lire des informations **MQTT** et diffuser une caméra sur des appareils **Google Cast** compatibles.

> Le projet est conçu en priorité pour un usage sur un LAN de confiance. L’API HTTP et le RTSP ne doivent pas être exposés directement sur Internet.

## Fonctionnalités actuelles

### Caméras

- connexion native V380 sur TCP `8800` ;
- authentification V380 ;
- vidéo H.265 / HEVC ;
- audio AAC ;
- plusieurs caméras configurées dynamiquement ;
- reconnexion indépendante par caméra ;
- activation à la demande lorsqu’un client RTSP lance réellement `PLAY` ;
- PTZ haut / bas / gauche / droite / stop ;
- lumière ON / OFF / AUTO ;
- image couleur / N&B-IR / AUTO ;
- retournement 180°.

### RTSP

- serveur RTSP intégré ;
- H.265 en RTP, payload type 96 ;
- AAC en RTP, payload type 97 ;
- transport RTP interleaved sur RTSP/TCP ;
- plusieurs clients sur une même caméra ;
- attente d’une keyframe avant le démarrage vidéo.

Format :

```text
rtsp://IP_DU_SERVEUR:8555/<stream-name>
```

### Interface Web

- dashboard sur `/` et `/live` ;
- mur vidéo MJPEG ;
- sélection, ordre et redimensionnement des caméras ;
- contrôles caméra sous les vidéos ;
- snapshots ;
- boutons d’action HTTP configurables ;
- bulles d’information MQTT ;
- édition de la configuration des caméras ;
- configuration globale des brokers MQTT ;
- bouton Google Cast dans chaque vidéo ;
- sauvegarde de la disposition du dashboard dans `localStorage`.

### Configuration à chaud

La configuration caméra peut être modifiée depuis l’interface. Les caméras ajoutées, modifiées ou supprimées sont réappliquées au runtime sans redémarrage lorsque l’opération peut être effectuée correctement.

### MQTT

- plusieurs brokers ;
- MQTT 3.1.1 minimal intégré ;
- TCP ou TLS ;
- authentification utilisateur / mot de passe ;
- secrets par variables d’environnement ;
- reconnexion automatique ;
- abonnement aux topics exacts utilisés par les bulles ;
- valeur numérique, texte ou booléenne ;
- unité, décimales, délai de péremption et extraction JSON simple.

### Google Cast

Le flux Cast est produit séparément du RTSP principal :

```text
Caméra
  ↓
RTSP local Xiaovv
  ↓
FFmpeg
  ↓
MPEG-DASH / H.264
  ↓
Nest Hub / écran Google Cast
```

Le profil Cast actuel est volontairement conservateur pour maximiser la compatibilité :

- H.264 Baseline level 3.0 ;
- 640×360 ;
- 10 fps ;
- vidéo seule pour le moment ;
- DASH/fMP4 ;
- watchdog du receiver et de la production des fragments.

## Prérequis

### Développement

- JDK 21 ;
- Gradle Wrapper du projet.

### Exécution

Le cœur RTSP n’a pas besoin de FFmpeg.

**FFmpeg est requis** pour les fonctions qui transcodent ou convertissent localement :

- mur vidéo MJPEG ;
- certaines routes vidéo HTTP ;
- snapshots ;
- Google Cast.

FFmpeg est recherché dans cet ordre :

1. `-Dxiaovv.ffmpeg=...`
2. `XIAOVV_FFMPEG`
3. `ffmpeg` dans le `PATH`.

## Démarrage rapide depuis le projet

Configurer au minimum :

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

Puis définir les secrets hors du projet.

PowerShell :

```powershell
$env:XIAOVV_API_TOKEN="un-jeton-long"
$env:XIAOVV_CAMERA_GARAGE_PASSWORD="mot-de-passe-camera"

.\gradlew.bat run
```

Linux :

```bash
export XIAOVV_API_TOKEN='un-jeton-long'
export XIAOVV_CAMERA_GARAGE_PASSWORD='mot-de-passe-camera'

./gradlew run
```

Interface :

```text
http://IP_DU_SERVEUR:8080/
```

RTSP :

```text
rtsp://IP_DU_SERVEUR:8555/garage
```

## Configuration externe recommandée

Pour éviter d’embarquer la configuration dans le projet :

```text
XIAOVV_CONFIG=/chemin/application.properties
XIAOVV_MQTT_CONFIG=/chemin/mqtt.properties
```

Les secrets restent dans des variables d’environnement :

```text
XIAOVV_API_TOKEN
XIAOVV_CAMERA_<ID>_PASSWORD
XIAOVV_MQTT_<ID>_PASSWORD
```

Voir [docs/CONFIGURATION.md](docs/CONFIGURATION.md).

## Documentation

| Document | Contenu |
|---|---|
| [Configuration](docs/CONFIGURATION.md) | fichiers, caméras, secrets, FFmpeg |
| [API HTTP](docs/API.md) | routes HTTP actuelles |
| [Interface Web](docs/WEB-UI.md) | dashboard, mur, actions, stockage navigateur |
| [MQTT](docs/MQTT.md) | brokers, bulles, secrets, diagnostic |
| [Google Cast](docs/CAST.md) | pipeline DASH, prérequis et dépannage |
| [Architecture](docs/ARCHITECTURE.md) | composants et flux internes |
| [Sécurité](docs/SECURITY.md) | secrets, exposition réseau, HTTP actions |
| [Dépannage](docs/TROUBLESHOOTING.md) | problèmes fréquents |
| [Checklist release](docs/RELEASE-CHECKLIST.md) | vérifications avant packaging |
| [Packaging portable](PACKAGING.md) | ZIP portable |
| [Packaging Linux autonome](LINUX-PACKAGING.md) | `jpackage` Linux |
| [MediaMTX](README-MEDIAMTX.md) | relais externe optionnel |

## Ports par défaut

| Service | Port | Remarque |
|---|---:|---|
| Caméra V380 native | `8800/TCP` | côté caméra |
| Xiaovv RTSP | `8555/TCP` | RTP interleaved |
| Xiaovv API / Web | `8080/TCP` | HTTP |

## MediaMTX

**MediaMTX n’est pas nécessaire au fonctionnement normal de Xiaovv.**

Le mur Web intégré et Google Cast utilisent directement Xiaovv. MediaMTX reste utile uniquement si l’on souhaite ajouter un relais RTSP externe, du HLS ou du WebRTC pour d’autres clients.

Voir [README-MEDIAMTX.md](README-MEDIAMTX.md).

## Limites actuelles importantes

- pas de RTP/UDP côté serveur RTSP ;
- pas d’authentification RTSP ;
- pas de HTTPS/TLS intégré pour l’API HTTP ;
- Cast actuellement **vidéo seule** ;
- le décodage Web direct du H.265 dépend toujours du client ;
- les bulles MQTT utilisent des topics exacts : `#` et `+` ne sont pas acceptés ;
- les boutons HTTP et les bulles d’information sont principalement configurés dans le navigateur ;
- l’endpoint d’action HTTP doit être considéré comme une fonction puissante à réserver à un LAN de confiance.

## Avertissement

Ce projet repose sur l’interopérabilité avec un protocole propriétaire Xiaovv / V380 et sur les comportements validés avec le matériel testé.

Des différences de firmware ou de modèle peuvent exister.

## Licence

Xiaovv Decoder est un logiciel **source-available** distribué sous **PolyForm Noncommercial License 1.0.0** (`PolyForm-Noncommercial-1.0.0`).

Les usages non commerciaux autorisés par cette licence sont gratuits. Le texte officiel de la licence fait foi :

https://polyformproject.org/licenses/noncommercial/1.0.0

**Aucun droit d'utilisation commerciale n'est accordé par la licence publique.**

Toute utilisation commerciale de Xiaovv Decoder nécessite un accord commercial écrit séparé avec **Michaël Dumont**. Cela concerne notamment l'intégration dans un produit ou service payant, la redistribution dans une offre commerciale, un service hébergé ou managé, ou une prestation facturée reposant sur Xiaovv Decoder.

Voir : [COMMERCIAL-LICENSING.md](COMMERCIAL-LICENSING.md)

Copyright © 2026 Michaël Dumont.

Les bibliothèques tierces restent soumises à leurs propres licences. Voir [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
