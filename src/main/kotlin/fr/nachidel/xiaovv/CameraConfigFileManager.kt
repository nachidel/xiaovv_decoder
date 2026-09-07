package fr.nachidel.xiaovv

import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

data class EditableCameraDefinition(
    val id: String,
    val enabled: Boolean,
    val streamName: String,
    val host: String,
    val port: Int,
    val deviceId: String,
    val username: String,
    val resolution: String,
    val reconnectDelayMs: Long,
    val passwordConfigured: Boolean,
    val passwordEnv: String?,
    val automaticPasswordEnvironmentAvailable: Boolean,
    val environmentOverrides: List<String>
)

data class CameraConfigSnapshot(
    val editable: Boolean,
    val path: String?,
    val cameras: List<EditableCameraDefinition>
)

data class CameraConfigUpdate(
    val id: String,
    val enabled: Boolean,
    val streamName: String,
    val host: String,
    val port: Int,
    val deviceId: String,
    val username: String,
    val resolution: String,
    val reconnectDelayMs: Long,
    val passwordMode: String,
    val password: String?,
    val passwordEnv: String?
)

class CameraConfigFileManager {

    companion object {

        private val cameraIdRegex =
            Regex(
                """[A-Za-z0-9_-]+"""
            )

        private val cameraKeyRegex =
            Regex(
                """^camera\.([^.]+)\.(.+)$"""
            )

        private val physicalPropertyRegex =
            Regex(
                """^\s*(camera\.[^.]+\.[^=:\s]+)\s*[:=].*$"""
            )

        private val knownCameraFields =
            setOf(
                "enabled",
                "stream-name",
                "host",
                "port",
                "device-id",
                "username",
                "password",
                "password-env",
                "resolution",
                "reconnect-delay-ms"
            )

        private val environmentAwareFields =
            listOf(
                "enabled",
                "stream-name",
                "host",
                "port",
                "device-id",
                "username",
                "resolution",
                "reconnect-delay-ms"
            )
    }

    fun snapshot():
            CameraConfigSnapshot {

        val path =
            externalConfigPath()

        if (
            path == null ||
            !Files.exists(
                path
            )
        ) {

            return CameraConfigSnapshot(
                editable =
                    false,

                path =
                    path?.toAbsolutePath()
                        ?.normalize()
                        ?.toString(),

                cameras =
                    emptyList()
            )
        }

        val properties =
            loadProperties(
                path
            )

        val cameraIds =
            properties
                .stringPropertyNames()
                .mapNotNull { key ->

                    cameraKeyRegex
                        .matchEntire(
                            key
                        )
                        ?.groupValues
                        ?.get(
                            1
                        )
                }
                .toSortedSet()

        val cameras =
            cameraIds.map { id ->

                buildEditableCamera(
                    properties,
                    id
                )
            }

        return CameraConfigSnapshot(
            editable =
                Files.isWritable(
                    path
                ),

            path =
                path.toAbsolutePath()
                    .normalize()
                    .toString(),

            cameras =
                cameras
        )
    }

    /*
     * Recharge la configuration effective des caméras depuis le même
     * fichier que celui édité par l'interface.
     *
     * Contrairement à snapshot(), cette méthode applique aussi les
     * variables d'environnement exactement comme AppConfig :
     *   XIAOVV_CAMERA_<ID>_<CLE>
     *
     * Elle retourne uniquement les caméras activées, prêtes à être
     * injectées dans CameraRuntimeManager pour le hot reload.
     */
    fun loadEnabledCameraConfigs():
            List<CameraConfig> {

        val path =
            externalConfigPath()
                ?: error(
                    "Aucun fichier application.properties modifiable n'a été trouvé."
                )

        require(
            Files.isRegularFile(
                path
            )
        ) {
            "Fichier de configuration introuvable."
        }

        val properties =
            loadProperties(
                path
            )

        val cameraIds =
            properties
                .stringPropertyNames()
                .mapNotNull { key ->

                    cameraKeyRegex
                        .matchEntire(
                            key
                        )
                        ?.groupValues
                        ?.get(
                            1
                        )
                }
                .toSortedSet()

        val cameras =
            mutableListOf<CameraConfig>()

        for (
        id in
        cameraIds
        ) {

            validateCameraId(
                id
            )

            val enabled =
                effectiveCameraValue(
                    properties =
                        properties,

                    id =
                        id,

                    key =
                        "enabled",

                    default =
                        "true"
                )
                    .toBooleanStrictOrNull()
                    ?: error(
                        "camera.$id.enabled doit valoir true ou false."
                    )

            if (!enabled) {
                continue
            }

            val streamName =
                effectiveCameraValue(
                    properties,
                    id,
                    "stream-name",
                    id
                )

            require(
                streamName.matches(
                    cameraIdRegex
                )
            ) {
                "Nom de flux RTSP invalide pour '$id'."
            }

            val host =
                effectiveCameraValue(
                    properties,
                    id,
                    "host"
                )

            val port =
                effectiveCameraValue(
                    properties,
                    id,
                    "port",
                    "8800"
                )
                    .toIntOrNull()
                    ?: error(
                        "Port caméra invalide pour '$id'."
                    )

            require(
                port in 1..65535
            ) {
                "Port caméra invalide pour '$id'."
            }

            val deviceId =
                effectiveCameraValue(
                    properties,
                    id,
                    "device-id"
                )

            val username =
                effectiveCameraValue(
                    properties,
                    id,
                    "username",
                    deviceId
                )

            val explicitPasswordEnv =
                properties
                    .getProperty(
                        "camera.$id.password-env"
                    )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

            val automaticPasswordEnv =
                cameraEnvironmentName(
                    id,
                    "password"
                )

            val password =
                explicitPasswordEnv
                    ?.let {
                        System.getenv(
                            it
                        )
                    }
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: System.getenv(
                        automaticPasswordEnv
                    )
                        ?.takeIf {
                            it.isNotBlank()
                        }
                    ?: properties
                        .getProperty(
                            "camera.$id.password"
                        )
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                    ?: error(
                        "Mot de passe absent pour la caméra '$id'."
                    )

            val resolution =
                CameraResolution.from(
                    effectiveCameraValue(
                        properties,
                        id,
                        "resolution",
                        "high"
                    )
                )

            val reconnectDelayMs =
                effectiveCameraValue(
                    properties,
                    id,
                    "reconnect-delay-ms",
                    "5000"
                )
                    .toLongOrNull()
                    ?: error(
                        "Délai de reconnexion invalide pour '$id'."
                    )

            require(
                reconnectDelayMs >= 1_000L
            ) {
                "Délai de reconnexion trop faible pour '$id'."
            }

            cameras.add(
                CameraConfig(
                    id =
                        id,

                    enabled =
                        true,

                    streamName =
                        streamName,

                    host =
                        host,

                    port =
                        port,

                    deviceId =
                        deviceId,

                    username =
                        username,

                    password =
                        password,

                    resolution =
                        resolution,

                    reconnectDelayMs =
                        reconnectDelayMs
                )
            )
        }

        val duplicateStreams =
            cameras
                .groupBy {
                    it.streamName
                }
                .filterValues {
                    it.size > 1
                }

        require(
            duplicateStreams.isEmpty()
        ) {
            "Plusieurs caméras utilisent le même stream RTSP : " +
                    duplicateStreams.keys.joinToString()
        }

        return cameras
    }

    fun save(
        update: CameraConfigUpdate
    ) {

        validateUpdate(
            update
        )

        val path =
            requireEditablePath()

        val existingProperties =
            loadProperties(
                path
            )

        val prefix =
            "camera.${update.id}."

        val alreadyExists =
            existingProperties
                .stringPropertyNames()
                .any {
                    it.startsWith(
                        prefix
                    )
                }

        val existingPassword =
            existingProperties
                .getProperty(
                    "${prefix}password"
                )
                ?.takeIf {
                    it.isNotBlank()
                }

        val existingPasswordEnv =
            existingProperties
                .getProperty(
                    "${prefix}password-env"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val automaticPasswordEnvironment =
            cameraEnvironmentName(
                update.id,
                "password"
            )

        val updates =
            linkedMapOf<String, String?>(
                "${prefix}enabled" to
                        update.enabled.toString(),

                "${prefix}stream-name" to
                        update.streamName,

                "${prefix}host" to
                        update.host,

                "${prefix}port" to
                        update.port.toString(),

                "${prefix}device-id" to
                        update.deviceId,

                "${prefix}username" to
                        update.username,

                "${prefix}resolution" to
                        update.resolution.lowercase(),

                "${prefix}reconnect-delay-ms" to
                        update.reconnectDelayMs.toString()
            )

        when (
            update.passwordMode
                .lowercase()
        ) {

            "keep" -> {

                if (!alreadyExists) {

                    val automaticAvailable =
                        !System.getenv(
                            automaticPasswordEnvironment
                        )
                            .isNullOrBlank()

                    require(
                        automaticAvailable
                    ) {
                        "Pour une nouvelle caméra, configurez un mot de passe " +
                                "ou une variable d'environnement."
                    }
                }

                /*
                 * Ne rien ajouter : les lignes existantes password /
                 * password-env restent exactement comme elles sont.
                 */
            }

            "file" -> {

                val password =
                    update.password
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: error(
                            "Le nouveau mot de passe est vide."
                        )

                updates[
                    "${prefix}password"
                ] =
                    password

                updates[
                    "${prefix}password-env"
                ] =
                    null
            }

            "env" -> {

                val passwordEnv =
                    update.passwordEnv
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: error(
                            "Le nom de la variable d'environnement est vide."
                        )

                require(
                    passwordEnv.matches(
                        Regex(
                            """[A-Za-z_][A-Za-z0-9_]*"""
                        )
                    )
                ) {
                    "Nom de variable d'environnement invalide."
                }

                updates[
                    "${prefix}password-env"
                ] =
                    passwordEnv

                updates[
                    "${prefix}password"
                ] =
                    null
            }

            else ->
                error(
                    "Mode de mot de passe invalide."
                )
        }

        /*
         * En mode KEEP, si la caméra existe mais n'a actuellement aucune
         * source de mot de passe exploitable, on refuse de produire une
         * configuration qui empêchera Xiaovv de redémarrer.
         */
        if (
            update.passwordMode.equals(
                "keep",
                ignoreCase = true
            ) &&
            alreadyExists
        ) {

            val explicitEnvAvailable =
                existingPasswordEnv
                    ?.let {
                        System.getenv(
                            it
                        )
                    }
                    ?.isNotBlank()
                    ?: false

            val automaticEnvAvailable =
                !System.getenv(
                    automaticPasswordEnvironment
                )
                    .isNullOrBlank()

            require(
                !existingPassword.isNullOrBlank() ||
                        explicitEnvAvailable ||
                        automaticEnvAvailable
            ) {
                "Aucun mot de passe exploitable n'est actuellement configuré " +
                        "pour cette caméra."
            }
        }

        val newText =
            rewriteCameraProperties(
                original =
                    Files.readString(
                        path,
                        StandardCharsets.UTF_8
                    ),

                cameraId =
                    update.id,

                updates =
                    updates,

                appendNewBlock =
                    !alreadyExists
            )

        validateResultingConfiguration(
            newText
        )

        writeAtomically(
            path,
            newText
        )
    }

    fun delete(
        cameraId: String
    ) {

        validateCameraId(
            cameraId
        )

        val path =
            requireEditablePath()

        val original =
            Files.readString(
                path,
                StandardCharsets.UTF_8
            )

        val lineEnding =
            if (
                original.contains(
                    "\r\n"
                )
            ) {
                "\r\n"
            } else {
                "\n"
            }

        val hadFinalNewline =
            original.endsWith(
                "\n"
            )

        val lines =
            original
                .split(
                    Regex(
                        """\r?\n"""
                    )
                )
                .toMutableList()

        if (
            hadFinalNewline &&
            lines.isNotEmpty() &&
            lines.last().isEmpty()
        ) {
            lines.removeAt(
                lines.lastIndex
            )
        }

        val prefix =
            "camera.$cameraId."

        val filtered =
            lines.filterNot { line ->

                val key =
                    propertyKeyFromPhysicalLine(
                        line
                    )

                key != null &&
                        key.startsWith(
                            prefix
                        )
            }

        require(
            filtered.size != lines.size
        ) {
            "Caméra '$cameraId' introuvable dans le fichier de configuration."
        }

        val newText =
            filtered.joinToString(
                lineEnding
            ) +
                    if (
                        hadFinalNewline
                    ) {
                        lineEnding
                    } else {
                        ""
                    }

        validateResultingConfiguration(
            newText
        )

        writeAtomically(
            path,
            newText
        )
    }

    private fun buildEditableCamera(
        properties: Properties,
        id: String
    ): EditableCameraDefinition {

        val prefix =
            "camera.$id."

        val enabled =
            properties
                .getProperty(
                    "${prefix}enabled",
                    "true"
                )
                .trim()
                .toBooleanStrictOrNull()
                ?: true

        val streamName =
            properties
                .getProperty(
                    "${prefix}stream-name",
                    id
                )
                .trim()

        val host =
            properties
                .getProperty(
                    "${prefix}host",
                    ""
                )
                .trim()

        val port =
            properties
                .getProperty(
                    "${prefix}port",
                    "8800"
                )
                .trim()
                .toIntOrNull()
                ?: 8800

        val deviceId =
            properties
                .getProperty(
                    "${prefix}device-id",
                    ""
                )
                .trim()

        val username =
            properties
                .getProperty(
                    "${prefix}username",
                    deviceId
                )
                .trim()

        val resolution =
            properties
                .getProperty(
                    "${prefix}resolution",
                    "high"
                )
                .trim()
                .uppercase()

        val reconnectDelayMs =
            properties
                .getProperty(
                    "${prefix}reconnect-delay-ms",
                    "5000"
                )
                .trim()
                .toLongOrNull()
                ?: 5000L

        val passwordEnv =
            properties
                .getProperty(
                    "${prefix}password-env"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val storedPasswordConfigured =
            !properties
                .getProperty(
                    "${prefix}password"
                )
                .isNullOrBlank()

        val explicitPasswordEnvironmentAvailable =
            passwordEnv
                ?.let {
                    !System.getenv(
                        it
                    )
                        .isNullOrBlank()
                }
                ?: false

        val automaticPasswordEnvironment =
            cameraEnvironmentName(
                id,
                "password"
            )

        val automaticPasswordEnvironmentAvailable =
            !System.getenv(
                automaticPasswordEnvironment
            )
                .isNullOrBlank()

        val environmentOverrides =
            environmentAwareFields
                .mapNotNull { field ->

                    val environment =
                        cameraEnvironmentName(
                            id,
                            field
                        )

                    if (
                        !System.getenv(
                            environment
                        )
                            .isNullOrBlank()
                    ) {
                        environment
                    } else {
                        null
                    }
                }

        return EditableCameraDefinition(
            id =
                id,

            enabled =
                enabled,

            streamName =
                streamName,

            host =
                host,

            port =
                port,

            deviceId =
                deviceId,

            username =
                username,

            resolution =
                resolution,

            reconnectDelayMs =
                reconnectDelayMs,

            passwordConfigured =
                storedPasswordConfigured ||
                        explicitPasswordEnvironmentAvailable ||
                        automaticPasswordEnvironmentAvailable,

            passwordEnv =
                passwordEnv,

            automaticPasswordEnvironmentAvailable =
                automaticPasswordEnvironmentAvailable,

            environmentOverrides =
                environmentOverrides
        )
    }

    private fun validateUpdate(
        update: CameraConfigUpdate
    ) {

        validateCameraId(
            update.id
        )

        require(
            update.streamName.matches(
                cameraIdRegex
            )
        ) {
            "Nom de flux RTSP invalide."
        }

        require(
            update.host.isNotBlank()
        ) {
            "Adresse de caméra absente."
        }

        require(
            update.port in
                    1..65535
        ) {
            "Port caméra invalide."
        }

        require(
            update.deviceId.isNotBlank()
        ) {
            "Device ID absent."
        }

        require(
            update.username.isNotBlank()
        ) {
            "Nom d'utilisateur absent."
        }

        require(
            update.resolution.equals(
                "LOW",
                ignoreCase = true
            ) ||
                    update.resolution.equals(
                        "HIGH",
                        ignoreCase = true
                    )
        ) {
            "Résolution invalide."
        }

        require(
            update.reconnectDelayMs >=
                    1_000L
        ) {
            "Le délai de reconnexion doit être d'au moins 1000 ms."
        }
    }

    private fun validateCameraId(
        cameraId: String
    ) {

        require(
            cameraId.matches(
                cameraIdRegex
            )
        ) {
            "Identifiant caméra invalide."
        }
    }

    private fun validateResultingConfiguration(
        content: String
    ) {

        val properties =
            Properties()

        properties.load(
            StringReader(
                content
            )
        )

        val cameraIds =
            properties
                .stringPropertyNames()
                .mapNotNull { key ->

                    cameraKeyRegex
                        .matchEntire(
                            key
                        )
                        ?.groupValues
                        ?.get(
                            1
                        )
                }
                .toSortedSet()

        val activeStreams =
            mutableMapOf<String, String>()

        for (
        id in
        cameraIds
        ) {

            validateCameraId(
                id
            )

            val prefix =
                "camera.$id."

            val enabled =
                properties
                    .getProperty(
                        "${prefix}enabled",
                        "true"
                    )
                    .trim()
                    .toBooleanStrictOrNull()
                    ?: error(
                        "${prefix}enabled doit valoir true ou false."
                    )

            if (!enabled) {
                continue
            }

            val stream =
                properties
                    .getProperty(
                        "${prefix}stream-name",
                        id
                    )
                    .trim()

            require(
                stream.matches(
                    cameraIdRegex
                )
            ) {
                "Nom de flux invalide pour '$id'."
            }

            val previousCamera =
                activeStreams.putIfAbsent(
                    stream,
                    id
                )

            require(
                previousCamera == null
            ) {
                "Les caméras '$previousCamera' et '$id' utilisent le même flux '$stream'."
            }
        }
    }

    private fun rewriteCameraProperties(
        original: String,
        cameraId: String,
        updates: LinkedHashMap<String, String?>,
        appendNewBlock: Boolean
    ): String {

        val lineEnding =
            if (
                original.contains(
                    "\r\n"
                )
            ) {
                "\r\n"
            } else {
                "\n"
            }

        val hadFinalNewline =
            original.endsWith(
                "\n"
            )

        val lines =
            original
                .split(
                    Regex(
                        """\r?\n"""
                    )
                )
                .toMutableList()

        if (
            hadFinalNewline &&
            lines.isNotEmpty() &&
            lines.last().isEmpty()
        ) {
            lines.removeAt(
                lines.lastIndex
            )
        }

        val prefix =
            "camera.$cameraId."

        val lastCameraLine =
            lines.indexOfLast { line ->

                val key =
                    propertyKeyFromPhysicalLine(
                        line
                    )

                key != null &&
                        key.startsWith(
                            prefix
                        )
            }

        val written =
            mutableSetOf<String>()

        val output =
            mutableListOf<String>()

        for (
        index in
        lines.indices
        ) {

            val line =
                lines[index]

            val key =
                propertyKeyFromPhysicalLine(
                    line
                )

            if (
                key != null &&
                key.startsWith(
                    prefix
                ) &&
                updates.containsKey(
                    key
                )
            ) {

                val value =
                    updates[key]

                if (
                    value != null
                ) {

                    output.add(
                        "$key=${escapePropertyValue(value)}"
                    )
                }

                written.add(
                    key
                )

            } else {

                output.add(
                    line
                )
            }

            if (
                index == lastCameraLine
            ) {

                for (
                (missingKey, missingValue) in
                updates
                ) {

                    if (
                        missingKey !in written &&
                        missingValue != null
                    ) {

                        output.add(
                            "$missingKey=${escapePropertyValue(missingValue)}"
                        )

                        written.add(
                            missingKey
                        )
                    }
                }
            }
        }

        if (
            appendNewBlock ||
            lastCameraLine < 0
        ) {

            if (
                output.isNotEmpty() &&
                output.last().isNotBlank()
            ) {
                output.add(
                    ""
                )
            }

            output.add(
                "# Caméra $cameraId"
            )

            for (
            (key, value) in
            updates
            ) {

                if (
                    value != null &&
                    key !in written
                ) {

                    output.add(
                        "$key=${escapePropertyValue(value)}"
                    )

                    written.add(
                        key
                    )
                }
            }
        }

        return output.joinToString(
            lineEnding
        ) +
                if (
                    hadFinalNewline ||
                    appendNewBlock
                ) {
                    lineEnding
                } else {
                    ""
                }
    }

    private fun propertyKeyFromPhysicalLine(
        line: String
    ): String? {

        val trimmed =
            line.trimStart()

        if (
            trimmed.isEmpty() ||
            trimmed.startsWith(
                "#"
            ) ||
            trimmed.startsWith(
                "!"
            )
        ) {
            return null
        }

        return physicalPropertyRegex
            .matchEntire(
                line
            )
            ?.groupValues
            ?.get(
                1
            )
    }

    private fun escapePropertyValue(
        value: String
    ): String {

        /*
         * AppConfig charge application.properties avec Properties.load(InputStream),
         * donc le format attendu est ISO-8859-1 avec séquences \\uXXXX.
         *
         * On écrit nous-mêmes uniquement les valeurs modifiées afin de préserver
         * les commentaires et le formatage du reste du fichier.
         */
        val result =
            StringBuilder(
                value.length
            )

        for (
        character in
        value
        ) {

            when (
                character
            ) {

                '\\' ->
                    result.append(
                        "\\\\"
                    )

                '\r' ->
                    result.append(
                        "\\r"
                    )

                '\n' ->
                    result.append(
                        "\\n"
                    )

                '\t' ->
                    result.append(
                        "\\t"
                    )

                else -> {

                    if (
                        character.code < 0x20 ||
                        character.code > 0x7E
                    ) {

                        result.append(
                            "\\u"
                        )

                        result.append(
                            character.code
                                .toString(
                                    16
                                )
                                .uppercase()
                                .padStart(
                                    4,
                                    '0'
                                )
                        )

                    } else {

                        result.append(
                            character
                        )
                    }
                }
            }
        }

        return result.toString()
    }

    private fun loadProperties(
        path: Path
    ): Properties {

        val properties =
            Properties()

        Files.newInputStream(
            path
        )
            .buffered()
            .use {
                properties.load(
                    it
                )
            }

        return properties
    }

    private fun requireEditablePath():
            Path {

        val path =
            externalConfigPath()
                ?: error(
                    "La configuration embarquée dans le JAR n'est pas modifiable. " +
                            "Lancez Xiaovv avec -Dxiaovv.config=... ou XIAOVV_CONFIG."
                )

        require(
            Files.exists(
                path
            )
        ) {
            "Fichier de configuration introuvable."
        }

        require(
            Files.isRegularFile(
                path
            )
        ) {
            "Le chemin de configuration n'est pas un fichier."
        }

        require(
            Files.isWritable(
                path
            )
        ) {
            "Le fichier de configuration n'est pas accessible en écriture."
        }

        return path
    }

    private fun externalConfigPath():
            Path? {

        /*
         * 1. Même priorité qu'AppConfig :
         *    propriété JVM puis variable d'environnement.
         */
        val configured =
            System.getProperty(
                "xiaovv.config"
            )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: System.getenv(
                    "XIAOVV_CONFIG"
                )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

        if (
            configured != null
        ) {

            return Path.of(
                configured
            )
                .toAbsolutePath()
                .normalize()
        }

        /*
         * 2. Exécution depuis IntelliJ / Gradle.
         *
         * Dans ce cas AppConfig peut charger application.properties
         * depuis le classpath sans que xiaovv.config soit défini.
         *
         * On cherche volontairement d'abord le fichier SOURCE du projet
         * afin de ne pas modifier build/resources/main/application.properties,
         * qui serait écrasé au prochain build.
         */
        val projectSource =
            Path.of(
                System.getProperty(
                    "user.dir"
                ),
                "src",
                "main",
                "resources",
                "application.properties"
            )
                .toAbsolutePath()
                .normalize()

        if (
            Files.isRegularFile(
                projectSource
            )
        ) {
            return projectSource
        }

        /*
         * 3. Distribution portable lancée avec application.properties
         *    dans le répertoire courant.
         */
        val workingDirectoryConfig =
            Path.of(
                System.getProperty(
                    "user.dir"
                ),
                "application.properties"
            )
                .toAbsolutePath()
                .normalize()

        if (
            Files.isRegularFile(
                workingDirectoryConfig
            )
        ) {
            return workingDirectoryConfig
        }

        /*
         * 4. Dernier recours : ressource classpath uniquement si elle
         *    correspond réellement à un fichier modifiable sur disque.
         *
         * Une ressource contenue dans un JAR a le protocole "jar" :
         * elle reste donc volontairement en lecture seule.
         */
        val resource =
            Thread.currentThread()
                .contextClassLoader
                .getResource(
                    "application.properties"
                )

        if (
            resource != null &&
            resource.protocol.equals(
                "file",
                ignoreCase = true
            )
        ) {

            try {

                val classpathFile =
                    Path.of(
                        resource.toURI()
                    )
                        .toAbsolutePath()
                        .normalize()

                if (
                    Files.isRegularFile(
                        classpathFile
                    )
                ) {
                    return classpathFile
                }

            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun writeAtomically(
        path: Path,
        content: String
    ) {

        val absolute =
            path.toAbsolutePath()
                .normalize()

        val parent =
            absolute.parent
                ?: error(
                    "Répertoire de configuration invalide."
                )

        val backup =
            absolute.resolveSibling(
                absolute.fileName
                    .toString() +
                        ".bak"
            )

        val temporary =
            Files.createTempFile(
                parent,
                absolute.fileName
                    .toString() +
                        ".",
                ".tmp"
            )

        try {

            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8
            )

            /*
             * Backup de la dernière version valide AVANT remplacement.
             */
            Files.copy(
                absolute,
                backup,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES
            )

            try {

                Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )

            } catch (_: AtomicMoveNotSupportedException) {

                Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }

        } finally {

            try {
                Files.deleteIfExists(
                    temporary
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun effectiveCameraValue(
        properties: Properties,
        id: String,
        key: String,
        default: String? = null
    ): String {

        val environment =
            cameraEnvironmentName(
                id,
                key
            )

        val environmentValue =
            System.getenv(
                environment
            )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        if (
            environmentValue != null
        ) {
            return environmentValue
        }

        val property =
            "camera.$id.$key"

        val propertyValue =
            properties
                .getProperty(
                    property
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        if (
            propertyValue != null
        ) {
            return propertyValue
        }

        return default
            ?: error(
                "Configuration absente : $property"
            )
    }

    private fun cameraEnvironmentName(
        id: String,
        key: String
    ): String {

        val cameraPart =
            id
                .uppercase()
                .replace(
                    '-',
                    '_'
                )

        val keyPart =
            key
                .uppercase()
                .replace(
                    '-',
                    '_'
                )

        return "XIAOVV_CAMERA_${cameraPart}_${keyPart}"
    }
}
