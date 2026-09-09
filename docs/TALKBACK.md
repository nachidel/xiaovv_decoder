# Interphone / Push-to-talk V380

Cette fonctionnalité permet de parler dans le haut-parleur d'une caméra
Xiaovv/V380 directement depuis le panneau de commandes du dashboard.

## Utilisation

Dans le mur vidéo :

1. ouvrir les commandes de la caméra ;
2. maintenir **🎙 Maintenir pour parler** ;
3. parler ;
4. relâcher le bouton pour couper immédiatement l'interphone.

Le bouton devient rouge pendant l'émission.

## API

Routes protégées par le même token API que les autres commandes :

```text
POST /api/cameras/{id}/talkback/start
POST /api/cameras/{id}/talkback/chunk
POST /api/cameras/{id}/talkback/stop
GET  /api/cameras/{id}/talkback/status
```

`chunk` reçoit du PCM :

```text
PCM 16 bits little-endian
mono
8 kHz
application/octet-stream
```

## Pipeline

```text
Micro navigateur
  -> PCM16 mono
  -> rééchantillonnage 8 kHz
  -> API Xiaovv
  -> IMA ADPCM
  -> chiffrement AES pour les protocoles V380 récents
  -> socket TCP V380 dédiée :8800
  -> haut-parleur caméra
```

Le flux montant est indépendant du flux RTSP entrant.

## Faible latence

Les files audio sont volontairement courtes. Si le réseau ou le navigateur
prend du retard, les blocs les plus anciens sont abandonnés plutôt que de
faire sortir la voix plusieurs secondes en retard.

Une session sans audio est automatiquement fermée côté serveur après quelques
secondes afin qu'un onglet fermé ou une perte réseau ne laisse pas la caméra
bloquée en mode interphone.

## Microphone et HTTPS

Les navigateurs autorisent `getUserMedia()` uniquement dans un contexte
sécurisé.

Cela fonctionne sur :

```text
http://localhost:8080/
```

Pour une autre machine ou un téléphone accédant à Xiaovv par son adresse LAN,
prévoir HTTPS, par exemple avec un reverse proxy.

## État de validation

Le code est conçu pour le protocole V380 observé dans Xiaovv, notamment les
caméras utilisant la version 31.

La validation définitive du rendu audio doit être faite sur le matériel réel,
car les firmwares V380 peuvent varier.

## Licence / provenance

Aucun code source tiers n'est incorporé par ce patch. L'implémentation Kotlin
est écrite pour Xiaovv à partir des comportements d'interopérabilité du
protocole V380 et de l'algorithme standard IMA ADPCM.
