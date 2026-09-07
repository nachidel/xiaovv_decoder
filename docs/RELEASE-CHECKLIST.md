# Checklist avant release

Cette checklist sert notamment à éviter qu’une nouvelle fonctionnalité écrase une fonction déjà présente dans une ancienne branche ou un ancien ZIP.

## 1. Compilation

Windows :

```powershell
.\gradlew.bat clean build
```

Linux :

```bash
./gradlew clean build
```

## 2. Démarrage

Vérifier dans les logs :

- RTSP démarré ;
- API démarrée ;
- nombre de caméras ;
- découverte Cast démarrée ;
- MQTT sans erreur de configuration.

## 3. Caméras

Tester au moins :

- une caméra disponible ;
- une caméra indisponible ;
- `GET /api/cameras` ;
- RTSP ;
- audio RTSP ;
- PTZ ;
- lumière ;
- image.

## 4. Mur Web

Tester :

- ouverture du dashboard ;
- token ;
- caméra MJPEG ;
- resize ;
- ordre ;
- snapshot ;
- masque/affichage interface.

## 5. Configuration caméra

Tester :

- modification d’un champ non secret ;
- ajout ;
- désactivation ;
- suppression ;
- hot reload ;
- absence de mot de passe dans les réponses JSON.

## 6. MQTT

Tester :

- bouton `MQTT` présent ;
- broker visible ;
- secret via variable d’environnement ;
- connexion ;
- création d’une bulle ;
- réception d’une valeur ;
- masquer ;
- restaurer ;
- supprimer définitivement.

Vérifier explicitement qu’une release Cast n’a pas réintroduit un `CameraApiServer.kt` plus ancien sans MQTT.

## 7. Cast

Tester au moins deux types d’appareils si possible :

- découverte ;
- démarrage ;
- image ;
- fonctionnement > 1 minute ;
- arrêt depuis l’icône ;
- arrêt propre de FFmpeg ;
- pas de boucle de reload anormale.

## 8. Secrets

Avant packaging :

```text
application.properties réel : absent
mqtt.properties réel        : absent
xiaovv-env.* réel            : absent
token réel                   : absent
passwords                    : absents
pcap/logs sensibles          : absents
```

## 9. Distribution

Le package doit contenir au minimum :

```text
application.properties.example
mqtt.properties.example
xiaovv-env.example.bat
xiaovv-env.example.ps1
xiaovv-env.example.sh
README.md
```

Les lanceurs doivent respecter :

```text
XIAOVV_CONFIG
XIAOVV_MQTT_CONFIG
XIAOVV_API_TOKEN
XIAOVV_CAMERA_<ID>_PASSWORD
XIAOVV_MQTT_<ID>_PASSWORD
XIAOVV_FFMPEG
```

## 10. Documentation

Vérifier que les limites mentionnées correspondent encore au code :

- audio RTSP : implémenté ;
- snapshots : implémentés ;
- config à chaud : implémentée ;
- MQTT : implémenté ;
- Cast : implémenté, vidéo seule actuellement ;
- MediaMTX : optionnel.
