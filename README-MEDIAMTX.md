# MediaMTX avec Xiaovv

Ce document décrit la configuration recommandée de **MediaMTX** avec le serveur **Xiaovv V380 RTSP**.

## Architecture

```text
Caméras Xiaovv / V380
        │
        │ protocole propriétaire TCP / 8800
        ▼
┌─────────────────────────────┐
│        Xiaovv Server        │
│ RTSP : 8555                 │
│ API  : 8080                 │
└──────────────┬──────────────┘
               │ RTSP / TCP
               ▼
┌─────────────────────────────┐
│          MediaMTX           │
│ RTSP    : 8554              │
│ HLS     : 8888              │
│ WebRTC  : 8889              │
│ WebRTC UDP : 8189           │
└──────────────┬──────────────┘
               │
               ├── VLC
               ├── FFmpeg
               ├── OBS
               ├── Jeedom
               └── Navigateur Web
```

Xiaovv reste responsable du protocole V380, de l'authentification caméra, du H.265, du RTP et des commandes PTZ / lumière / image.

MediaMTX se connecte uniquement aux flux RTSP produits par Xiaovv.

---

## Ports

| Service | Port | Protocole |
|---|---:|---|
| Xiaovv RTSP | `8555` | TCP |
| Xiaovv API HTTP | `8080` | TCP |
| MediaMTX RTSP | `8554` | TCP / UDP |
| MediaMTX HLS | `8888` | HTTP |
| MediaMTX WebRTC | `8889` | HTTP |
| MediaMTX WebRTC média | `8189` | UDP |

Les ports RTSP `8554` et `8555` sont volontairement différents afin que Xiaovv et MediaMTX puissent fonctionner sur la même machine.

---

## Flux Xiaovv

Exemples :

```text
rtsp://127.0.0.1:8555/garage
rtsp://127.0.0.1:8555/parking
rtsp://127.0.0.1:8555/salle
rtsp://127.0.0.1:8555/salon
rtsp://127.0.0.1:8555/sejour
```

Si MediaMTX est installé sur une autre machine, remplacer `127.0.0.1` par l'adresse IP de la machine Xiaovv.

---

# Configuration `mediamtx.yml`

Configuration recommandée :

```yaml
###############################################
# MediaMTX + Xiaovv
###############################################

logLevel: info

###############################################
# RTSP
###############################################

rtsp: true
rtspAddress: :8554

###############################################
# HLS
###############################################

hls: true
hlsAddress: :8888

###############################################
# WebRTC
###############################################

webrtc: true
webrtcAddress: :8889
webrtcLocalUDPAddress: :8189

###############################################
# Paramètres communs aux sources RTSP Xiaovv
###############################################

pathDefaults:

  # Xiaovv transporte le RTP dans la connexion RTSP TCP.
  rtspTransport: tcp

  # MediaMTX ouvre le flux Xiaovv uniquement lorsqu'un lecteur
  # demande réellement ce chemin.
  sourceOnDemand: yes

###############################################
# Caméras
###############################################

paths:

  garage:
    source: rtsp://127.0.0.1:8555/garage

  parking:
    source: rtsp://127.0.0.1:8555/parking

  salle:
    source: rtsp://127.0.0.1:8555/salle

  salon:
    source: rtsp://127.0.0.1:8555/salon

  sejour:
    source: rtsp://127.0.0.1:8555/sejour
```

---

## Pourquoi `rtspTransport: tcp` ?

Le serveur RTSP Xiaovv utilise le transport RTP interleaved sur TCP.

Il est donc recommandé de forcer MediaMTX à utiliser :

```yaml
rtspTransport: tcp
```

Cela évite qu'il tente de négocier un flux RTP/UDP avec Xiaovv.

---

## `sourceOnDemand`

Avec :

```yaml
sourceOnDemand: yes
```

MediaMTX ouvre le flux Xiaovv uniquement lorsqu'un client demande le chemin.

Exemple :

```text
VLC demande /garage
        ↓
MediaMTX ouvre
rtsp://127.0.0.1:8555/garage
        ↓
Xiaovv fournit le H.265
```

Pour conserver les connexions MediaMTX vers Xiaovv ouvertes en permanence :

```yaml
sourceOnDemand: no
```

MediaMTX ne transcode pas la vidéo dans cette configuration : le flux H.265 est relayé.

---

# Démarrage

Démarrer Xiaovv en premier.

Tester directement un flux Xiaovv :

```text
rtsp://127.0.0.1:8555/garage
```

Puis démarrer MediaMTX.

Linux :

```bash
./mediamtx mediamtx.yml
```

Windows :

```powershell
.\mediamtx.exe mediamtx.yml
```

---

# Accès RTSP via MediaMTX

Une fois MediaMTX démarré :

```text
rtsp://IP_DU_SERVEUR:8554/garage
rtsp://IP_DU_SERVEUR:8554/parking
rtsp://IP_DU_SERVEUR:8554/salle
rtsp://IP_DU_SERVEUR:8554/salon
rtsp://IP_DU_SERVEUR:8554/sejour
```

Avec FFplay :

```bash
ffplay -rtsp_transport tcp rtsp://IP_DU_SERVEUR:8554/garage
```

Avec VLC :

```text
Média
→ Ouvrir un flux réseau
→ rtsp://IP_DU_SERVEUR:8554/garage
```

---

# Accès HLS

Page de lecture :

```text
http://IP_DU_SERVEUR:8888/garage
```

Playlist HLS :

```text
http://IP_DU_SERVEUR:8888/garage/index.m3u8
```

Même principe pour les autres chemins :

```text
http://IP_DU_SERVEUR:8888/parking
http://IP_DU_SERVEUR:8888/salle
```

Le HLS a davantage de latence que RTSP ou WebRTC, mais il est simple à intégrer dans de nombreux clients.

---

# Accès WebRTC

Page WebRTC :

```text
http://IP_DU_SERVEUR:8889/garage
```

Endpoint WHEP :

```text
http://IP_DU_SERVEUR:8889/garage/whep
```

## H.265 et navigateurs

Xiaovv fournit actuellement de la vidéo **H.265 / HEVC**.

MediaMTX sait transporter le H.265, mais le décodage dans le navigateur dépend du navigateur, du système d'exploitation et du matériel.

Si RTSP fonctionne dans VLC mais pas WebRTC dans un navigateur, vérifier en premier lieu la compatibilité H.265 du navigateur.

---

# Utilisation avec Jeedom

Pour la vidéo, utiliser de préférence les flux MediaMTX :

```text
rtsp://IP_DU_SERVEUR:8554/garage
```

L'API Xiaovv reste indépendante pour le pilotage :

```text
http://IP_DU_SERVEUR:8080/
```

Elle continue de gérer notamment :

- PTZ ;
- lumière ;
- couleur ;
- noir et blanc / IR ;
- mode automatique ;
- retournement de l'image.

Architecture logique :

```text
                    ┌──── RTSP :8554 ─── VLC / Jeedom / OBS
                    │
Caméras → Xiaovv → MediaMTX ─ HLS :8888 ─── navigateur
           │        │
           │        └──── WebRTC :8889 ─── navigateur
           │
           └──────── API :8080 ────────── commandes caméra
```

---

# MediaMTX sur une autre machine

Exemple avec Xiaovv sur `192.168.1.50` :

```yaml
paths:

  garage:
    source: rtsp://192.168.1.50:8555/garage

  parking:
    source: rtsp://192.168.1.50:8555/parking

  salle:
    source: rtsp://192.168.1.50:8555/salle

  salon:
    source: rtsp://192.168.1.50:8555/salon

  sejour:
    source: rtsp://192.168.1.50:8555/sejour
```

Le port `8555/TCP` doit alors être accessible entre les deux machines.

---

# Ajouter une caméra

Si Xiaovv expose :

```text
rtsp://127.0.0.1:8555/entree
```

ajouter :

```yaml
paths:
  entree:
    source: rtsp://127.0.0.1:8555/entree
```

Les nouvelles sorties deviennent :

```text
rtsp://IP_DU_SERVEUR:8554/entree
http://IP_DU_SERVEUR:8888/entree
http://IP_DU_SERVEUR:8889/entree
```

---

# Rechargement de la configuration

MediaMTX surveille son fichier `mediamtx.yml` et peut appliquer de nombreuses modifications à chaud.

Pour une modification importante, un redémarrage complet reste recommandé afin de repartir d'un état connu.

---

# Diagnostic

## 1. Tester Xiaovv directement

```bash
ffplay -rtsp_transport tcp rtsp://127.0.0.1:8555/garage
```

Si cela ne fonctionne pas, le problème se situe avant MediaMTX.

## 2. Tester MediaMTX

```bash
ffplay -rtsp_transport tcp rtsp://127.0.0.1:8554/garage
```

Si Xiaovv fonctionne mais pas MediaMTX, vérifier le chemin `source:` et les logs MediaMTX.

## 3. `connection refused`

Vérifier que Xiaovv écoute bien sur le port `8555`.

Linux :

```bash
ss -lntp | grep 8555
```

## 4. RTSP fonctionne mais HLS ne fonctionne pas

Vérifier :

```yaml
hls: true
hlsAddress: :8888
```

Puis :

```text
http://IP_DU_SERVEUR:8888/garage
```

## 5. RTSP fonctionne mais WebRTC ne fonctionne pas

Vérifier :

```yaml
webrtc: true
webrtcAddress: :8889
webrtcLocalUDPAddress: :8189
```

et vérifier que `8189/UDP` n'est pas bloqué.

## 6. RTSP fonctionne mais pas l'image WebRTC

Tester avec VLC ou FFplay.

Si le H.265 est correct en RTSP, le problème peut être lié au support HEVC du navigateur.

---

# Configuration RTSP minimale

Si HLS et WebRTC ne sont pas nécessaires :

```yaml
logLevel: info

rtsp: true
rtspAddress: :8554

hls: false
webrtc: false

pathDefaults:
  rtspTransport: tcp
  sourceOnDemand: yes

paths:

  garage:
    source: rtsp://127.0.0.1:8555/garage

  parking:
    source: rtsp://127.0.0.1:8555/parking

  salle:
    source: rtsp://127.0.0.1:8555/salle

  salon:
    source: rtsp://127.0.0.1:8555/salon

  sejour:
    source: rtsp://127.0.0.1:8555/sejour
```

---

# Résumé

```text
Xiaovv
  RTSP source           : 8555
  API de pilotage       : 8080

MediaMTX
  RTSP clients          : 8554
  HLS                   : 8888
  WebRTC HTTP           : 8889
  WebRTC média          : 8189/UDP
```

Flux vidéo à utiliser côté clients :

```text
rtsp://IP_DU_SERVEUR:8554/<camera>
```

Commandes caméra :

```text
http://IP_DU_SERVEUR:8080/
```
