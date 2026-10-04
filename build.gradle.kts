import org.gradle.api.GradleException
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Compression
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip
import java.io.File
import java.awt.GraphicsEnvironment
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JPasswordField
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

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
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true

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
        exclude("mqtt.properties", "mqtt.properties.bak")

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

        from(layout.projectDirectory.file("LICENSE"))
        from(layout.projectDirectory.file("NOTICE"))
        from(layout.projectDirectory.file("COMMERCIAL-LICENSING.md"))
        from(layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"))

        from(layout.projectDirectory.dir("THIRD-PARTY-LICENSES")) {
            into("THIRD-PARTY-LICENSES")
        }
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

        from(layout.projectDirectory.file("LICENSE"))
        from(layout.projectDirectory.file("NOTICE"))
        from(layout.projectDirectory.file("COMMERCIAL-LICENSING.md"))
        from(layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"))

        from(layout.projectDirectory.dir("THIRD-PARTY-LICENSES")) {
            into("THIRD-PARTY-LICENSES")
        }
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

        from(layout.projectDirectory.file("LICENSE"))
        from(layout.projectDirectory.file("NOTICE"))
        from(layout.projectDirectory.file("COMMERCIAL-LICENSING.md"))
        from(layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"))

        from(layout.projectDirectory.dir("THIRD-PARTY-LICENSES")) {
            into("THIRD-PARTY-LICENSES")
        }
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

/*
 * ============================================================================
 * DEPLOIEMENT RASPBERRY PI — variables et tâches regroupées ici
 * ============================================================================
 * SSH par clé et sudo sans saisie doivent être configurés une première fois.
 * Aucun mot de passe dans ce fichier ni dans les paramètres Gradle.
 */

val rpiHost = providers.gradleProperty("rpi.host").getOrElse("192.168.1.15")
val rpiUser = providers.gradleProperty("rpi.user").getOrElse("nachidel")
val rpiSshPort = providers.gradleProperty("rpi.port").getOrElse("22").toInt()
val rpiDomain = providers.gradleProperty("rpi.domain").getOrElse("www.nachidel.ovh")
val rpiHttpPort = providers.gradleProperty("rpi.httpPort").getOrElse("8081").toInt()
val rpiHttpsPort = providers.gradleProperty("rpi.httpsPort").getOrElse("8443").toInt()
val rpiService = "xiaovv"
val rpiSshKey = file(providers.gradleProperty("rpi.identityFile").getOrElse("config/rpi/ssh/id_ed25519"))
val rpiCertificate = file("config/certs/xiaovv.p12")
val rpiPasswordFile = file("config/certs/xiaovv-password.dpapi")
val rpiApplicationFile = file("src/main/resources/application.properties")
val rpiMqttFile = file("src/main/resources/mqtt.properties")
val rpiSecretsFile = file("config/xiaovv-env.bat")
val rpiIdeaRun = "xiaovv_decoder [run]"
val rpiSyncConfig = providers.gradleProperty("rpi.syncConfig").getOrElse("true").toBooleanStrict()

fun rpiQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"
fun rpiBase64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
fun rpiHash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }

// Les processus reçoivent les secrets par stdin ; leur sortie reste masquée.
// Seuls les diagnostics connus et les codes émis par nos scripts sont affichés.
fun rpiProcess(command: List<String>, input: ByteArray, step: String, timeout: Long = 90,
               outputCharset: java.nio.charset.Charset = Charsets.UTF_8): String {
    val process = try { ProcessBuilder(command).redirectErrorStream(true).start() }
    catch (_: Exception) { throw GradleException("$step : exécutable introuvable (${command.first()}).") }
    val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readBytes() } }
    try {
        var writeFailed = false
        try { process.outputStream.use { it.write(input) } } catch (_: java.io.IOException) { writeFailed = true }
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) throw GradleException("$step : délai dépassé.")
        val bytes = output.get(5, TimeUnit.SECONDS)
        val text = bytes.toString(outputCharset)
        bytes.fill(0)
        if (process.exitValue() != 0 || writeFailed) {
            val occupiedPort = Regex("(?m)^XIAOVV_PORT:([0-9]{1,5})\\r?$").find(text)?.groupValues?.get(1)
            val reason = when {
                text.contains("Permission denied") -> "Connexion SSH refusée : installer la clé publique ${rpiSshKey.name}.pub dans ~/.ssh/authorized_keys du compte $rpiUser sur $rpiHost."
                text.contains("REMOTE HOST IDENTIFICATION HAS CHANGED") -> "La clé d'hôte SSH a changé : vérifier l'identité du Pi avant de corriger known_hosts."
                text.contains("Host key verification failed") -> "Clé d'hôte SSH non approuvée : ouvrir une connexion manuelle à $rpiUser@$rpiHost sur le port $rpiSshPort."
                text.contains("Connection refused") -> "Connexion refusée sur $rpiHost:$rpiSshPort : vérifier le serveur SSH."
                text.contains("timed out") || text.contains("No route to host") -> "Pi injoignable à $rpiHost:$rpiSshPort."
                text.contains("XIAOVV_ERROR:SUDO") || text.contains("sudo:") -> "sudo exige une saisie ou refuse cette commande. Vérifier ssh puis sudo -n true sur le Pi."
                text.contains("XIAOVV_ERROR:ARM64") -> "Le Pi doit utiliser Linux ARM64 (uname -m : aarch64)."
                text.contains("XIAOVV_ERROR:SYSTEMD") -> "systemd n'est pas actif sur cette machine."
                text.contains("XIAOVV_ERROR:UNIT") -> "Un service $rpiService existe déjà et n'a pas été créé par ces tâches ; il a été conservé."
                text.contains("XIAOVV_ERROR:CERTIFICATE") -> "Le certificat ne correspond pas à $rpiDomain, a expiré ou son mot de passe est incorrect."
                text.contains("XIAOVV_ERROR:JAVA") -> "Le runtime Java 21 téléchargé ou son SHA-256 n'est pas valide."
                text.contains("XIAOVV_ERROR:FFMPEG_AUDIO") -> "FFmpeg ne possède pas l'encodeur libmp3lame nécessaire au son Web ; installer un paquet FFmpeg avec cet encodeur sur le Pi."
                text.contains("XIAOVV_ERROR:READ_ACCESS") -> "Le compte $rpiService ne peut pas lire le programme ou le certificat ; vérifier les droits des fichiers installés."
                text.contains("XIAOVV_ERROR:PORT") -> "Le port ${occupiedPort ?: "HTTP/HTTPS/RTSP"} est déjà utilisé par un autre programme sur le Pi ; modifier le port correspondant dans les variables rpi de build.gradle.kts."
                text.contains("XIAOVV_ERROR:HEALTH") -> "Le contrôle HTTPS/authentification a échoué. Consulter sudo journalctl -u $rpiService -n 80 sur le Pi."
                else -> "Échec (code ${process.exitValue()}). La sortie est masquée car elle peut contenir un secret."
            }
            throw GradleException("$step : $reason")
        }
        return text.trim()
    } finally {
        input.fill(0)
        if (process.isAlive) process.destroyForcibly()
    }
}

fun rpiSshCommand(command: String): List<String> = buildList {
    addAll(listOf("ssh", "-T", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
        "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", "-p", rpiSshPort.toString()))
    if (rpiSshKey.isFile) addAll(listOf("-i", rpiSshKey.absolutePath, "-o", "IdentitiesOnly=yes"))
    add("$rpiUser@$rpiHost")
    add(command)
}

fun rpiSsh(script: String, step: String, root: Boolean = true, timeout: Long = 90): String {
    val command = (if (root && rpiUser != "root") "sudo -n " else "") + "bash -s"
    return rpiProcess(rpiSshCommand(command), ("set -eu\n" + script.trimIndent() + "\n").toByteArray(), step, timeout)
}

// Écrit atomiquement seulement si le contenu, le propriétaire ou les droits diffèrent.
fun rpiRemote(script: String, step: String, timeout: Long = 90): String = rpiSsh("""
    service=${rpiQuote(rpiService)}
    root=/opt/${rpiService}
    state=/var/lib/${rpiService}
    config=/etc/${rpiService}
    fail() { printf 'XIAOVV_ERROR:%s\n' "${'$'}1"; exit 1; }
    changed() { touch "${'$'}state/.deployment-dirty"; }
    install_changed() {
        local source=${'$'}1 target=${'$'}2 owner=${'$'}3 group=${'$'}4 mode=${'$'}5 temporary
        if ! cmp -s "${'$'}source" "${'$'}target" ||
           [ "${'$'}(stat -c '%a:%U:%G' "${'$'}target" 2>/dev/null || true)" != "${'$'}mode:${'$'}owner:${'$'}group" ]; then
            temporary=${'$'}(mktemp "${'$'}target.XXXXXX")
            install -o "${'$'}owner" -g "${'$'}group" -m "${'$'}mode" "${'$'}source" "${'$'}temporary"
            mv -f "${'$'}temporary" "${'$'}target"
            changed
        fi
    }
    $script
""".trimIndent(), step, timeout = timeout)

fun rpiReadProperties(file: File): Properties = Properties().apply {
    if (!file.isFile) throw GradleException("Fichier requis introuvable : $file")
    file.inputStream().use { load(it) }
}

// Ordre stable, sans date de génération : la configuration identique n'est pas réinstallée.
fun rpiPropertiesBytes(properties: Properties): ByteArray {
    fun escape(text: String) = buildString {
        text.forEach { ch ->
            when {
                ch.code !in 32..126 -> append("\\u%04x".format(ch.code))
                ch in "\\ =:#!" -> append('\\').append(ch)
                else -> append(ch)
            }
        }
    }
    return properties.stringPropertyNames().sorted().joinToString("\n", postfix = "\n") {
        escape(it) + "=" + escape(properties.getProperty(it))
    }.toByteArray(Charsets.US_ASCII)
}

var rpiApplication: Properties? = null
var rpiMqtt: Properties? = null
val rpiSecrets = sortedMapOf<String, String>()

fun rpiLoadConfiguration() {
    if (rpiApplication != null) return
    val app = rpiReadProperties(rpiApplicationFile)
    val mqtt = rpiReadProperties(rpiMqttFile)
    // Reprend les secrets de Run pour éviter de les recopier dans un nouveau fichier.
    val workspace = file(".idea/workspace.xml")
    if (workspace.isFile && rpiIdeaRun.isNotEmpty()) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val configurations = factory.newDocumentBuilder().parse(workspace).getElementsByTagName("configuration")
        for (i in 0 until configurations.length) {
            val config = configurations.item(i) as Element
            if (config.getAttribute("name") != rpiIdeaRun) continue
            for ((tag, attribute) in listOf("env" to "name", "entry" to "key")) {
                val entries = config.getElementsByTagName(tag)
                for (j in 0 until entries.length) {
                    val entry = entries.item(j) as Element
                    val name = entry.getAttribute(attribute)
                    if (name.matches(Regex("XIAOVV_[A-Z0-9_]+"))) rpiSecrets[name] = entry.getAttribute("value")
                }
            }
        }
    }
    if (rpiSecretsFile.isFile) {
        if (rpiSecretsFile.extension == "bat") {
            require(System.getProperty("os.name").startsWith("Windows")) { "Sous Linux, utiliser un fichier de secrets .properties." }
            val command = "call \"${rpiSecretsFile.absolutePath}\" >nul && set XIAOVV_"
            val output = rpiProcess(listOf(System.getenv("ComSpec") ?: "cmd.exe", "/u", "/d", "/c", command),
                byteArrayOf(), "Lecture des secrets locaux", outputCharset = Charsets.UTF_16LE)
            output.lineSequence().forEach {
                val name = it.substringBefore('=')
                if (name.matches(Regex("XIAOVV_[A-Z0-9_]+"))) rpiSecrets[name] = it.substringAfter('=')
            }
        } else {
            val values = rpiReadProperties(rpiSecretsFile)
            values.stringPropertyNames().forEach { rpiSecrets[it] = values.getProperty(it) }
        }
    }
    val required = mutableSetOf<String>()
    fun secret(props: Properties, key: String, defaultEnv: String, needed: Boolean) {
        val env = props.getProperty("$key-env", defaultEnv)
        require(env.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Nom de variable invalide : $key-env" }
        (props.remove(key) as? String)?.takeIf { it.isNotBlank() }?.let { rpiSecrets.putIfAbsent(env, it) }
        System.getenv(env)?.takeIf { it.isNotBlank() }?.let { rpiSecrets[env] = it }
        props.setProperty("$key-env", env)
        if (needed || !rpiSecrets[env].isNullOrBlank()) required.add(env)
    }
    secret(app, "api.token", "XIAOVV_API_TOKEN", true)
    fun ids(props: Properties, prefix: String) = props.stringPropertyNames()
        .map { it.split('.') }.filter { it.size >= 3 && it[0] == prefix }.map { it[1] }.toSet()
    for (id in ids(app, "camera")) {
        secret(app, "camera.$id.password", "XIAOVV_CAMERA_${id.uppercase().replace('-', '_')}_PASSWORD",
            app.getProperty("camera.$id.enabled", "true").toBoolean())
    }
    for (id in ids(mqtt, "mqtt")) {
        secret(mqtt, "mqtt.$id.password", "XIAOVV_MQTT_${id.uppercase().replace('-', '_')}_PASSWORD",
            mqtt.getProperty("mqtt.$id.enabled", "true").toBoolean() && !mqtt.getProperty("mqtt.$id.username", "").isBlank())
    }
    rpiSecrets.keys.retainAll(required)
    required.forEach { if (rpiSecrets[it].isNullOrBlank()) throw GradleException("Secret requis absent : $it. Le définir dans Run ou l'environnement.") }
    require(rpiSecrets.getValue(app.getProperty("api.token-env")).length >= 16) { "Le token API doit contenir au moins 16 caractères." }
    app.setProperty("api.port", rpiHttpPort.toString())
    app.setProperty("api.https.enabled", "true")
    app.setProperty("api.https.bind-address", "0.0.0.0")
    app.setProperty("api.https.port", rpiHttpsPort.toString())
    app.setProperty("api.https.key-store", "/etc/$rpiService/certs/xiaovv.p12")
    app.setProperty("api.https.key-store-type", "PKCS12")
    app.setProperty("api.https.key-store-password-env", "XIAOVV_HTTPS_KEY_STORE_PASSWORD")
    app.remove("api.https.key-store-password-file")
    val ports = listOf(app.getProperty("api.port", "8080").toInt(), rpiHttpsPort, app.getProperty("rtsp.port", "8555").toInt())
    require(ports.all { it in 1024..65535 } && ports.distinct().size == 3) { "Ports HTTP/HTTPS/RTSP invalides ou identiques." }
    rpiApplication = app
    rpiMqtt = mqtt
}

fun rpiCertificatePassword(): String {
    rpiSecrets["XIAOVV_HTTPS_KEY_STORE_PASSWORD"]?.let { return it }
    var password = System.getenv("XIAOVV_HTTPS_KEY_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
    if (password == null && rpiPasswordFile.isFile && System.getProperty("os.name").startsWith("Windows")) {
        val script = """
            ${'$'}ErrorActionPreference='Stop'
            try {
                Add-Type -AssemblyName System.Security
                ${'$'}data=[Convert]::FromBase64String([Console]::In.ReadLine())
                ${'$'}clear=[System.Security.Cryptography.ProtectedData]::Unprotect(${'$'}data,${'$'}null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser)
                [Console]::Out.Write([Convert]::ToBase64String(${'$'}clear))
            } catch { exit 1 }
        """.trimIndent()
        val output = rpiProcess(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script),
            (rpiBase64(rpiPasswordFile.readBytes()) + "\n").toByteArray(), "Lecture du mot de passe HTTPS chiffré")
        val clear = Base64.getDecoder().decode(output)
        password = clear.toString(Charsets.UTF_8)
        clear.fill(0)
    }
    if (password == null) {
        var chars = System.console()?.readPassword("Mot de passe du certificat : ")
        if (chars == null && !GraphicsEnvironment.isHeadless()) SwingUtilities.invokeAndWait {
            val field = JPasswordField(24)
            if (JOptionPane.showConfirmDialog(null, arrayOf("Mot de passe d'export du certificat Jeedom", field),
                "Déploiement Xiaovv", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) chars = field.password
            field.text = ""
        }
        val value = chars ?: throw GradleException("Définir XIAOVV_HTTPS_KEY_STORE_PASSWORD ; saisie annulée ou indisponible.")
        password = String(value)
        value.fill('\u0000')
    }
    val value = password!!
    val chars = value.toCharArray()
    try {
        val store = KeyStore.getInstance("PKCS12")
        rpiCertificate.inputStream().use { store.load(it, chars) }
        val aliases = store.aliases().toList().filter { store.isKeyEntry(it) && store.getKey(it, chars) is PrivateKey }
        require(aliases.isNotEmpty())
        aliases.forEach { (store.getCertificate(it) as X509Certificate).checkValidity() }
    } catch (_: Exception) { throw GradleException("PKCS12 invalide, expiré ou mot de passe incorrect ; aucun certificat installé.") }
    finally { chars.fill('\u0000') }
    rpiSecrets["XIAOVV_HTTPS_KEY_STORE_PASSWORD"] = value
    return value
}

fun rpiTask(name: String, description: String, vararg dependencies: String, action: () -> Unit) = tasks.register(name) {
    group = "déploiement Raspberry Pi"
    this.description = description
    dependsOn(*dependencies)
    notCompatibleWithConfigurationCache("Vérification distante SSH et lecture de secrets à chaque lancement")
    doLast { action() }
}

val packagerRpi = tasks.register<Tar>("packagerRpi") {
    group = "déploiement Raspberry Pi"
    description = "Construit un package reproductible pour le Pi, sans configuration ni secrets réels"
    dependsOn(fatJar, tasks.test)
    archiveFileName.set("xiaovv-${project.version}-rpi.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    compression = Compression.GZIP
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(fatJar.flatMap { it.archiveFile }) { into("app") }
    from("README.md", "LICENSE", "NOTICE", "COMMERCIAL-LICENSING.md", "THIRD-PARTY-NOTICES.md")
    from("THIRD-PARTY-LICENSES") { into("THIRD-PARTY-LICENSES") }
}

rpiTask("verifierPrerequisRpi", "Vérifie l'accès SSH/sudo, ARM64 et systemd", "packagerRpi") {
    require(rpiHost.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]*")) && rpiUser.matches(Regex("[a-z_][a-z0-9_-]*"))) { "IP ou utilisateur SSH invalide." }
    require(rpiDomain.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]*")) && rpiService.matches(Regex("[a-z][a-z0-9_-]*"))) { "Domaine ou nom de service invalide." }
    require(rpiSshPort in 1..65535 && rpiHttpPort in 1024..65535 && rpiHttpsPort in 1024..65535) { "Port invalide." }
    require(rpiCertificate.isFile) { "Certificat PKCS12 introuvable : $rpiCertificate" }
    rpiLoadConfiguration()
    rpiSsh("""
        fail() { printf 'XIAOVV_ERROR:%s\n' "${'$'}1"; exit 1; }
        [ "${'$'}(uname -m)" = aarch64 ] || fail ARM64
        [ -d /run/systemd/system ] || fail SYSTEMD
        if [ "${'$'}(id -u)" != 0 ]; then sudo -n true || fail SUDO; fi
    """, "Prérequis du Pi", root = false)
    rpiSsh("""
        unit=/etc/systemd/system/$rpiService.service
        if [ -e "${'$'}unit" ] && ! grep -qF '# Managed by Xiaovv Gradle deployment' "${'$'}unit"; then
            echo XIAOVV_ERROR:UNIT; exit 1
        fi
    """, "Vérification du service existant")
    logger.lifecycle("Prérequis OK : {}@{}:{} — ARM64, SSH/sudo, systemd et configuration", rpiUser, rpiHost, rpiSshPort)
}

rpiTask("installerJava21Rpi", "Vérifie Java 21 et les outils, installe seulement ceux qui manquent", "verifierPrerequisRpi") {
    rpiRemote("""
        id "${'$'}service" >/dev/null 2>&1 || useradd --system --user-group --home-dir "${'$'}state" --no-create-home --shell /usr/sbin/nologin "${'$'}service"
        [ "${'$'}(id -u "${'$'}service")" != 0 ]
        install -d -m 755 "${'$'}root" "${'$'}root/releases"
        install -d -o root -g "${'$'}service" -m 750 "${'$'}config" "${'$'}config/certs"
        install -d -o "${'$'}service" -g "${'$'}service" -m 700 "${'$'}state" "${'$'}state/config"
        missing=()
        for item in curl:curl openssl:openssl ffmpeg:ffmpeg tar:tar python3:python3 ss:iproute2; do
            command -v "${'$'}{item%%:*}" >/dev/null || missing+=("${'$'}{item#*:}")
        done
        [ -f /etc/ssl/certs/ca-certificates.crt ] || missing+=(ca-certificates)
        if [ "${'$'}{#missing[@]}" -gt 0 ]; then
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends "${'$'}{missing[@]}"
        fi
        temporary=${'$'}(mktemp -d "${'$'}root/.java21.XXXXXX")
        trap 'rm -rf -- "${'$'}temporary"' EXIT
        java=''
        for candidate in "${'$'}(cat "${'$'}config/java.path" 2>/dev/null || true)" "${'$'}root/java21/bin/java" "${'$'}(command -v java || true)" /usr/lib/jvm/*/bin/java; do
            [ -x "${'$'}candidate" ] || continue
            candidate=${'$'}(readlink -f "${'$'}candidate")
            case "${'$'}candidate" in /home/*|/root/*) continue;; esac
            if "${'$'}candidate" -version 2>&1 | grep -qE 'version "21(\.|")'; then java=${'$'}candidate; break; fi
        done
        if [ -z "${'$'}java" ]; then
            curl -fsSL --retry 3 --connect-timeout 15 --max-time 60 'https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=aarch64&image_type=jre&os=linux&vendor=eclipse' > "${'$'}temporary/metadata.json"
            url=${'$'}(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["binary"]["package"]["link"])' "${'$'}temporary/metadata.json")
            checksum=${'$'}(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["binary"]["package"]["checksum"])' "${'$'}temporary/metadata.json")
            [[ "${'$'}url" == https://* && "${'$'}checksum" =~ ^[a-fA-F0-9]{64}${'$'} ]] || fail JAVA
            curl -fsSL --retry 3 --connect-timeout 15 --max-time 600 -o "${'$'}temporary/java.tar.gz" "${'$'}url"
            echo "${'$'}checksum  ${'$'}temporary/java.tar.gz" | sha256sum -c - >/dev/null || fail JAVA
            mkdir "${'$'}temporary/runtime"
            tar -xzf "${'$'}temporary/java.tar.gz" --strip-components=1 -C "${'$'}temporary/runtime"
            "${'$'}temporary/runtime/bin/java" -version 2>&1 | grep -qE 'version "21(\.|")' || fail JAVA
            if [ -e "${'$'}root/java21" ]; then mv "${'$'}root/java21" "${'$'}root/java21-backup-${'$'}(date +%s)"; fi
            mv "${'$'}temporary/runtime" "${'$'}root/java21"
            java="${'$'}root/java21/bin/java"
            changed
        fi
        printf '%s\n' "${'$'}java" > "${'$'}temporary/java.path"
        install_changed "${'$'}temporary/java.path" "${'$'}config/java.path" root root 644
        ffmpeg -version >/dev/null
        ffmpeg -hide_banner -encoders 2>/dev/null | grep libmp3lame >/dev/null || fail FFMPEG_AUDIO
    """.trimIndent(), "Installation/vérification Java 21", timeout = 1200)
    logger.lifecycle("Java 21 et FFmpeg avec audio MP3 disponibles sur le Pi")
}

rpiTask("installerCertificatsRpi", "Valide puis installe le certificat s'il a changé", "installerJava21Rpi") {
    val password = rpiCertificatePassword()
    rpiRemote("""
        umask 077
        temporary=${'$'}(mktemp -d "${'$'}config/.certificate.XXXXXX")
        trap 'rm -rf -- "${'$'}temporary"' EXIT
        printf '%s' '${rpiBase64(rpiCertificate.readBytes())}' | base64 -d > "${'$'}temporary/certificate.p12"
        printf '%s' '${rpiBase64(password.toByteArray())}' | base64 -d |
            openssl pkcs12 -in "${'$'}temporary/certificate.p12" -passin stdin -clcerts -nokeys -out "${'$'}temporary/certificate.pem" 2>/dev/null || fail CERTIFICATE
        openssl x509 -in "${'$'}temporary/certificate.pem" -noout -checkend 0 >/dev/null || fail CERTIFICATE
        openssl x509 -in "${'$'}temporary/certificate.pem" -noout -checkhost ${rpiQuote(rpiDomain)} | grep -q 'does match certificate' || fail CERTIFICATE
        install_changed "${'$'}temporary/certificate.p12" "${'$'}config/certs/xiaovv.p12" root "${'$'}service" 640
    """.trimIndent(), "Installation/vérification du certificat")
}

rpiTask("installerMotsDePasseCertificatRpi", "Installe les mots de passe dans un fichier systemd protégé", "installerCertificatsRpi") {
    fun quoteEnv(value: String): String {
        require('\n' !in value && '\r' !in value && '\u0000' !in value) { "Un secret contient un caractère incompatible avec EnvironmentFile." }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
    val environment = rpiSecrets.entries.joinToString("\n", postfix = "\n") { "${it.key}=${quoteEnv(it.value)}" }
    rpiRemote("""
        umask 077
        temporary=${'$'}(mktemp "${'$'}config/.environment.XXXXXX")
        trap 'rm -f -- "${'$'}temporary"' EXIT
        printf '%s' '${rpiBase64(environment.toByteArray())}' | base64 -d > "${'$'}temporary"
        install_changed "${'$'}temporary" "${'$'}config/environment" root root 600
    """.trimIndent(), "Installation/vérification des mots de passe")
}

rpiTask("envoyerProgrammeRpi", "Transfère le programme et la configuration seulement s'ils ont changé", "installerMotsDePasseCertificatRpi", "packagerRpi") {
    val archive = packagerRpi.get().archiveFile.get().asFile
    val hash = rpiHash(archive)
    val jarHash = rpiHash(fatJar.get().archiveFile.get().asFile)
    val present = rpiRemote("""
        jar="${'$'}root/releases/$hash/app/xiaovv.jar"
        if [ -f "${'$'}jar" ] && [ "${'$'}(sha256sum "${'$'}jar" | cut -d' ' -f1)" = '$jarHash' ]; then echo present; else echo missing; fi
    """.trimIndent(), "Vérification de la version distante") == "present"
    if (!present) {
        val target = "/opt/$rpiService/.package-$hash.tar.gz"
        rpiProcess(rpiSshCommand((if (rpiUser == "root") "" else "sudo -n ") + "sh -c " +
            rpiQuote("umask 077; cat > ${rpiQuote(target)}")), archive.readBytes(), "Transfert du package", 300)
        rpiRemote("""
            archive=${rpiQuote(target)}
            temporary=${'$'}(mktemp -d "${'$'}root/releases/.install.XXXXXX")
            trap 'rm -rf -- "${'$'}temporary"; rm -f -- "${'$'}archive"' EXIT
            echo '$hash  $target' | sha256sum -c - >/dev/null
            tar -xzf "${'$'}archive" -C "${'$'}temporary"
            [ "${'$'}(sha256sum "${'$'}temporary/app/xiaovv.jar" | cut -d' ' -f1)" = '$jarHash' ]
            chmod 755 "${'$'}temporary"
            if [ -e "${'$'}root/releases/$hash" ]; then mv "${'$'}root/releases/$hash" "${'$'}root/releases/$hash-invalid-${'$'}(date +%s)"; fi
            mv "${'$'}temporary" "${'$'}root/releases/$hash"
        """.trimIndent(), "Installation du package")
        logger.lifecycle("Nouvelle version transférée et vérifiée")
    } else logger.lifecycle("Version du programme déjà présente : transfert ignoré")
    rpiRemote("""
        # Corrige aussi les versions déjà installées avec un dossier temporaire en 700.
        if [ "${'$'}(stat -c '%a' "${'$'}root/releases/$hash")" != 755 ]; then
            chmod 755 "${'$'}root/releases/$hash"
            changed
        fi
        if [ "${'$'}(readlink "${'$'}root/current" 2>/dev/null || true)" != "${'$'}root/releases/$hash" ]; then
            ln -s "${'$'}root/releases/$hash" "${'$'}root/.current-new"
            mv -Tf "${'$'}root/.current-new" "${'$'}root/current"
            changed
        fi
        temporary=${'$'}(mktemp -d "${'$'}state/.config.XXXXXX")
        trap 'rm -rf -- "${'$'}temporary"' EXIT
        printf '%s' '${rpiBase64(rpiPropertiesBytes(rpiApplication!!))}' | base64 -d > "${'$'}temporary/application.properties"
        printf '%s' '${rpiBase64(rpiPropertiesBytes(rpiMqtt!!))}' | base64 -d > "${'$'}temporary/mqtt.properties"
        for name in application.properties mqtt.properties; do
            if ${if (rpiSyncConfig) "true" else "false"} || [ ! -f "${'$'}state/config/${'$'}name" ]; then
                install_changed "${'$'}temporary/${'$'}name" "${'$'}state/config/${'$'}name" "${'$'}service" "${'$'}service" 600
            fi
        done
    """.trimIndent(), "Installation/vérification de la configuration")
}

rpiTask("installerServiceSystemdRpi", "Crée ou met à jour le service systemd et active son démarrage automatique", "envoyerProgrammeRpi") {
    val java = rpiRemote("cat \"${'$'}config/java.path\"", "Lecture du chemin Java")
    require(java.matches(Regex("/[A-Za-z0-9_./+-]+"))) { "Chemin Java incompatible avec l'unité systemd." }
    val unit = """
        # Managed by Xiaovv Gradle deployment
        [Unit]
        Description=Xiaovv camera server
        Wants=network-online.target
        After=network-online.target
        [Service]
        Type=simple
        User=$rpiService
        Group=$rpiService
        WorkingDirectory=/var/lib/$rpiService
        EnvironmentFile=/etc/$rpiService/environment
        ExecStart=$java -Dfile.encoding=UTF-8 -Dxiaovv.config=/var/lib/$rpiService/config/application.properties -jar /opt/$rpiService/current/app/xiaovv.jar
        Restart=on-failure
        RestartSec=5
        NoNewPrivileges=true
        PrivateTmp=true
        ProtectHome=true
        ProtectSystem=strict
        ReadWritePaths=/var/lib/$rpiService
        [Install]
        WantedBy=multi-user.target
    """.trimIndent() + "\n"
    rpiRemote("""
        temporary=${'$'}(mktemp -d)
        trap 'rm -rf -- "${'$'}temporary"' EXIT
        printf '%s' '${rpiBase64(unit.toByteArray())}' | base64 -d > "${'$'}temporary/$rpiService.service"
        systemd-analyze verify "${'$'}temporary/$rpiService.service" >/dev/null 2>&1
        previous=${'$'}(sha256sum /etc/systemd/system/$rpiService.service 2>/dev/null || true)
        install_changed "${'$'}temporary/$rpiService.service" /etc/systemd/system/$rpiService.service root root 644
        if [ "${'$'}previous" != "${'$'}(sha256sum /etc/systemd/system/$rpiService.service)" ] ||
           [ "${'$'}(systemctl show $rpiService.service -p NeedDaemonReload --value)" = yes ]; then
            systemctl daemon-reload
            changed
        fi
        systemctl is-enabled --quiet $rpiService.service || systemctl enable $rpiService.service
    """.trimIndent(), "Installation/vérification du service systemd")
}

rpiTask("demarrerServiceRpi", "Démarre le service ou le redémarre si son installation a changé", "installerServiceSystemdRpi") {
    val ports = listOf(rpiApplication!!.getProperty("api.port", "8080"), rpiHttpsPort.toString(), rpiApplication!!.getProperty("rtsp.port", "8555"))
    rpiRemote("""
        runuser -u "${'$'}service" -- test -r "${'$'}root/current/app/xiaovv.jar" || fail READ_ACCESS
        runuser -u "${'$'}service" -- test -r "${'$'}config/certs/xiaovv.p12" || fail READ_ACCESS
        port_occupied() { printf 'XIAOVV_PORT:%s\n' "${'$'}port"; fail PORT; }
        pid=${'$'}(systemctl show $rpiService.service -p MainPID --value)
        for port in ${ports.joinToString(" ")}; do
            listeners=${'$'}(ss -H -ltnp "sport = :${'$'}port")
            if [ -n "${'$'}listeners" ]; then
                [ "${'$'}pid" != 0 ] || port_occupied
                while IFS= read -r listener; do
                    case "${'$'}listener" in *"pid=${'$'}pid,"*) ;; *) port_occupied;; esac
                done <<< "${'$'}listeners"
            fi
        done
        if ! systemctl is-active --quiet $rpiService.service; then
            systemctl reset-failed $rpiService.service
            systemctl start $rpiService.service
        elif [ -e "${'$'}state/.deployment-dirty" ]; then
            systemctl restart $rpiService.service
        fi
    """.trimIndent(), "Démarrage du service")
}

rpiTask("verifierServiceRpi", "Vérifie le service et HTTPS avec le certificat du domaine et le token API", "demarrerServiceRpi") {
    val token = rpiSecrets.getValue(rpiApplication!!.getProperty("api.token-env"))
        .replace("\\", "\\\\").replace("\"", "\\\"")
    rpiRemote("""
        temporary=${'$'}(mktemp -d)
        trap 'rm -rf -- "${'$'}temporary"' EXIT
        umask 077
        printf '%s' '${rpiBase64(("header = \"X-API-Token: $token\"\n").toByteArray())}' | base64 -d > "${'$'}temporary/curl.conf"
        url=https://$rpiDomain:$rpiHttpsPort
        for attempt in ${'$'}(seq 1 30); do
            if systemctl is-active --quiet $rpiService.service &&
               curl -fLsS --noproxy '*' --max-time 2 --resolve '$rpiDomain:$rpiHttpsPort:127.0.0.1' "${'$'}url/" >/dev/null 2>&1; then break; fi
            sleep 2
        done
        systemctl is-active --quiet $rpiService.service || fail HEALTH
        anonymous=${'$'}(curl -sS --noproxy '*' --max-time 5 --resolve '$rpiDomain:$rpiHttpsPort:127.0.0.1' -o /dev/null -w '%{http_code}' "${'$'}url/api/cameras") || fail HEALTH
        [ "${'$'}anonymous" = 401 ] || fail HEALTH
        curl -fsS --noproxy '*' --max-time 5 --resolve '$rpiDomain:$rpiHttpsPort:127.0.0.1' --config "${'$'}temporary/curl.conf" "${'$'}url/api/cameras" >/dev/null 2>&1 || fail HEALTH
        rm -f "${'$'}state/.deployment-dirty"
    """.trimIndent(), "Contrôle HTTPS du service", timeout = 90)
}

rpiTask("deploiement", "Package, vérifie les prérequis, installe les éléments manquants et lance Xiaovv sur le Pi", "verifierServiceRpi") {
    logger.lifecycle("Déploiement terminé : https://{}:{}/ — service {} actif", rpiDomain, rpiHttpsPort, rpiService)
}
