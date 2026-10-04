# HTTPS pour Xiaovv

Xiaovv peut servir le dashboard, les vidéos et l'API en HTTPS sur un port
séparé, sans reverse proxy. L'accès HTTP existant reste disponible sur le
réseau local pour Google Cast. HTTPS est désactivé par défaut.

Exemple d'adresse publique : `https://www.nachidel.ovh:8443/`.
Jeedom peut continuer à utiliser `https://www.nachidel.ovh/` sur le port 443.

Pour transférer et installer automatiquement le programme sur un Raspberry Pi,
voir la section [Déploiement Raspberry Pi](../README.md#déploiement-raspberry-pi).

## 1. Certificat du domaine

Il faut un certificat valide pour `www.nachidel.ovh`, sa chaîne de certification
et la clé privée correspondante. Le numéro de port ne fait pas partie du nom
du certificat : un certificat utilisé sur 443 convient aussi sur 8443.

Si Jeedom utilise déjà un certificat public pour ce nom et que tu as accès
aux fichiers, commence par retrouver les chemins déclarés dans Apache.
Dans un terminal sur sa machine Linux :

```bash
sudo grep -R -E '^[[:space:]]*SSLCertificate(File|KeyFile|ChainFile)[[:space:]]' /etc/apache2/sites-enabled/
```

Cette commande affiche les chemins, pas le contenu de la clé. Repère le
certificat et la clé du site `www.nachidel.ovh`. Si elle ne retourne rien,
le HTTPS peut être géré par un autre serveur ou un proxy : il faut retrouver
le certificat à cet endroit.

Convertis ensuite ces fichiers en PKCS12 sur la machine Linux :

```bash
openssl pkcs12 -export \
  -in /chemin/vers/fullchain.pem \
  -inkey /chemin/vers/privkey.pem \
  -out xiaovv.p12 \
  -name xiaovv
```

OpenSSL demande de choisir un mot de passe d'export. Conserve-le pour le
lancement de Xiaovv. Les chemins ci-dessus sont à remplacer par ceux du
certificat réel ; ils dépendent de l'installation de Jeedom.

Si Apache déclare un `SSLCertificateChainFile` séparé, ajoute aussi
`-certfile /chemin/vers/chain.pem` à la commande d'export. Avec
`fullchain.pem`, la chaîne est déjà incluse.

Transfère le fichier `xiaovv.p12` sur la machine qui exécute Xiaovv, par exemple
dans `/opt/xiaovv/certs/`. Ce fichier contient une clé privée : garde-le hors
de Git, limite sa lecture au compte qui exécute Xiaovv et ne le partage pas.

Si tu n'as pas accès à ce certificat, il faut en obtenir un autre pour le même
nom de domaine, par exemple avec une validation ACME par DNS chez le fournisseur
DNS. Ouvrir seulement le port 8443 ne suffit pas pour une validation ACME HTTP
classique, qui passe par le port 80.

Un certificat autosigné sert aux tests, mais ne donne pas un accès public
reconnu sans avertissement par les navigateurs.

## 2. Activer HTTPS

Ajoute ces propriétés à ton `application.properties` externe :

```properties
api.https.enabled=true
api.https.bind-address=0.0.0.0
api.https.port=8443
api.https.key-store=/opt/xiaovv/certs/xiaovv.p12
api.https.key-store-type=PKCS12
api.https.key-store-password-env=XIAOVV_HTTPS_KEY_STORE_PASSWORD
```

Sous Windows, utilise par exemple `D:/xiaovv/certs/xiaovv.p12`.

### Démarrer avec Run dans IntelliJ sur Windows

Le HTTPS est chargé par le programme lui-même. Le bouton **Run** suffit,
sans lanceur BAT. Dans la configuration utilisée par IntelliJ, active les
propriétés ci-dessus et ajoute :

```properties
api.https.key-store-password-file=D:/xiaovv/certs/xiaovv-password.dpapi
```

Au premier lancement, une fenêtre demande le mot de passe d'export du
certificat Jeedom. Après vérification du certificat et du mot de passe, le
programme enregistre ce dernier dans le fichier `.dpapi`, chiffré par Windows
pour le compte courant. Aux lancements suivants, il le relit automatiquement.
Un mot de passe incorrect ou une annulation ne crée pas ce fichier.

Avec Run sans configuration externe, les paramètres se trouvent dans
`src/main/resources/application.properties`. Les variables existantes du token
API et des caméras dans IntelliJ restent nécessaires. Arrête l'ancienne instance
avant Run pour libérer les ports 8080, 8443 et 8555.

Le fichier chiffré fonctionne avec le même compte Windows sur ce PC. Pour
changer de compte ou de mot de passe, supprime uniquement le fichier `.dpapi`
et relance le programme pour le saisir à nouveau. Le mot de passe n'est pas
enregistré en clair dans les propriétés, les arguments Java ou les logs.
Cette option utilise le PowerShell intégré à Windows, lancé sans fenêtre.

### Autres modes de lancement

Pour lancer directement depuis ce projet Windows, copie le certificat dans
`config/certs/xiaovv.p12`, puis ouvre `start-https.bat` a la racine du projet.
Ce lanceur utilise `config/application.properties`, le JAR compile dans
`build/libs/xiaovv.jar` et les secrets du fichier local `config/xiaovv-env.bat`
ou `config/xiaovv-env.ps1`. Il demande le mot de passe d'export dans une saisie
masquee et active HTTPS sur 8443. Arrete l'instance Xiaovv precedente avant
de le lancer ; ses ports HTTP et RTSP restent utilises par la nouvelle version.

Le certificat est charge a chaque lancement : son mot de passe n'est pas
enregistre par ce lanceur. Le MQTT existant de `src/main/resources/mqtt.properties`
reste utilise si aucun fichier MQTT externe n'est defini.

Un chemin relatif est résolu à côté du fichier désigné par `XIAOVV_CONFIG`
ou `-Dxiaovv.config`. Sans configuration externe, il est relatif au répertoire
de lancement. Un chemin absolu évite les différences entre les lanceurs
portables et les packages natifs.

La variable d'environnement du mot de passe reste prioritaire sur le fichier
chiffré Windows. Sans cette option Windows, elle est obligatoire.
Tu peux la définir dans ton fichier local `xiaovv-env.sh` ou dans
l'environnement de ton service, comme les autres secrets de Xiaovv.

Pour un lancement interactif Linux, sans écrire le mot de passe dans
l'historique du terminal :

```bash
read -r -s -p 'Mot de passe du certificat : ' XIAOVV_HTTPS_KEY_STORE_PASSWORD
export XIAOVV_HTTPS_KEY_STORE_PASSWORD
printf '\n'
./start.sh
```

Sur ce PC Windows, dans PowerShell :

```powershell
$certificatePassword = Read-Host 'Mot de passe du certificat' -AsSecureString
$env:XIAOVV_HTTPS_KEY_STORE_PASSWORD = [System.Net.NetworkCredential]::new('', $certificatePassword).Password
.\start.ps1
```

Redémarre Xiaovv après une modification des paramètres HTTPS. Le programme
accepte TLS 1.2 et TLS 1.3. Si le certificat manque, est expiré ou ne peut pas
être ouvert avec le mot de passe fourni, le démarrage échoue avec un message
d'erreur au lieu de continuer avec une configuration partielle.

### Variables disponibles

| Propriété | Variable d'environnement | Défaut |
|---|---|---|
| `api.https.enabled` | `XIAOVV_HTTPS_ENABLED` | `false` |
| `api.https.bind-address` | `XIAOVV_HTTPS_BIND` | adresse HTTP existante |
| `api.https.port` | `XIAOVV_HTTPS_PORT` | `8443` |
| `api.https.key-store` | `XIAOVV_HTTPS_KEY_STORE` | obligatoire si activé |
| `api.https.key-store-type` | `XIAOVV_HTTPS_KEY_STORE_TYPE` | `PKCS12` ; `JKS` aussi accepté |
| `api.https.key-store-password-file` | `XIAOVV_HTTPS_PASSWORD_FILE` | absent ; stockage DPAPI facultatif sous Windows |

Les variables ont priorité sur les propriétés. La propriété
`api.https.key-store-password-env` indique le nom de la variable du secret ;
son défaut est `XIAOVV_HTTPS_KEY_STORE_PASSWORD`.

## 3. Accès Internet

Le domaine doit pointer vers l'adresse publique de ton réseau. Sur la box,
redirige **8443/TCP** vers **8443/TCP** de la machine Xiaovv. Autorise aussi ce
port dans le pare-feu de cette machine. Les redirections existantes vers
Jeedom sur 80 et 443 restent telles quelles.

Le port HTTP `8080` et le RTSP `8555` restent locaux ; ne les redirige pas vers
Internet. Si tu utilises Google Cast, garde l'API HTTP accessible aux appareils
du LAN. Sinon, tu peux limiter `api.bind-address` à `127.0.0.1`.

Le même token API protège les commandes et les vidéos en HTTPS. Utilise un
token long et aléatoire dans `XIAOVV_API_TOKEN`.

## 4. Vérifier

Depuis un téléphone en données mobiles, ouvre :

```text
https://www.nachidel.ovh:8443/
```

Vérifie que le certificat est accepté sans avertissement, puis saisis ton
token API. Le navigateur possède un stockage distinct pour cette nouvelle
adresse : le token, la disposition, les boutons et les bulles enregistrés à
l'ancienne adresse HTTP ne sont pas transférés automatiquement.

Teste une caméra, une commande et le microphone de l'interphone. Les requêtes
du dashboard utilisent des chemins relatifs et suivent automatiquement HTTPS.

Un test depuis le LAN avec le domaine public peut échouer si la box ne gère
pas le retour vers son adresse publique ; le test en données mobiles permet
de distinguer ce cas d'un problème de redirection.

## 5. Renouvellement

Xiaovv charge le certificat au démarrage et n'effectue pas de renouvellement
ACME lui-même. Quand le certificat source est renouvelé, recrée `xiaovv.p12`,
remplace le fichier de Xiaovv et redémarre le programme. Le renouvellement
automatique du certificat de Jeedom ne met pas à jour cette copie PKCS12.
En conservant le même mot de passe d'export, le fichier chiffré Windows reste
utilisable. Si ce mot de passe change, supprime le fichier `.dpapi` avant de
relancer Xiaovv.

Références : [HTTPS Java 21](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpsServer.html),
[export PKCS12 OpenSSL](https://docs.openssl.org/3.5/man1/openssl-pkcs12/).
Les chemins du certificat et de la clé sont déclarés par les directives
[SSL Apache](https://httpd.apache.org/docs/2.4/mod/mod_ssl.html#sslcertificatefile).
Le stockage Windows utilise [DPAPI avec CurrentUser](https://learn.microsoft.com/en-us/dotnet/api/system.security.cryptography.dataprotectionscope?view=windowsdesktop-9.0).
