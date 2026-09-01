package fr.nachidel.xiaovv

import fr.nachidel.xiaovv.logging.logger
import java.io.File
import java.util.Properties

private val log =
    logger<AppConfig>()

data class AppConfig(
    val rtsp: RtspConfig,
    val api: ApiConfig,
    val cameras: List<CameraConfig>
) {

    companion object {

        private val cameraKeyRegex =
            Regex(
                """^camera\.([^.]+)\..+$"""
            )

        fun load(): AppConfig {

            val properties =
                Properties()

            /*
             * ====================================================
             * FICHIER DE CONFIGURATION
             * ====================================================
             */

            val externalConfig =
                System.getProperty(
                    "xiaovv.config"
                )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: System.getenv(
                        "XIAOVV_CONFIG"
                    )
                        ?.takeIf {
                            it.isNotBlank()
                        }

            if (externalConfig != null) {

                val file =
                    File(
                        externalConfig
                    )

                require(
                    file.exists()
                ) {
                    "Fichier de configuration introuvable : ${file.absolutePath}"
                }

                file.inputStream()
                    .buffered()
                    .use {
                        properties.load(
                            it
                        )
                    }

                log.info(
                    "Configuration chargée depuis {}",
                    file.absolutePath
                )

            } else {

                val resource =
                    Thread.currentThread()
                        .contextClassLoader
                        .getResourceAsStream(
                            "application.properties"
                        )
                        ?: error(
                            "application.properties introuvable"
                        )

                resource.use {
                    properties.load(
                        it
                    )
                }

                log.info(
                    "Configuration chargée depuis application.properties"
                )
            }

            /*
             * ====================================================
             * RTSP
             * ====================================================
             */

            val rtsp =
                RtspConfig(

                    bindAddress =
                        value(
                            properties,
                            property =
                                "rtsp.bind-address",
                            environment =
                                "XIAOVV_RTSP_BIND",
                            default =
                                "0.0.0.0"
                        ),

                    port =
                        value(
                            properties,
                            property =
                                "rtsp.port",
                            environment =
                                "XIAOVV_RTSP_PORT",
                            default =
                                "8555"
                        )
                            .toInt()
                )

            require(
                rtsp.port in 1..65535
            ) {
                "Port RTSP invalide : ${rtsp.port}"
            }

            /*
             * ====================================================
             * API HTTP
             * ====================================================
             */

            val apiTokenEnvironment =
                properties
                    .getProperty(
                        "api.token-env"
                    )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "XIAOVV_API_TOKEN"

            /*
             * Priorité :
             *
             * 1. variable d'environnement
             * 2. api.token dans properties
             *
             * On recommande évidemment la variable d'environnement.
             */
            val apiToken =
                System.getenv(
                    apiTokenEnvironment
                )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: properties
                        .getProperty(
                            "api.token"
                        )
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                    ?: error(
                        "Jeton API absent. " +
                                "Définir $apiTokenEnvironment " +
                                "ou api.token dans application.properties"
                    )

            require(
                apiToken.length >= 16
            ) {
                "Le jeton API doit contenir au moins 16 caractères"
            }

            val api =
                ApiConfig(

                    bindAddress =
                        value(
                            properties,
                            property =
                                "api.bind-address",
                            environment =
                                "XIAOVV_API_BIND",
                            default =
                                "127.0.0.1"
                        ),

                    port =
                        value(
                            properties,
                            property =
                                "api.port",
                            environment =
                                "XIAOVV_API_PORT",
                            default =
                                "8080"
                        )
                            .toInt(),

                    token =
                        apiToken
                )

            require(
                api.port in 1..65535
            ) {
                "Port API invalide : ${api.port}"
            }

            /*
             * ====================================================
             * CAMÉRAS
             * ====================================================
             */

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

            if (
                cameraIds.isEmpty()
            ) {

                log.warn(
                    "Aucune caméra définie"
                )
            }

            val cameras =
                cameraIds
                    .mapNotNull { id ->

                        val enabled =
                            cameraValue(
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
                                    "camera.$id.enabled doit valoir true ou false"
                                )

                        if (!enabled) {

                            log.info(
                                "Caméra '{}' désactivée",
                                id
                            )

                            null

                        } else {

                            buildCameraConfig(
                                properties =
                                    properties,

                                id =
                                    id
                            )
                        }
                    }

            /*
             * Pas deux caméras avec le même chemin RTSP.
             */
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

            log.info(
                "{} caméra(s) activée(s)",
                cameras.size
            )

            for (
            camera in
            cameras
            ) {

                log.info(
                    "Caméra '{}' : {}:{} -> /{} ({})",
                    camera.id,
                    camera.host,
                    camera.port,
                    camera.streamName,
                    camera.resolution.name.lowercase()
                )
            }

            log.info(
                "RTSP : {}:{}",
                rtsp.bindAddress,
                rtsp.port
            )

            log.info(
                "API HTTP : {}:{}",
                api.bindAddress,
                api.port
            )

            /*
             * Ne jamais logger api.token.
             */

            return AppConfig(
                rtsp =
                    rtsp,

                api =
                    api,

                cameras =
                    cameras
            )
        }

        /*
         * ========================================================
         * CAMÉRA
         * ========================================================
         */

        private fun buildCameraConfig(
            properties: Properties,
            id: String
        ): CameraConfig {

            require(
                id.matches(
                    Regex(
                        """[A-Za-z0-9_-]+"""
                    )
                )
            ) {
                "Identifiant caméra invalide : '$id'"
            }

            val streamName =
                cameraValue(
                    properties =
                        properties,

                    id =
                        id,

                    key =
                        "stream-name",

                    default =
                        id
                )

            require(
                streamName.matches(
                    Regex(
                        """[A-Za-z0-9_-]+"""
                    )
                )
            ) {
                "Nom de flux RTSP invalide : '$streamName'"
            }

            val host =
                cameraValue(
                    properties,
                    id,
                    "host"
                )

            val port =
                cameraValue(
                    properties,
                    id,
                    "port",
                    "8800"
                )
                    .toInt()

            require(
                port in 1..65535
            ) {
                "Port invalide pour '$id' : $port"
            }

            val deviceId =
                cameraValue(
                    properties,
                    id,
                    "device-id"
                )

            val username =
                cameraValue(
                    properties,
                    id,
                    "username",
                    deviceId
                )

            /*
             * ====================================================
             * MOT DE PASSE CAMÉRA
             * ====================================================
             */

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
                    id =
                        id,

                    key =
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
                        "Mot de passe absent pour la caméra '$id'. " +
                                "Définir $automaticPasswordEnv"
                    )

            val resolution =
                CameraResolution.from(
                    cameraValue(
                        properties,
                        id,
                        "resolution",
                        "high"
                    )
                )

            val reconnectDelayMs =
                cameraValue(
                    properties,
                    id,
                    "reconnect-delay-ms",
                    "5000"
                )
                    .toLong()

            require(
                reconnectDelayMs >= 1_000
            ) {
                "Délai de reconnexion trop faible pour '$id'"
            }

            return CameraConfig(
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
        }

        /*
         * ========================================================
         * VALEUR GLOBALE
         * ========================================================
         */

        private fun value(
            properties: Properties,
            property: String,
            environment: String,
            default: String? = null
        ): String {

            val envValue =
                System.getenv(
                    environment
                )
                    ?.trim()

            if (
                !envValue.isNullOrBlank()
            ) {
                return envValue
            }

            val propertyValue =
                properties
                    .getProperty(
                        property
                    )
                    ?.trim()

            if (
                !propertyValue.isNullOrBlank()
            ) {
                return propertyValue
            }

            return default
                ?: error(
                    "Configuration absente : $property"
                )
        }

        /*
         * ========================================================
         * VALEUR CAMÉRA
         * ========================================================
         */

        private fun cameraValue(
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

            val envValue =
                System.getenv(
                    environment
                )
                    ?.trim()

            if (
                !envValue.isNullOrBlank()
            ) {
                return envValue
            }

            val property =
                "camera.$id.$key"

            val propertyValue =
                properties
                    .getProperty(
                        property
                    )
                    ?.trim()

            if (
                !propertyValue.isNullOrBlank()
            ) {
                return propertyValue
            }

            return default
                ?: error(
                    "Configuration absente : $property"
                )
        }

        /*
         * ========================================================
         * VARIABLE ENVIRONNEMENT CAMÉRA
         * ========================================================
         */

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
}

/*
 * ================================================================
 * RTSP
 * ================================================================
 */

data class RtspConfig(
    val bindAddress: String,
    val port: Int
)

/*
 * ================================================================
 * API
 * ================================================================
 */

data class ApiConfig(
    val bindAddress: String,
    val port: Int,
    val token: String
)

/*
 * ================================================================
 * CAMERA
 * ================================================================
 */

data class CameraConfig(
    val id: String,
    val enabled: Boolean,
    val streamName: String,
    val host: String,
    val port: Int,
    val deviceId: String,
    val username: String,
    val password: String,
    val resolution: CameraResolution,
    val reconnectDelayMs: Long
)

/*
 * ================================================================
 * RÉSOLUTION
 * ================================================================
 */

enum class CameraResolution {

    LOW,
    HIGH;

    companion object {

        fun from(
            value: String
        ): CameraResolution {

            return when (
                value
                    .trim()
                    .lowercase()
            ) {

                "low",
                "sd" ->
                    LOW

                "high",
                "hd" ->
                    HIGH

                else ->
                    error(
                        "Résolution inconnue : '$value'"
                    )
            }
        }
    }
}