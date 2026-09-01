# Packaging portable Xiaovv

## Mise en place

Copier à la racine du projet :

- `build.gradle.kts`
- le dossier `distribution/`

Conserver également le `README.md` principal à la racine.

## Construire

Windows / PowerShell :

```powershell
.\gradlew.bat clean packageRelease
```

Linux :

```bash
./gradlew clean packageRelease
```

## Résultat

```text
build/
└── release/
    ├── xiaovv/
    │   ├── xiaovv.jar
    │   ├── README.md
    │   ├── application.properties.example
    │   ├── start.bat
    │   ├── start.ps1
    │   ├── start.sh
    │   ├── xiaovv-env.example.bat
    │   ├── xiaovv-env.example.ps1
    │   └── xiaovv-env.example.sh
    │
    └── xiaovv-1.0.0-portable.zip
```

## Installation Windows

1. Extraire le ZIP.
2. Copier `application.properties.example` en `application.properties`.
3. Configurer les caméras.
4. Copier `xiaovv-env.example.ps1` en `xiaovv-env.ps1`.
5. Mettre les vrais secrets dans `xiaovv-env.ps1`.
6. Lancer :

```powershell
.\start.ps1
```

Ou utiliser `start.bat` avec `xiaovv-env.bat`.

## Installation Linux

1. Extraire le ZIP.
2. Copier :

```bash
cp application.properties.example application.properties
cp xiaovv-env.example.sh xiaovv-env.sh
```

3. Configurer les caméras et les secrets.
4. Lancer :

```bash
chmod +x start.sh
./start.sh
```

Si les droits exécutables n'ont pas été conservés par le ZIP, `chmod +x start.sh` suffit.

## Prérequis

Cette distribution portable nécessite Java 21 (ou une version compatible avec le bytecode généré).

La prochaine étape peut être une distribution autonome avec runtime Java inclus.
