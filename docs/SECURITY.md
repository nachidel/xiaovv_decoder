# Sécurité

Xiaovv est destiné en priorité à un réseau local de confiance.

## Secrets

Ne pas stocker les secrets dans Git.

### API

```text
XIAOVV_API_TOKEN
```

### Caméras

```text
XIAOVV_CAMERA_<ID>_PASSWORD
```

### MQTT

```text
XIAOVV_MQTT_<ID>_PASSWORD
```

ou variable personnalisée avec `password-env`.

## Fichiers recommandés hors dépôt

```text
application.properties
mqtt.properties
xiaovv-env.bat
xiaovv-env.ps1
xiaovv-env.sh
.env
```

Les fichiers `.example` peuvent être commités à condition de ne contenir aucun secret réel.

## `.gitignore` conseillé

```gitignore
.env
.env.*
application.properties
mqtt.properties
xiaovv-env.bat
xiaovv-env.ps1
xiaovv-env.sh
*.log
*.pcap
```

Adapter si un `application.properties` sans secret doit volontairement rester versionné.

## API HTTP

L’API utilise un token mais le transport est HTTP.

Le token n’est donc pas chiffré sur le réseau.

Recommandations :

- LAN de confiance ;
- VPN pour accès distant ;
- pas de redirection de port Internet ;
- reverse proxy HTTPS uniquement si nécessaire et correctement configuré.

## RTSP

Le RTSP actuel :

- n’est pas chiffré ;
- ne possède pas d’authentification propre ;
- utilise TCP.

Ne pas exposer le port RTSP sur Internet.

## Boutons HTTP

`/api/http-action` permet au serveur Xiaovv de faire une requête HTTP vers une URL fournie par le dashboard.

Cette fonction peut être assimilée à une primitive de requête serveur générique.

Recommandations :

- API non exposée ;
- URLs uniquement vers des services LAN connus ;
- éviter les URLs contenant une clé/secrète ;
- envisager à terme une allowlist ou des actions identifiées côté serveur.

## Google Cast

Les receivers Google Cast ne peuvent pas envoyer le header `X-API-Token`.

Les fichiers média sont donc servis temporairement sous :

```text
/cast-media/<identifiant-aléatoire>/...
```

L’identifiant agit comme une URL temporaire difficile à deviner, mais ce n’est pas un remplacement d’une vraie couche HTTPS/authentification.

## Navigateur

Le token API est stocké dans `localStorage`.

Toute personne ayant accès au profil navigateur local peut potentiellement le lire.

Les boutons HTTP et les définitions de bulles sont également enregistrés localement.

## Logs

Ne jamais journaliser :

- mot de passe caméra ;
- mot de passe MQTT ;
- token API ;
- secrets d’URL.

Les adresses IP et IDs techniques peuvent également être sensibles lors du partage de logs.

## Archives de diagnostic

Avant d’envoyer un ZIP ou des logs :

- supprimer les fichiers de configuration réels ;
- supprimer les `.env` ;
- supprimer les captures réseau ;
- vérifier les URLs de boutons HTTP ;
- remplacer IPs/IDs si nécessaire.
