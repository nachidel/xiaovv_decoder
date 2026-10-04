# API HTTP Xiaovv

Base typique :

```text
http://IP_DU_SERVEUR:8080
```

## Authentification

Les intégrations utilisent le même token pour les routes métier `/api/...`.
Ce token donne l'accès complet. Les sessions navigateur utilisent un cookie
de connexion et appliquent le rôle et les droits par caméra du compte.

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
| GET | `/login` | connexion ou création du premier administrateur |
| GET | `/api/auth/session` | état de connexion et jeton CSRF de la session |
| POST | `/api/auth/login` | connexion avec `username`, `password` |
| POST | `/api/auth/setup` | premier administrateur ; jeton API requis |
| GET/HEAD | `/favicon.webp`, `/auth.js`, `/auth.css`, `/dashboard.js`, `/camera-audio.js` | ressources de l'interface |
| GET/HEAD | `/cast-media/<session>/...` | média temporaire Cast |

Les URLs `/cast-media/...` utilisent un identifiant de session aléatoire et doivent rester sur le LAN.

`/` et `/live` exigent une session navigateur et redirigent sinon vers `/login`.
Les routes de session utilisent des formulaires `application/x-www-form-urlencoded`.
Les opérations suivantes exigent une session et le header `X-CSRF-Token` :

| Méthode | Route | Fonction |
|---|---|---|
| POST | `/api/auth/logout` | déconnexion |
| POST | `/api/auth/password` | `currentPassword`, `password` |
| GET/POST | `/api/auth/users` | liste/création ; administrateur |
| POST/DELETE | `/api/auth/users/{username}` | modification/suppression ; administrateur |

Création/modification : `username` (création), `role=admin\|user`, `password`
(vide pour le conserver lors d'une modification) et `cameras` (IDs séparés par
des virgules). Un administrateur a accès à toutes les caméras ; un utilisateur
ne reçoit aucun droit implicite. Une modification du compte invalide ses sessions.

Pour les sessions navigateur, `/api/cameras` est filtré ; les routes directes,
les contrôles et le Cast refusent une caméra non autorisée avec 403. La
configuration des caméras/MQTT et `/api/http-action` sont réservées aux
administrateurs. Les commandes GET PTZ/lumière/image exigent également le jeton CSRF.

## Caméras

| Méthode | Route |
|---|---|
| GET | `/api/cameras` |
| GET | `/api/cameras/{id}/status` |
| GET | `/api/cameras/{id}/snapshot` |
| GET | `/api/cameras/{id}/live.mjpeg` |
| GET | `/api/cameras/{id}/live.mp4` |
| GET | `/api/cameras/{id}/audio.mp3` |
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

`audio.mp3` transmet le microphone seul (`audio/mpeg`, sans cache), converti
depuis le RTSP local par FFmpeg. Les permissions caméra sont contrôlées avant
l'ouverture puis pendant le flux. Une absence de son ou de FFmpeg renvoie 503.

## Boutons et informations enregistrés

Ces routes exigent un compte connecté ; les mutations exigent le jeton CSRF.
Le jeton API d'intégration ne remplace pas le compte pour ces routes.

| Méthode | Route | Fonction |
|---|---|---|
| GET | `/api/dashboard?owner={username}` | éléments communs et personnels, disposition ; autre compte réservé aux administrateurs |
| POST | `/api/dashboard` | créer/modifier un bouton ou une info |
| DELETE | `/api/dashboard/{id}` | supprimer ; `revision` requise |
| GET | `/api/dashboard/recipients` | noms des destinataires possibles |
| POST | `/api/dashboard/{id}/transfer` | `target`, `mode=copy\|move`, `revision` |
| POST | `/api/dashboard/{id}/execute` | exécuter un bouton autorisé selon sa définition enregistrée |
| POST | `/api/dashboard/layout` | enregistrer les préférences du compte courant |

Une définition contient `kind=action\|info`, `scope=common\|personal`, `owner`
(personnel), `name` et `enabled=true\|false`. Une modification fournit `id` et
`revision` ; une révision dépassée renvoie 409. Les éléments communs sont
modifiables seulement par les administrateurs ; les autres comptes voient leur
propre tableau. Un administrateur peut aussi gérer le tableau d'un autre compte.

Bouton : `url` HTTP/HTTPS, `method=GET\|POST\|PUT\|PATCH\|DELETE`.
Info : `serverId`, `topic` exact, `valueType=number\|text\|boolean`, `unit`,
`decimals=auto\|0\|1\|2\|3`, `staleSeconds`, `jsonPath`, `measureType` facultatif.
`importId` permet l'import idempotent des anciennes définitions du navigateur.

La disposition accepte `selected`, `order`, `sizes`, `controlOrder` (JSON),
`hidden` (IDs séparés par des virgules), `wallOnly=0\|1`, `audioVolume` (0–100)
et `audioMuted=true\|false` ; ces préférences restent propres au compte.

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
| GET | `/api/mqtt/sources` |
| POST | `/api/mqtt/servers/save` |
| DELETE | `/api/mqtt/servers/{id}` |
| GET | `/api/mqtt/value?server=<id>&topic=<topic>` |

`/api/mqtt/value` crée/maintient l’abonnement nécessaire au topic demandé puis retourne la dernière valeur connue et son horodatage.

Les wildcards `#` et `+` ne sont pas acceptés pour les bulles du dashboard.

`/api/mqtt/sources` fournit aux utilisateurs les IDs et noms des brokers actifs
pour créer leurs infos, sans les paramètres ni les identifiants de connexion.

## Actions HTTP

```text
POST /api/http-action
```

Cette route reste disponible pour les administrateurs et les intégrations avec
le jeton API. Les boutons enregistrés utilisent `/api/dashboard/{id}/execute`.

C’est volontairement puissant : ne pas exposer cette API sur Internet et éviter les URLs non maîtrisées.

## Codes courants

| Code | Signification |
|---:|---|
| 200 | succès |
| 400 | requête invalide |
| 401 | token absent ou incorrect |
| 403 | droits insuffisants ou CSRF invalide |
| 404 | caméra / route / ressource inconnue |
| 405 | méthode non autorisée |
| 409 | élément modifié depuis sa lecture |
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
