# Interface Web Xiaovv

L’interface est servie par :

```text
http://IP_DU_SERVEUR:8080/
```

et également :

```text
http://IP_DU_SERVEUR:8080/live
```

## Authentification

Le navigateur mémorise le token API dans :

```text
localStorage["xiaovvApiToken"]
```

Le token n’est pas injecté en clair dans le HTML généré par le serveur.

## Mur vidéo

Le mur principal utilise le flux MJPEG produit localement par FFmpeg à partir du RTSP Xiaovv.

Conséquences :

- le navigateur n’a pas besoin de décoder directement le H.265 ;
- l’ouverture d’une caméra dans le mur crée une demande RTSP locale ;
- masquer/fermer le flux libère cette demande ;
- FFmpeg est nécessaire pour cette fonction.

Le dashboard mémorise notamment :

- caméras affichées ;
- ordre ;
- dimensions ;
- état d’affichage de l’interface.

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

Les définitions sont conservées dans le navigateur.

La requête est exécutée côté serveur via `/api/http-action`, ce qui évite les limitations CORS du navigateur.

### Attention

Une URL sensible peut donc être stockée dans le `localStorage` du navigateur. Préférer des endpoints locaux sans secret dans l’URL.

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

Les bulles sont stockées dans le navigateur.

### Masquer n’est pas supprimer

Une information masquée reste configurée.

Lors d’un nouvel ajout, les anciennes informations masquées sont proposées pour :

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
| disposition mur | navigateur |
| boutons HTTP | navigateur |
| bulles MQTT | navigateur |
| session Cast | serveur |
