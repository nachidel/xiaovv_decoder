# Google Cast

Xiaovv peut envoyer une caméra vers un appareil Google Cast découvert sur le LAN, notamment un Nest Hub ou un téléviseur compatible.

## Pipeline actuel

```text
Caméra V380
   ↓
RTSP H.265 Xiaovv
   ↓ rtsp://127.0.0.1:8555/<stream>
FFmpeg
   ↓ H.264
MPEG-DASH / fMP4
   ↓ HTTP
Google Cast Default Media Receiver
```

Le Cast est donc un client supplémentaire du RTSP local.

## Prérequis

- appareil Cast sur le même réseau accessible ;
- mDNS fonctionnel ;
- API Xiaovv accessible depuis le LAN ;
- firewall autorisant le port HTTP Xiaovv ;
- FFmpeg ;
- encodeur `libx264` disponible.

Pour le Cast, éviter :

```properties
api.bind-address=127.0.0.1
```

Utiliser par exemple :

```properties
api.bind-address=0.0.0.0
```

## Découverte

Xiaovv démarre la découverte Google Cast au lancement.

Le sélecteur du dashboard affiche :

- nom ;
- modèle si disponible ;
- adresse ;
- port.

Sur Windows avec plusieurs interfaces réseau, VPN ou firewall strict, mDNS peut nécessiter des ajustements.

## Démarrage

Dans le dashboard :

1. ouvrir une caméra ;
2. cliquer sur l’icône Cast ;
3. choisir l’écran.

Le serveur démarre un FFmpeg dédié puis charge le media receiver.

## Profil média actuel

Le profil est volontairement conservateur car il a été choisi pour les Nest Hub testés :

```text
H.264 Baseline level 3.0
640 × 360
10 fps
yuv420p
sans B-frame
vidéo seule
MPEG-DASH / fMP4
segments de 2 s
```

**L’audio Cast n’est pas réactivé pour le moment**, même si le RTSP Xiaovv contient bien l’audio AAC.

## Timestamps

Certaines caméras produisent des timestamps irréguliers.

Pour éviter de propager ces trous au DASH, FFmpeg :

- utilise l’horloge d’arrivée des paquets ;
- régénère les timestamps ;
- reconstruit une timeline vidéo à 10 fps constants.

Les WARN `Timestamp caméra discontinu` peuvent donc encore apparaître dans le serveur RTSP sans forcément casser le Cast.

## Watchdogs

Deux contrôles sont distincts.

### Production FFmpeg

Si le process FFmpeg reste vivant mais ne fabrique plus de nouveaux fragments DASH pendant environ 12 secondes, l’encodeur est redémarré.

### Receiver Cast

Si l’écran ne demande plus de fragments pendant environ 40 secondes, le media receiver est rechargé.

Le statut Cast (`PLAYING`, `BUFFERING`, `IDLE`...) sert surtout au diagnostic.

## Arrêt

Cliquer à nouveau sur l’icône Cast active :

- arrête le receiver ;
- arrête FFmpeg ;
- libère la demande RTSP ;
- supprime les fichiers DASH temporaires.

## Contraintes actuelles

- une caméra n’est diffusée que vers un écran à la fois ;
- un écran ne reçoit qu’une session Xiaovv à la fois ;
- le Cast actuel est vidéo seule ;
- le media receiver accède à une route HTTP temporaire non authentifiée par header.

Cette route contient un identifiant de session aléatoire et n’est valide que pendant la session.

## Dépannage

### Aucun appareil dans la liste

Vérifier :

- même LAN/VLAN ;
- multicast/mDNS ;
- UDP 5353 ;
- isolation Wi-Fi ;
- firewall Windows ;
- VPN.

### Le Hub apparaît mais aucun flux

Vérifier :

- `api.bind-address=0.0.0.0` ;
- port API accessible depuis le Hub ;
- FFmpeg présent ;
- `libx264` disponible.

### `Unknown encoder 'libx264'`

Utiliser une distribution FFmpeg incluant `libx264`.

### Le flux démarre puis bufferise

Chercher dans les logs :

```text
état Cast avant relance : state=BUFFERING
```

et :

```text
encodeur DASH vivant mais aucun nouveau fragment depuis ...
```

Les gros sauts de timestamps provenant de certaines caméras sont également utiles pour le diagnostic.

### Barre de lecture qui réapparaît

Une interface de progression ponctuelle peut être normale sur un live. Une apparition répétitive avec retour à zéro correspond généralement à des phases de rechargement/buffering et doit être corrélée avec les logs Cast.
