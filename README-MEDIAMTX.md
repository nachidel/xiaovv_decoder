# MediaMTX avec Xiaovv — optionnel

MediaMTX n’est **pas requis** pour le mur vidéo Xiaovv ni pour Google Cast.

Ce document ne concerne que les installations qui souhaitent ajouter un relais externe pour :

- RTSP supplémentaire ;
- HLS ;
- WebRTC ;
- intégrations tierces.

## Architecture

```text
Caméras V380
    ↓
Xiaovv
RTSP :8555
API  :8080
    ↓ RTSP/TCP
MediaMTX
    ├─ RTSP
    ├─ HLS
    └─ WebRTC
```

Xiaovv reste responsable :

- du protocole V380 ;
- de l’authentification ;
- du H.265/AAC ;
- du RTSP source ;
- des commandes caméra.

MediaMTX ne fait que consommer le RTSP Xiaovv.

## Configuration minimale

Exemple :

```yaml
logLevel: info

rtsp: true
rtspAddress: :8554

pathDefaults:
  rtspTransport: tcp
  sourceOnDemand: yes

paths:
  garage:
    source: rtsp://127.0.0.1:8555/garage

  parking:
    source: rtsp://127.0.0.1:8555/parking
```

Le point important est :

```yaml
rtspTransport: tcp
```

car Xiaovv sert actuellement RTP en RTSP/TCP interleaved.

## `sourceOnDemand`

Recommandé :

```yaml
sourceOnDemand: yes
```

MediaMTX n’ouvre alors la caméra via Xiaovv que lorsqu’un client demande réellement le chemin.

## Tests

Xiaovv direct :

```bash
ffplay -rtsp_transport tcp rtsp://127.0.0.1:8555/garage
```

MediaMTX :

```bash
ffplay -rtsp_transport tcp rtsp://127.0.0.1:8554/garage
```

Toujours valider d’abord le flux Xiaovv direct avant de diagnostiquer MediaMTX.

## HLS / WebRTC

MediaMTX peut exposer ces protocoles selon sa propre configuration.

Attention : le flux source Xiaovv est H.265. La compatibilité du décodage H.265 dans un navigateur dépend du navigateur, de l’OS et du matériel.

MediaMTX ne doit pas être présenté comme une dépendance de Xiaovv : il s’agit d’un composant externe facultatif.
