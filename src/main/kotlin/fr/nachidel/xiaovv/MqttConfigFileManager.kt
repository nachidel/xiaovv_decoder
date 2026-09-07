package fr.nachidel.xiaovv

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * Configuration globale des serveurs MQTT.
 *
 * Les bulles du dashboard ne mémorisent que l'identifiant du serveur et
 * le topic. L'hôte, le port et les identifiants restent centralisés ici.
 */
class MqttConfigFileManager {

    data class BrokerConfig(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val tls: Boolean,
        val enabled: Boolean,
        val username: String?,
        val password: String?,
        val passwordEnv: String?,
        val automaticPasswordEnv: String,
        val activePasswordEnv: String?,
        val passwordStoredInFile: Boolean
    )

    data class BrokerView(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val tls: Boolean,
        val enabled: Boolean,
        val username: String?,
        val passwordConfigured: Boolean,
        val passwordEnv: String?,
        val passwordEnvironment: String,
        val activePasswordEnvironment: String?,
        val passwordFromEnvironment: Boolean,
        val passwordStoredInFile: Boolean
    )

    data class Snapshot(
        val path: String,
        val editable: Boolean,
        val brokers: List<BrokerView>
    )

    enum class PasswordMode {
        KEEP,
        SET,
        CLEAR
    }

    data class SaveRequest(
        val previousId: String?,
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val tls: Boolean,
        val enabled: Boolean,
        val username: String?,
        val passwordEnv: String?,
        val passwordMode: PasswordMode,
        val password: String?
    )

    fun snapshot(): Snapshot {
        val path = configPath()

        return Snapshot(
            path = path.toString(),
            editable = isEditable(path),
            brokers = loadAll(path).map { broker ->
                BrokerView(
                    id = broker.id,
                    name = broker.name,
                    host = broker.host,
                    port = broker.port,
                    tls = broker.tls,
                    enabled = broker.enabled,
                    username = broker.username,
                    passwordConfigured = !broker.password.isNullOrEmpty(),
                    passwordEnv = broker.passwordEnv,
                    passwordEnvironment =
                        broker.activePasswordEnv
                            ?: broker.passwordEnv
                            ?: broker.automaticPasswordEnv,
                    activePasswordEnvironment = broker.activePasswordEnv,
                    passwordFromEnvironment =
                        broker.activePasswordEnv != null,
                    passwordStoredInFile =
                        broker.passwordStoredInFile
                )
            }
        )
    }

    fun loadAll(): List<BrokerConfig> =
        loadAll(
            configPath()
        )

    fun loadEnabled(): List<BrokerConfig> =
        loadAll()
            .filter {
                it.enabled
            }

    @Synchronized
    fun save(
        request: SaveRequest
    ) {
        validate(
            request
        )

        val path =
            configPath()

        ensureWritableLocation(
            path
        )

        val properties =
            loadPropertiesIfExists(
                path
            )

        val previousId =
            request.previousId
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val oldConfig =
            previousId
                ?.let {
                    readBroker(
                        properties,
                        it,
                        resolveEnvironment =
                            false
                    )
                }
                ?: readBroker(
                    properties,
                    request.id.trim(),
                    resolveEnvironment =
                        false
                )

        if (
            previousId != null &&
            previousId != request.id.trim()
        ) {
            removeBrokerProperties(
                properties,
                previousId
            )
        }

        val password =
            when (
                request.passwordMode
            ) {
                PasswordMode.KEEP ->
                    oldConfig?.password

                PasswordMode.SET ->
                    request.password
                        ?.takeIf {
                            it.isNotEmpty()
                        }

                PasswordMode.CLEAR ->
                    null
            }

        val passwordEnv =
            request.passwordEnv
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val username =
            request.username
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        if (
            !password.isNullOrEmpty() &&
            username.isNullOrEmpty()
        ) {
            error(
                "Un mot de passe MQTT nécessite un nom d'utilisateur."
            )
        }

        val broker =
            BrokerConfig(
                id = request.id.trim(),
                name = request.name.trim(),
                host = request.host.trim(),
                port = request.port,
                tls = request.tls,
                enabled = request.enabled,
                username = username,
                password = password,
                passwordEnv = passwordEnv,
                automaticPasswordEnv =
                    automaticPasswordEnvironmentName(
                        request.id.trim()
                    ),
                activePasswordEnv = null,
                passwordStoredInFile =
                    !password.isNullOrEmpty()
            )

        writeBroker(
            properties,
            broker
        )

        val ids =
            brokerIds(
                properties
            )
                .filter {
                    it != previousId
                }
                .toMutableList()

        if (
            !ids.contains(
                broker.id
            )
        ) {
            ids.add(
                broker.id
            )
        }

        properties.setProperty(
            "mqtt.ids",
            ids
                .distinct()
                .joinToString(
                    ","
                )
        )

        persist(
            path,
            properties
        )
    }

    @Synchronized
    fun delete(
        id: String
    ) {
        val normalized =
            id.trim()

        requireValidId(
            normalized
        )

        val path =
            configPath()

        ensureWritableLocation(
            path
        )

        val properties =
            loadPropertiesIfExists(
                path
            )

        val idsBeforeDelete =
            brokerIds(
                properties
            )

        removeBrokerProperties(
            properties,
            normalized
        )

        properties.setProperty(
            "mqtt.ids",
            idsBeforeDelete
                .filter {
                    it != normalized
                }
                .joinToString(
                    ","
                )
        )

        persist(
            path,
            properties
        )
    }

    private fun loadAll(
        path: Path
    ): List<BrokerConfig> {
        val properties =
            loadPropertiesIfExists(
                path
            )

        return brokerIds(
            properties
        )
            .mapNotNull { id ->
                readBroker(
                    properties,
                    id
                )
            }
    }

    private fun brokerIds(
        properties: Properties
    ): List<String> {
        val declared =
            properties.getProperty(
                "mqtt.ids",
                ""
            )
                .split(',')
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }

        if (
            declared.isNotEmpty()
        ) {
            return declared.distinct()
        }

        return properties
            .stringPropertyNames()
            .mapNotNull { key ->
                Regex(
                    """^mqtt\.([A-Za-z0-9_-]+)\.host$"""
                )
                    .matchEntire(
                        key
                    )
                    ?.groupValues
                    ?.getOrNull(
                        1
                    )
            }
            .distinct()
            .sorted()
    }

    private fun readBroker(
        properties: Properties,
        id: String,
        resolveEnvironment: Boolean =
            true
    ): BrokerConfig? {
        if (
            id.isBlank()
        ) {
            return null
        }

        val prefix =
            "mqtt.$id."

        val host =
            properties.getProperty(
                prefix + "host"
            )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val name =
            properties.getProperty(
                prefix + "name",
                id
            )
                .trim()
                .ifBlank {
                    id
                }

        val port =
            properties.getProperty(
                prefix + "port",
                "1883"
            )
                .trim()
                .toIntOrNull()
                ?: 1883

        val username =
            properties.getProperty(
                prefix + "username"
            )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val storedPassword =
            properties.getProperty(
                prefix + "password"
            )
                ?.takeIf {
                    it.isNotEmpty()
                }

        val explicitPasswordEnv =
            properties.getProperty(
                prefix + "password-env"
            )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val automaticPasswordEnv =
            automaticPasswordEnvironmentName(
                id
            )

        val explicitEnvironmentPassword =
            if (
                resolveEnvironment
            ) {
                explicitPasswordEnv
                    ?.let {
                        System.getenv(
                            it
                        )
                    }
                    ?.takeIf {
                        it.isNotBlank()
                    }
            } else {
                null
            }

        val automaticEnvironmentPassword =
            if (
                resolveEnvironment
            ) {
                System.getenv(
                    automaticPasswordEnv
                )
                    ?.takeIf {
                        it.isNotBlank()
                    }
            } else {
                null
            }

        val activePasswordEnv =
            when {
                explicitEnvironmentPassword != null ->
                    explicitPasswordEnv

                automaticEnvironmentPassword != null ->
                    automaticPasswordEnv

                else ->
                    null
            }

        val password =
            if (
                resolveEnvironment
            ) {
                explicitEnvironmentPassword
                    ?: automaticEnvironmentPassword
                    ?: storedPassword
            } else {
                storedPassword
            }

        if (
            !password.isNullOrEmpty() &&
            username.isNullOrBlank()
        ) {
            error(
                "Le serveur MQTT '$id' possède un mot de passe mais aucun nom d'utilisateur."
            )
        }

        return BrokerConfig(
            id = id,
            name = name,
            host = host,
            port = port,
            tls = properties.getProperty(
                prefix + "tls",
                "false"
            )
                .trim()
                .toBooleanStrictOrNull()
                ?: false,
            enabled = properties.getProperty(
                prefix + "enabled",
                "true"
            )
                .trim()
                .toBooleanStrictOrNull()
                ?: true,
            username = username,
            password = password,
            passwordEnv = explicitPasswordEnv,
            automaticPasswordEnv = automaticPasswordEnv,
            activePasswordEnv = activePasswordEnv,
            passwordStoredInFile =
                !storedPassword.isNullOrEmpty()
        )
    }

    private fun writeBroker(
        properties: Properties,
        broker: BrokerConfig
    ) {
        val prefix =
            "mqtt.${broker.id}."

        properties.setProperty(
            prefix + "name",
            broker.name
        )

        properties.setProperty(
            prefix + "host",
            broker.host
        )

        properties.setProperty(
            prefix + "port",
            broker.port.toString()
        )

        properties.setProperty(
            prefix + "tls",
            broker.tls.toString()
        )

        properties.setProperty(
            prefix + "enabled",
            broker.enabled.toString()
        )

        if (
            broker.username.isNullOrBlank()
        ) {
            properties.remove(
                prefix + "username"
            )
        } else {
            properties.setProperty(
                prefix + "username",
                broker.username
            )
        }

        if (
            broker.passwordEnv.isNullOrBlank()
        ) {
            properties.remove(
                prefix + "password-env"
            )
        } else {
            properties.setProperty(
                prefix + "password-env",
                broker.passwordEnv
            )
        }

        if (
            broker.password.isNullOrEmpty()
        ) {
            properties.remove(
                prefix + "password"
            )
        } else {
            properties.setProperty(
                prefix + "password",
                broker.password
            )
        }
    }

    private fun removeBrokerProperties(
        properties: Properties,
        id: String
    ) {
        val prefix =
            "mqtt.$id."

        properties
            .stringPropertyNames()
            .filter {
                it.startsWith(
                    prefix
                )
            }
            .forEach {
                properties.remove(
                    it
                )
            }
    }

    private fun validate(
        request: SaveRequest
    ) {
        val id =
            request.id.trim()

        requireValidId(
            id
        )

        require(
            request.name.isNotBlank()
        ) {
            "Le nom du serveur MQTT est obligatoire."
        }

        require(
            request.host.isNotBlank()
        ) {
            "L'adresse du serveur MQTT est obligatoire."
        }

        require(
            request.port in 1..65535
        ) {
            "Le port MQTT doit être compris entre 1 et 65535."
        }

        request.previousId
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                requireValidId(
                    it
                )
            }

        request.passwordEnv
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let { environment ->
                require(
                    Regex(
                        """^[A-Za-z_][A-Za-z0-9_]*$"""
                    )
                        .matches(
                            environment
                        )
                ) {
                    "Nom de variable d'environnement MQTT invalide."
                }
            }

        if (
            request.passwordMode ==
            PasswordMode.SET
        ) {
            require(
                !request.password.isNullOrEmpty()
            ) {
                "Le nouveau mot de passe MQTT est vide."
            }
        }
    }

    private fun requireValidId(
        id: String
    ) {
        require(
            Regex(
                """^[A-Za-z0-9_-]+$"""
            )
                .matches(
                    id
                )
        ) {
            "L'identifiant MQTT ne peut contenir que lettres, chiffres, tiret et underscore."
        }
    }

    fun automaticPasswordEnvironmentName(
        id: String
    ): String {

        val normalized =
            id
                .trim()
                .uppercase()
                .map {
                    character ->

                    if (
                        character in 'A'..'Z' ||
                        character in '0'..'9'
                    ) {
                        character
                    } else {
                        '_'
                    }
                }
                .joinToString(
                    ""
                )

        return "XIAOVV_MQTT_${normalized}_PASSWORD"
    }

    private fun configPath(): Path {
        val explicit =
            System.getProperty(
                "xiaovv.mqtt.config"
            )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: System.getenv(
                    "XIAOVV_MQTT_CONFIG"
                )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

        if (
            explicit != null
        ) {
            return Path.of(
                explicit
            )
                .toAbsolutePath()
                .normalize()
        }

        val mainConfig =
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
            mainConfig != null
        ) {
            val mainPath =
                Path.of(
                    mainConfig
                )
                    .toAbsolutePath()
                    .normalize()

            mainPath.parent
                ?.let { parent ->
                    return parent.resolve(
                        "mqtt.properties"
                    )
                }
        }

        val projectSource =
            Path.of(
                System.getProperty(
                    "user.dir"
                ),
                "src",
                "main",
                "resources",
                "mqtt.properties"
            )
                .toAbsolutePath()
                .normalize()

        if (
            Files.isDirectory(
                projectSource.parent
            )
        ) {
            return projectSource
        }

        return Path.of(
            System.getProperty(
                "user.dir"
            ),
            "mqtt.properties"
        )
            .toAbsolutePath()
            .normalize()
    }

    private fun loadPropertiesIfExists(
        path: Path
    ): Properties {
        val properties =
            Properties()

        if (
            !Files.isRegularFile(
                path
            )
        ) {
            return properties
        }

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

    private fun ensureWritableLocation(
        path: Path
    ) {
        val parent =
            path.parent
                ?: error(
                    "Répertoire MQTT invalide."
                )

        Files.createDirectories(
            parent
        )

        if (
            Files.exists(
                path
            )
        ) {
            require(
                Files.isRegularFile(
                    path
                )
            ) {
                "Le chemin MQTT n'est pas un fichier."
            }

            require(
                Files.isWritable(
                    path
                )
            ) {
                "Le fichier MQTT n'est pas accessible en écriture."
            }
        } else {
            require(
                Files.isWritable(
                    parent
                )
            ) {
                "Le répertoire MQTT n'est pas accessible en écriture."
            }
        }
    }

    private fun isEditable(
        path: Path
    ): Boolean {
        return try {
            val parent =
                path.parent
                    ?: return false

            if (
                Files.exists(
                    path
                )
            ) {
                Files.isRegularFile(
                    path
                ) &&
                Files.isWritable(
                    path
                )
            } else {
                Files.createDirectories(
                    parent
                )

                Files.isWritable(
                    parent
                )
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun persist(
        path: Path,
        properties: Properties
    ) {
        ensureWritableLocation(
            path
        )

        if (
            Files.isRegularFile(
                path
            )
        ) {
            Files.copy(
                path,
                path.resolveSibling(
                    path.fileName.toString() +
                    ".bak"
                ),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        val parent =
            path.parent
                ?: error(
                    "Répertoire MQTT invalide."
                )

        val temp =
            Files.createTempFile(
                parent,
                "mqtt-",
                ".properties.tmp"
            )

        try {
            Files.newOutputStream(
                temp
            )
                .buffered()
                .use { output ->
                    properties.store(
                        output,
                        "Xiaovv MQTT configuration"
                    )
                }

            try {
                Files.move(
                    temp,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temp,
                    path,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            Files.deleteIfExists(
                temp
            )
        }
    }
}
