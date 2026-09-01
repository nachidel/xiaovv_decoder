# Xiaovv - package Linux autonome

La tâche Gradle :

```bash
./gradlew clean packageLinux
```

doit être exécutée **sous Linux** avec un **JDK 21 contenant `jpackage`**.

Le package produit est natif pour l'architecture de la machine de build :

- Linux x86_64 -> `xiaovv-1.0.0-linux-x64.tar.gz`
- Linux ARM64/aarch64 -> `xiaovv-1.0.0-linux-arm64.tar.gz`

## Installation du package généré

```bash
tar -xzf xiaovv-1.0.0-linux-arm64.tar.gz
cd xiaovv

cp application.properties.example application.properties
cp xiaovv-env.example.sh xiaovv-env.sh

nano application.properties
nano xiaovv-env.sh

chmod +x start.sh
./start.sh
```

Java est embarqué dans le package final : aucune installation Java n'est nécessaire sur la machine qui l'exécute.

Le JDK 21 + jpackage est nécessaire uniquement sur la machine qui construit le package.
