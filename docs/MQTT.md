# MQTT dans Xiaovv

Le module MQTT sert actuellement au dashboard d’informations.

Il ne remplace pas un broker : Xiaovv se connecte à un ou plusieurs brokers existants.

## Fonctionnement

```text
Broker MQTT
   ↓ MQTT 3.1.1
MqttRuntimeManager
   ↓ cache dernière valeur
/api/mqtt/value
   ↓
Bulle du dashboard
```

Le client MQTT intégré gère uniquement ce qui est nécessaire :

- CONNECT ;
- SUBSCRIBE ;
- réception PUBLISH ;
- keepalive ;
- reconnexion ;
- TCP ou TLS.

## Configuration

Fichier :

```text
mqtt.properties
```

Exemple :

```properties
mqtt.ids=jeedom

mqtt.jeedom.name=MQTT Jeedom
mqtt.jeedom.host=192.168.1.10
mqtt.jeedom.port=1883
mqtt.jeedom.tls=false
mqtt.jeedom.enabled=true
mqtt.jeedom.username=dashboard
mqtt.jeedom.password-env=XIAOVV_MQTT_JEEDOM_PASSWORD
```

## Plusieurs brokers

```properties
mqtt.ids=jeedom,domotique

mqtt.jeedom.host=192.168.1.10
mqtt.jeedom.port=1883
mqtt.jeedom.enabled=true

mqtt.domotique.host=192.168.1.20
mqtt.domotique.port=8883
mqtt.domotique.tls=true
mqtt.domotique.enabled=true
```

## Mot de passe hors du projet

Pour l’ID :

```text
jeedom
```

la variable automatique est :

```text
XIAOVV_MQTT_JEEDOM_PASSWORD
```

On peut l’utiliser sans écrire de `password-env`.

Ou choisir explicitement :

```properties
mqtt.jeedom.password-env=MON_SECRET_MQTT
```

Priorité :

1. variable indiquée par `password-env` ;
2. `XIAOVV_MQTT_<ID>_PASSWORD` ;
3. `mqtt.<id>.password` en clair.

Le dernier mode est déconseillé.

## Emplacement du fichier

Priorité :

```text
-Dxiaovv.mqtt.config
XIAOVV_MQTT_CONFIG
mqtt.properties à côté de XIAOVV_CONFIG
src/main/resources/mqtt.properties
./mqtt.properties
```

## Interface Web

Le bouton `MQTT` permet d’ajouter/modifier/supprimer les brokers.

L’interface affiche notamment :

- broker actif ou désactivé ;
- état connecté/déconnecté ;
- variable d’environnement attendue ;
- présence éventuelle d’un ancien secret dans `mqtt.properties`.

Une option permet de supprimer le mot de passe encore stocké dans le fichier après migration vers une variable d’environnement.

## Bulles d’information

Une bulle utilise :

```text
serverId + topic
```

Le topic doit être exact.

Accepté :

```text
maison/piscine/temperature
```

Refusé :

```text
maison/+/temperature
maison/#
```

Cette contrainte est volontaire pour garder le client simple et éviter qu’une bulle s’abonne à un ensemble de messages inattendu.

## Types

### Numérique

Affichage formaté avec :

- unité ;
- 0 à 3 décimales ;
- mode `auto`.

### Texte

Payload affiché comme chaîne.

### Booléen

Interprétation selon les valeurs prises en charge par l’interface.

## JSON

Si le payload est :

```json
{
  "temperatureSortie": 27.4,
  "pompe": true
}
```

on peut configurer :

```text
$.temperatureSortie
```

ou :

```text
$.pompe
```

Le parseur de chemin JSON est volontairement simple.

## Valeur périmée

Le champ :

```text
Périmée après (s)
```

permet d’indiquer qu’une donnée trop ancienne ne doit plus être présentée comme fraîche.

`0` désactive la péremption.

## API

Liste des brokers :

```text
GET /api/mqtt/servers
```

Dernière valeur :

```text
GET /api/mqtt/value?server=jeedom&topic=maison/piscine/temperature
```

Sauvegarde :

```text
POST /api/mqtt/servers/save
```

Suppression :

```text
DELETE /api/mqtt/servers/jeedom
```

Toutes ces routes sont protégées par le token API.

## Dépannage

### Broker déconnecté

Vérifier :

- IP/hostname ;
- port ;
- TLS ;
- utilisateur ;
- variable de mot de passe ;
- firewall ;
- ACL MQTT.

### Mot de passe non détecté

Pour `jeedom`, vérifier dans l’environnement du **processus Xiaovv** :

```text
XIAOVV_MQTT_JEEDOM_PASSWORD
```

Dans IntelliJ :

```text
Run → Edit Configurations → Environment variables
```

### La bulle reste vide

Vérifier :

1. broker connecté ;
2. topic exact ;
3. le broker a déjà publié une valeur ;
4. ACL de souscription ;
5. extraction JSON ;
6. délai de péremption.
