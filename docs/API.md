# API HTTP Xiaovv

Base typique :

```text
http://IP_DU_SERVEUR:8080
```

## Authentification

Toutes les routes `/api/...` utilisent le même token.

Header recommandé :

```http
X-API-Token: VOTRE_TOKEN
```

ou :

```http
Authorization: Bearer VOTRE_TOKEN
```

Le token est généralement fourni par :

```text
XIAOVV_API_TOKEN
```

## Routes publiques

| Méthode | Route | Fonction |
|---|---|---|
| GET | `/` | dashboard |
| GET | `/live` | dashboard |
| GET/HEAD | `/cast-media/<session>/...` | média temporaire Cast |

Les URLs `/cast-media/...` utilisent un identifiant de session aléatoire et doivent rester sur le LAN.

## Caméras

| Méthode | Route |
|---|---|
| GET | `/api/cameras` |
| GET | `/api/cameras/{id}/status` |
| GET | `/api/cameras/{id}/snapshot` |
| GET | `/api/cameras/{id}/live.mjpeg` |
| GET | `/api/cameras/{id}/live.mp4` |
| GET/POST | `/api/cameras/{id}/ptz/up` |
| GET/POST | `/api/cameras/{id}/ptz/down` |
| GET/POST | `/api/cameras/{id}/ptz/left` |
| GET/POST | `/api/cameras/{id}/ptz/right` |
| GET/POST | `/api/cameras/{id}/ptz/stop` |
| GET/POST | `/api/cameras/{id}/light/on` |
| GET/POST | `/api/cameras/{id}/light/off` |
| GET/POST | `/api/cameras/{id}/light/auto` |
| GET/POST | `/api/cameras/{id}/image/color` |
| GET/POST | `/api/cameras/{id}/image/bw` |
| GET/POST | `/api/cameras/{id}/image/auto` |
| GET/POST | `/api/cameras/{id}/image/flip` |

Pour les intégrations normales, utiliser `POST` pour les commandes même si `GET` reste accepté.

## Configuration caméra

| Méthode | Route | Fonction |
|---|---|---|
| GET | `/api/config/cameras` | état éditable de la configuration |
| POST | `/api/config/cameras/save` | ajoute ou modifie une caméra |
| DELETE | `/api/config/cameras/{id}` | supprime la configuration |

Une réponse de sauvegarde peut indiquer :

- `runtimeApplied=true` : modification appliquée immédiatement ;
- `restartRequired=true` : fichier écrit mais runtime à redémarrer.

## Google Cast

| Méthode | Route |
|---|---|
| GET | `/api/cast/devices` |
| GET | `/api/cast/status` |
| POST | `/api/cast/start` |
| POST | `/api/cast/stop` |

Démarrage, formulaire :

```text
cameraId=garage
deviceAddress=<adresse de l'appareil détecté>
```

Arrêt :

```text
cameraId=garage
```

## MQTT

| Méthode | Route |
|---|---|
| GET | `/api/mqtt/servers` |
| POST | `/api/mqtt/servers/save` |
| DELETE | `/api/mqtt/servers/{id}` |
| GET | `/api/mqtt/value?server=<id>&topic=<topic>` |

`/api/mqtt/value` crée/maintient l’abonnement nécessaire au topic demandé puis retourne la dernière valeur connue et son horodatage.

Les wildcards `#` et `+` ne sont pas acceptés pour les bulles du dashboard.

## Actions HTTP

```text
POST /api/http-action
```

Cette route est utilisée par les boutons HTTP du dashboard afin que Xiaovv fasse la requête côté serveur.

C’est volontairement puissant : ne pas exposer cette API sur Internet et éviter les URLs non maîtrisées.

## Codes courants

| Code | Signification |
|---:|---|
| 200 | succès |
| 400 | requête invalide |
| 401 | token absent ou incorrect |
| 404 | caméra / route / ressource inconnue |
| 405 | méthode non autorisée |
| 500 | erreur interne |
| 502 | erreur d’accès à un service externe, notamment Cast |
| 503 | caméra indisponible pour certaines opérations |

## Exemples

Liste :

```bash
curl -H "X-API-Token: $XIAOVV_API_TOKEN" \
  http://127.0.0.1:8080/api/cameras
```

PTZ :

```bash
curl -X POST \
  -H "X-API-Token: $XIAOVV_API_TOKEN" \
  http://127.0.0.1:8080/api/cameras/garage/ptz/left
```

MQTT :

```bash
curl \
  -H "X-API-Token: $XIAOVV_API_TOKEN" \
  "http://127.0.0.1:8080/api/mqtt/value?server=jeedom&topic=maison/temperature"
```
