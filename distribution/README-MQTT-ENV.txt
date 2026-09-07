Xiaovv - MQTT : mot de passe hors du projet / package
=======================================================

Cette distribution est compatible avec le dernier changement MQTT.

1. Le fichier mqtt.properties reste à la racine de la distribution.
   Les scripts start.* définissent automatiquement :

       XIAOVV_MQTT_CONFIG=<racine>/mqtt.properties

   si cette variable n'est pas déjà fournie.

2. Le mot de passe MQTT reste hors de mqtt.properties.

   Pour un broker dont l'ID est "jeedom", la variable automatique est :

       XIAOVV_MQTT_JEEDOM_PASSWORD

   Pour l'exemple fourni :

       XIAOVV_MQTT_EXAMPLE_PASSWORD

3. Il est également possible de choisir un nom personnalisé dans
   mqtt.properties :

       mqtt.jeedom.password-env=XIAOVV_MQTT_PASSWORD

4. Les fichiers xiaovv-env.example.* montrent comment fournir le secret
   à la distribution sans l'embarquer dans le projet ou dans le package.

5. Aucun mot de passe MQTT réel n'est contenu dans cette archive.
