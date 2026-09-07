import org.gradle.api.GradleException
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

plugins {
    kotlin("jvm") version "2.4.0"
    application
}

group = "fr.nachidel.xiaovv"
version = "1.0.0"

application {
    mainClass.set("fr.nachidel.xiaovv.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.18")
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("de.sfuhrm:chromecast-java-api-v2:0.12.20")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

/*
 * ============================================================================
 * FAT JAR
 * ============================================================================
 */

val fatJar =
    tasks.register<Jar>("fatJar") {

        group = "build"
        description = "Construit le JAR autonome Xiaovv"

        archiveFileName.set("xiaovv.jar")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        dependsOn(tasks.classes)

        manifest {
            attributes(
                mapOf(
                    "Main-Class" to "fr.nachidel.xiaovv.MainKt",
                    "Implementation-Title" to "Xiaovv V380 Server",
                    "Implementation-Version" to project.version.toString()
                )
            )
        }

        from(sourceSets.main.get().output)

        /*
         * Configuration réelle volontairement externe.
         */
        exclude("application.properties")

        /*
         * Dépendances runtime intégrées.
         */
        from(
            {
                configurations.runtimeClasspath
                    .get()
                    .map { file ->
                        if (file.isDirectory) {
                            file
                        } else {
                            zipTree(file)
                        }
                    }
            }
        )

        /*
         * Signatures devenues invalides après fusion.
         */
        exclude(
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA"
        )
    }

tasks.named("build") {
    dependsOn(fatJar)
}

/*
 * ============================================================================
 * PACKAGE PORTABLE JVM
 * ============================================================================
 */

val prepareRelease =
    tasks.register<Sync>("prepareRelease") {

        group = "distribution"
        description = "Prépare le dossier portable Xiaovv"

        dependsOn(fatJar)

        into(
            layout.buildDirectory.dir(
                "release/xiaovv"
            )
        )

        from(
            fatJar.flatMap {
                it.archiveFile
            }
        )

        from(
            layout.projectDirectory.dir(
                "distribution"
            )
        ) {
            /*
             * Les scripts des packages natifs ne doivent pas entrer
             * dans le portable JVM.
             */
            exclude(
                "windows/**",
                "linux/**"
            )
        }

        from(
            layout.projectDirectory.file(
                "README.md"
            )
        )
    }

val packageRelease =
    tasks.register<Zip>("packageRelease") {

        group = "distribution"
        description = "Construit le ZIP portable Xiaovv"

        dependsOn(prepareRelease)

        archiveFileName.set(
            "xiaovv-${project.version}-portable.zip"
        )

        destinationDirectory.set(
            layout.buildDirectory.dir(
                "release"
            )
        )

        from(
            layout.buildDirectory.dir(
                "release/xiaovv"
            )
        ) {
            into("xiaovv")
        }

        doLast {
            println()
            println("============================================================")
            println(" Package portable Xiaovv cree")
            println("============================================================")
            println(
                "ZIP : " +
                        archiveFile.get().asFile.absolutePath
            )
            println("============================================================")
            println()
        }
    }

/*
 * ============================================================================
 * WINDOWS AUTONOME - JAVA EMBARQUE
 * ============================================================================
 */

val windowsArch =
    when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> System.getProperty("os.arch").lowercase()
    }

val windowsInputDir =
    layout.buildDirectory.dir(
        "jpackage/windows-input"
    )

val windowsImageDir =
    layout.buildDirectory.dir(
        "jpackage/windows-image"
    )

val windowsPackageDir =
    layout.buildDirectory.dir(
        "release/windows-package/xiaovv"
    )

val cleanWindowsPackaging =
    tasks.register<Delete>("cleanWindowsPackaging") {

        group = "distribution"

        delete(
            windowsInputDir,
            windowsImageDir,
            layout.buildDirectory.dir(
                "release/windows-package"
            )
        )
    }

val prepareWindowsInput =
    tasks.register<Sync>("prepareWindowsInput") {

        group = "distribution"
        description = "Prépare les fichiers d'entrée Windows pour jpackage"

        dependsOn(
            cleanWindowsPackaging,
            fatJar
        )

        into(windowsInputDir)

        from(
            fatJar.flatMap {
                it.archiveFile
            }
        )
    }

val packageWindowsImage =
    tasks.register<Exec>("packageWindowsImage") {

        group = "distribution"
        description = "Construit l'image Windows autonome avec jpackage"

        dependsOn(prepareWindowsInput)

        doFirst {

            val osName =
                System.getProperty("os.name")
                    .lowercase()

            if (!osName.contains("windows")) {
                throw GradleException(
                    "packageWindows doit etre execute sous Windows."
                )
            }

            commandLine(
                "jpackage",
                "--type", "app-image",
                "--name", "Xiaovv",
                "--input", windowsInputDir.get().asFile.absolutePath,
                "--main-jar", "xiaovv.jar",
                "--main-class", "fr.nachidel.xiaovv.MainKt",
                "--dest", windowsImageDir.get().asFile.absolutePath,
                "--app-version", project.version.toString(),
                "--vendor", "Nachidel",
                "--description", "Xiaovv V380 multi-camera RTSP server",
                "--win-console",

                "--java-options", "-Dfile.encoding=UTF-8",
                "--java-options", "-Dsun.stdout.encoding=UTF-8",
                "--java-options", "-Dsun.stderr.encoding=UTF-8",

                "--java-options",
                "-Dxiaovv.config=\$APPDIR/application.properties"
            )
        }
    }

val prepareWindowsPackage =
    tasks.register<Sync>("prepareWindowsPackage") {

        group = "distribution"
        description = "Prépare le dossier Windows autonome final"

        dependsOn(packageWindowsImage)

        into(windowsPackageDir)

        from(
            windowsImageDir.map {
                it.dir("Xiaovv")
            }
        )

        from(
            layout.projectDirectory.dir(
                "distribution/windows"
            )
        )

        from(
            layout.projectDirectory.file(
                "distribution/application.properties.example"
            )
        )

        from(
            layout.projectDirectory.file(
                "distribution/xiaovv-env.example.bat"
            )
        )

        from(
            layout.projectDirectory.file(
                "distribution/xiaovv-env.example.ps1"
            )
        )

        from(
            layout.projectDirectory.file(
                "README.md"
            )
        )
    }

val packageWindows =
    tasks.register<Zip>("packageWindows") {

        group = "distribution"
        description =
            "Construit Xiaovv Windows autonome avec Java embarque"

        dependsOn(prepareWindowsPackage)

        archiveFileName.set(
            "xiaovv-${project.version}-windows-${windowsArch}.zip"
        )

        destinationDirectory.set(
            layout.buildDirectory.dir(
                "release"
            )
        )

        from(windowsPackageDir) {
            into("xiaovv")
        }

        doLast {
            println()
            println("============================================================")
            println(" Package Windows autonome Xiaovv cree")
            println("============================================================")
            println("Architecture : $windowsArch")
            println(
                "ZIP          : " +
                        archiveFile.get().asFile.absolutePath
            )
            println("Java         : embarque")
            println("============================================================")
            println()
        }
    }

/*
 * ============================================================================
 * LINUX AUTONOME - JAVA EMBARQUE
 * ============================================================================
 *
 * IMPORTANT :
 *
 * jpackage construit une image native pour le système sur lequel
 * la tâche est exécutée.
 *
 * packageLinux doit donc être lancé sous Linux.
 *
 * Pour obtenir un package ARM64 destiné à une machine ARM64,
 * il faut exécuter cette tâche sur un Linux ARM64 avec un JDK 21
 * contenant jpackage.
 */

val linuxArch =
    when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> System.getProperty("os.arch").lowercase()
    }

val linuxInputDir =
    layout.buildDirectory.dir(
        "jpackage/linux-input"
    )

val linuxImageDir =
    layout.buildDirectory.dir(
        "jpackage/linux-image"
    )

val linuxPackageDir =
    layout.buildDirectory.dir(
        "release/linux-package/xiaovv"
    )

val cleanLinuxPackaging =
    tasks.register<Delete>("cleanLinuxPackaging") {

        group = "distribution"

        delete(
            linuxInputDir,
            linuxImageDir,
            layout.buildDirectory.dir(
                "release/linux-package"
            )
        )
    }

val prepareLinuxInput =
    tasks.register<Sync>("prepareLinuxInput") {

        group = "distribution"
        description = "Prépare les fichiers d'entrée Linux pour jpackage"

        dependsOn(
            cleanLinuxPackaging,
            fatJar
        )

        into(linuxInputDir)

        from(
            fatJar.flatMap {
                it.archiveFile
            }
        )
    }

val packageLinuxImage =
    tasks.register<Exec>("packageLinuxImage") {

        group = "distribution"
        description = "Construit l'image Linux autonome avec jpackage"

        dependsOn(prepareLinuxInput)

        doFirst {

            val osName =
                System.getProperty("os.name")
                    .lowercase()

            if (!osName.contains("linux")) {
                throw GradleException(
                    "packageLinux doit etre execute sous Linux."
                )
            }

            commandLine(
                "jpackage",
                "--type", "app-image",
                "--name", "Xiaovv",
                "--input", linuxInputDir.get().asFile.absolutePath,
                "--main-jar", "xiaovv.jar",
                "--main-class", "fr.nachidel.xiaovv.MainKt",
                "--dest", linuxImageDir.get().asFile.absolutePath,
                "--app-version", project.version.toString(),
                "--vendor", "Nachidel",
                "--description", "Xiaovv V380 multi-camera RTSP server",

                /*
                 * UTF-8 explicite, même si Java 21 l'utilise déjà
                 * généralement par défaut.
                 */
                "--java-options", "-Dfile.encoding=UTF-8",
                "--java-options", "-Dsun.stdout.encoding=UTF-8",
                "--java-options", "-Dsun.stderr.encoding=UTF-8",

                /*
                 * Sous Linux, $APPDIR pointe vers le dossier
                 * applicatif jpackage contenant le JAR.
                 *
                 * start.sh synchronise le fichier utilisateur
                 * vers ce dossier avant de démarrer le lanceur.
                 */
                "--java-options",
                "-Dxiaovv.config=\$APPDIR/application.properties"
            )
        }
    }

val prepareLinuxPackage =
    tasks.register<Sync>("prepareLinuxPackage") {

        group = "distribution"
        description = "Prépare le dossier Linux autonome final"

        dependsOn(packageLinuxImage)

        into(linuxPackageDir)

        /*
         * Image jpackage Linux :
         *
         *   bin/Xiaovv
         *   lib/app/
         *   lib/runtime/
         */
        from(
            linuxImageDir.map {
                it.dir("Xiaovv")
            }
        )

        /*
         * Lanceur Linux de notre distribution.
         */
        from(
            layout.projectDirectory.dir(
                "distribution/linux"
            )
        )

        /*
         * Configuration et secrets d'exemple.
         */
        from(
            layout.projectDirectory.file(
                "distribution/application.properties.example"
            )
        )

        from(
            layout.projectDirectory.file(
                "distribution/xiaovv-env.example.sh"
            )
        )

        /*
         * Documentation.
         */
        from(
            layout.projectDirectory.file(
                "README.md"
            )
        )
    }

val packageLinux =
    tasks.register<Tar>("packageLinux") {

        group = "distribution"
        description =
            "Construit Xiaovv Linux autonome avec Java embarque"

        dependsOn(prepareLinuxPackage)

        archiveFileName.set(
            "xiaovv-${project.version}-linux-${linuxArch}.tar.gz"
        )

        compression = Compression.GZIP

        destinationDirectory.set(
            layout.buildDirectory.dir(
                "release"
            )
        )

        from(linuxPackageDir) {
            into("xiaovv")
        }

        doLast {
            println()
            println("============================================================")
            println(" Package Linux autonome Xiaovv cree")
            println("============================================================")
            println("Architecture : $linuxArch")
            println("TAR.GZ       : " + archiveFile.get().asFile.absolutePath)
            println("Java         : embarque")
            println("============================================================")
            println()
        }
    }
