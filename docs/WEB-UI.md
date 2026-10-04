# Interface Web Xiaovv

L’interface est servie par :

```text
http://IP_DU_SERVEUR:8080/
```

et également :

```text
http://IP_DU_SERVEUR:8080/live
```

Une écoute HTTPS peut être activée, par exemple sur
`https://www.nachidel.ovh:8443/`. Voir [HTTPS](HTTPS.md).

## Authentification

L'ouverture du dashboard affiche une page de connexion avec la favicon Xiaovv.
Lors du premier accès, choisir le nom et le mot de passe de l'administrateur
(12 à 128 caractères) et fournir le jeton API actuel. Le champ du jeton est
prérempli si ce navigateur l'avait déjà enregistré pour cette même adresse.
Ce jeton reste utilisable par les intégrations et n'est pas communiqué aux utilisateurs.

La connexion utilise un cookie HttpOnly, SameSite=Strict et Secure sur HTTPS.
Elle expire après 30 minutes sans activité, au plus tard après 12 heures,
ou au redémarrage du programme. Le bouton **Mon compte** permet de changer
son mot de passe et de se déconnecter.

### Comptes et droits par caméra

Le bouton **Utilisateurs**, visible pour les administrateurs, ouvre une fenêtre
pour créer, modifier ou supprimer les comptes, changer leur rôle et réinitialiser
leur mot de passe. Pour conserver un mot de passe lors d'une modification,
laisser son champ vide. Il faut toujours conserver au moins un administrateur.

- **Administrateur** : toutes les caméras, gestion des comptes et de la configuration.
- **Utilisateur** : uniquement les caméras cochées dans **Caméras autorisées**.
  Un nouveau compte utilisateur n'a aucune caméra autorisée par défaut.

Les droits couvrent le catalogue, les images, la vidéo, les contrôles caméra,
l'interphone et le démarrage/arrêt du Cast. Le serveur contrôle aussi les URL
directes. Les lecteurs RTSP du LAN demandent les identifiants du même compte
et respectent ces droits. Une modification du compte invalide ses sessions.

Les comptes et droits sont enregistrés hors du JAR dans `users.properties`, à
côté du fichier de configuration externe, ou dans `config/users.properties`
avec le bouton Run. Sur le Pi déployé, le chemin par défaut est
`/var/lib/xiaovv/config/users.properties`. Ce fichier doit être sauvegardé et
reste présent après un déploiement. Le chemin peut être changé avec
`XIAOVV_USERS_FILE` ou `-Dxiaovv.users.file=...`.

## Mur vidéo

Le mur principal utilise le flux MJPEG produit localement par FFmpeg à partir du RTSP Xiaovv.

Conséquences :

- le navigateur n’a pas besoin de décoder directement le H.265 ;
- l’ouverture d’une caméra dans le mur crée une demande RTSP locale ;
- masquer/fermer le flux libère cette demande ;
- FFmpeg est nécessaire pour cette fonction.

Le serveur mémorise pour chaque compte :

- caméras affichées ;
- ordre ;
- dimensions ;
- état d’affichage de l’interface.

La disposition et les réglages de son suivent le compte sur ses appareils.

## Son de la caméra active

Cliquer sur l'image d'une caméra sélectionne cette caméra et lance l'écoute de
son microphone. Une seule caméra est audible à la fois ; sa tuile est entourée
en bleu. La même sélection fonctionne au clavier avec Entrée ou Espace.

Une petite barre apparaît en bas à droite de la vidéo sélectionnée : volume
de 0 à 100 %, icône de coupure du son et croix pour arrêter l'écoute. Elle
reste cachée sans sélection et suit la caméra active. Son voyant indique la
connexion, la lecture ou une erreur ; le survol du voyant affiche le détail.
Le volume et la coupure sont enregistrés
pour le compte. L'écoute démarre seulement après une sélection ; masquer la
caméra active ou quitter la page arrête son flux audio.

Le son AAC du RTSP local est converti en MP3 par FFmpeg avec `libmp3lame`, puis
diffusé par HTTP/HTTPS avec les mêmes droits caméra que les images. Le
microphone de la caméra doit fournir un flux audio.

## Contrôles caméra

Chaque tuile peut exposer :

- PTZ ;
- lumière ;
- mode image ;
- snapshot ;
- Cast.

Les contrôles PTZ envoient un `STOP` à la fin de l’interaction.

## Configuration des caméras

Le dashboard possède un éditeur de configuration caméra.

Il peut :

- ajouter ;
- modifier ;
- désactiver ;
- supprimer ;
- recharger le runtime.

Les mots de passe ne sont pas renvoyés en clair à l’interface.

## Boutons HTTP

Des tuiles d’action HTTP peuvent être créées depuis le dashboard.

Configuration typique :

- nom ;
- URL ;
- méthode HTTP ;
- actif / masqué.

Les définitions sont conservées sur le serveur dans `dashboard.properties`,
à côté de `users.properties`. Elles survivent à un déploiement.

La requête est exécutée côté serveur via `/api/dashboard/{id}/execute`, ce qui
évite les limitations CORS du navigateur. Un utilisateur exécute la définition
enregistrée ; l'URL d'un bouton commun n'est pas renvoyée aux utilisateurs.

### Attention

Une URL sensible peut être enregistrée dans ce fichier serveur. Le protéger et
le sauvegarder avec les comptes ; ne pas le versionner.

## Informations MQTT

Les bulles d’information utilisent un broker MQTT configuré côté serveur.

Une bulle contient actuellement :

- nom ;
- serveur MQTT ;
- topic exact ;
- type : numérique, texte ou booléen ;
- type de mesure ;
- unité ;
- nombre de décimales ;
- délai avant valeur périmée ;
- extraction JSON facultative.

Exemples d’extraction JSON :

```text
$.temperature
$.piscine.temperatureSortie
$.capteurs[0].valeur
```

Les bulles sont enregistrées dans le même fichier serveur que les boutons.

## Éléments communs, personnels et transfert

Le bouton **Boutons et infos** ouvre leur gestion :

- **Commun à tous** : tous les comptes peuvent utiliser l'élément ; seuls les
  administrateurs peuvent créer ou modifier ces définitions.
- **Personnel** : le propriétaire et les administrateurs peuvent gérer
  l'élément. Chaque utilisateur peut créer ses boutons et ses infos.
- **Copier / transférer** : choisir un compte destinataire, puis copier pour
  conserver l'original ou déplacer pour changer de propriétaire. Les droits
  caméra du destinataire restent identiques. Un élément commun peut être copié
  vers un compte par un administrateur.

Un administrateur peut aussi sélectionner le tableau personnel d'un autre
compte. Les modifications concurrentes sont refusées si la définition a changé
entre l'ouverture du formulaire et son enregistrement : actualiser la liste.

### Récupérer les anciens éléments

Dans le navigateur où les boutons et infos avaient été créés, cliquer sur
**Les importer sur le serveur**. L'administrateur choisit **Commun à tous** ou
**Mes éléments personnels**. La disposition peut être reprise aussi.
Les anciennes données locales restent conservées et un nouvel import des
mêmes éléments ne crée pas de doublons.

### Masquer n’est pas supprimer

Une information masquée reste configurée.

Dans **Boutons et infos**, les informations masquées sont proposées pour :

- restauration ;
- modification ;
- suppression définitive.

## Zone boutons + informations

Les boutons HTTP et les bulles MQTT utilisent la même zone de contrôle et le même mécanisme de déplacement/redimensionnement.

La zone vidéo reste distincte en dessous.

## Configuration MQTT

Le bouton `MQTT` ouvre la configuration globale des brokers.

Le dashboard permet de gérer :

- ID ;
- nom ;
- host ;
- port ;
- TLS ;
- utilisateur ;
- nom de variable d’environnement du mot de passe ;
- ancien mot de passe local, uniquement pour migration ;
- activation.

Le mot de passe résolu depuis l’environnement n’est jamais renvoyé au navigateur.

## Cast

L’icône Cast se trouve en bas à droite de la vidéo.

Premier clic :

1. ouverture du sélecteur ;
2. liste des appareils découverts ;
3. choix de l’écran ;
4. démarrage du flux.

Clic sur une icône Cast déjà active :

- arrêt du Cast pour cette caméra.

Le Cast continue côté serveur même si la page Web est fermée.

## Portée des données

| Donnée | Stockage |
|---|---|
| configuration caméra | fichier serveur |
| configuration broker MQTT | fichier serveur |
| secrets | variables d’environnement recommandées |
| disposition mur et volume | serveur, par compte |
| boutons HTTP | serveur, communs ou personnels |
| bulles MQTT | serveur, communes ou personnelles |
| session Cast | serveur |
