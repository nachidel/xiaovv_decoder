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
mqtt.properties.bak
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
*.bak
xiaovv-env.bat
xiaovv-env.ps1
xiaovv-env.sh
*.log
*.pcap
```

Adapter si un `application.properties` sans secret doit volontairement rester versionné.

Une exclusion Git ne retire pas un fichier déjà suivi. Pour conserver une
configuration localement tout en cessant de la versionner :

```bash
git rm --cached -- src/main/resources/mqtt.properties
```

Vérifier aussi les sauvegardes `.bak`. Les configurations locales et leurs
sauvegardes sont exclues dans le `.gitignore` du projet ; les modèles sans
secrets restent dans `distribution/*.properties.example`.

Un secret déjà présent dans un commit reste dans l'historique après cette
suppression. S'il a été partagé, révoquer ou changer le secret auprès du
service concerné et mettre à jour ses consommateurs. Une réécriture de
l'historique doit être coordonnée avec les personnes qui utilisent le dépôt ;
elle ne remplace pas la révocation d'un secret exposé.

## API HTTP

Les intégrations API utilisent un token donnant l'accès complet. Les navigateurs
utilisent un compte avec rôle et droits par caméra. Par défaut, le transport est HTTP et le token n'est
pas chiffré sur le réseau.

Une écoute HTTPS native peut être activée sur un port séparé avec un
certificat externe. Voir [HTTPS](HTTPS.md) pour la configuration, l'accès
Internet et le renouvellement du certificat. L'écoute HTTP reste disponible
pour le LAN et Google Cast.

Recommandations :

- LAN de confiance ;
- VPN pour accès distant ;
- pas de redirection Internet des ports HTTP et RTSP ;
- pour l'accès distant, HTTPS natif avec un certificat public valide ou un
  reverse proxy HTTPS correctement configuré.

## RTSP

Le RTSP actuel :

- n’est pas chiffré ;
- demande les identifiants d'un compte Xiaovv aux clients distants et applique ses droits par caméra ;
- utilise TCP.

L'authentification RTSP Basic n'apporte aucun chiffrement au transport ; elle
est réservée au LAN de confiance ou à un VPN. Ne pas exposer le port RTSP sur
Internet. Les lectures depuis la boucle locale (FFmpeg/Cast) sont autorisées
sans identifiants ; les processus du serveur doivent donc rester de confiance.

## Boutons HTTP

`/api/http-action` permet au serveur Xiaovv de faire une requête HTTP vers une
URL fournie par un administrateur ou une intégration disposant du jeton API.
Les boutons enregistrés passent par `/api/dashboard/{id}/execute` : le serveur
vérifie leur visibilité et utilise la définition stockée, sans accepter une URL
de remplacement dans la requête. L'URL d'un bouton commun est masquée aux
utilisateurs ordinaires.

Un utilisateur peut créer ses propres boutons HTTP ; ses requêtes sont donc
exécutées depuis le serveur. Donner un compte utilisateur suppose de lui faire
confiance pour utiliser cette fonction et les topics MQTT disponibles. Copier
ou déplacer un bouton transmet sa définition complète au destinataire, y
compris son éventuelle URL sensible.

Cette fonction peut être assimilée à une primitive de requête serveur générique.

Recommandations :

- API non exposée ;
- URLs uniquement vers des services LAN connus ;
- éviter les URLs contenant une clé/secrète ;
- limiter les comptes aux personnes autorisées à utiliser les services du serveur.

## Google Cast

Les receivers Google Cast ne peuvent pas envoyer le header `X-API-Token`.

Les fichiers média sont donc servis temporairement sous :

```text
/cast-media/<identifiant-aléatoire>/...
```

L’identifiant agit comme une URL temporaire difficile à deviner, mais ce n’est pas un remplacement d’une vraie couche HTTPS/authentification.

## Navigateur

La session est dans un cookie HttpOnly, SameSite=Strict et Secure sur HTTPS.
Les requêtes qui modifient l'état exigent un jeton CSRF et une origine autorisée.
Le jeton API précédemment enregistré dans `localStorage` est effacé après la
première connexion. Ne pas le donner à un utilisateur : il conserve l'accès complet
aux intégrations.

Les mots de passe des comptes sont hachés avec PBKDF2-HMAC-SHA256 (600 000
itérations et sel aléatoire par compte). Le fichier `users.properties` est écrit
atomiquement avec des permissions 600 sous Linux. Ne pas le versionner ni le
supprimer pour contourner un compte ; le sauvegarder avec les autres données.
Une modification de compte révoque ses sessions Web et RTSP ; un changement
de mot de passe personnel renouvelle seulement la session Web courante.

Les boutons HTTP, infos et dispositions sont enregistrés sur le serveur dans
`dashboard.properties`, à côté des comptes, avec écriture atomique et
permissions 600 sous Linux. Ce fichier peut contenir des URLs sensibles : ne
pas le versionner et le sauvegarder avec `users.properties`. Les anciennes
définitions du navigateur sont conservées après leur import.

L'écoute du microphone applique les mêmes droits caméra que la vidéo. Le flux
MP3 passe par la connexion Web authentifiée et chiffrée lorsqu'elle utilise
HTTPS ; son processus FFmpeg est arrêté lors de la fermeture du flux ou de la
révocation des droits.

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
