package fr.nachidel.xiaovv

import com.sun.net.httpserver.HttpExchange
import fr.nachidel.xiaovv.logging.logger
import java.io.Closeable
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Routes HTTP du module MQTT.
 *
 * CameraApiServer appelle ce module après son contrôle X-API-Token commun,
 * donc toutes les routes /api/mqtt/ restent protégées comme les caméras.
 */
class MqttFeature : Closeable {

    private val log =
        logger<MqttFeature>()

    private val config =
        MqttConfigFileManager()

    private val runtime =
        MqttRuntimeManager(
            config
        )

    fun start() {
        runtime.start()
    }

    /**
     * @return true quand la route appartenait au module MQTT.
     */
    fun handle(
        exchange: HttpExchange,
        path: String
    ): Boolean {
        when {
            path ==
            "/api/mqtt/servers" -> {
                if (
                    !exchange.requestMethod.equals(
                        "GET",
                        ignoreCase = true
                    )
                ) {
                    methodNotAllowed(
                        exchange
                    )
                } else {
                    sendServers(
                        exchange
                    )
                }

                return true
            }

            path ==
            "/api/mqtt/servers/save" -> {
                if (
                    !exchange.requestMethod.equals(
                        "POST",
                        ignoreCase = true
                    )
                ) {
                    methodNotAllowed(
                        exchange
                    )
                } else {
                    saveServer(
                        exchange
                    )
                }

                return true
            }

            path.startsWith(
                "/api/mqtt/servers/"
            ) -> {
                if (
                    !exchange.requestMethod.equals(
                        "DELETE",
                        ignoreCase = true
                    )
                ) {
                    methodNotAllowed(
                        exchange
                    )
                } else {
                    deleteServer(
                        exchange,
                        path.substringAfterLast(
                            '/'
                        )
                    )
                }

                return true
            }

            path ==
            "/api/mqtt/value" -> {
                if (
                    !exchange.requestMethod.equals(
                        "GET",
                        ignoreCase = true
                    )
                ) {
                    methodNotAllowed(
                        exchange
                    )
                } else {
                    sendValue(
                        exchange
                    )
                }

                return true
            }

            else ->
                return false
        }
    }

    private fun sendServers(
        exchange: HttpExchange
    ) {
        try {
            val snapshot =
                config.snapshot()

            val brokersJson =
                snapshot.brokers
                    .joinToString(
                        prefix = "[",
                        postfix = "]",
                        separator = ","
                    ) { broker ->
                        """
                        {
                          "id": "${jsonEscape(broker.id)}",
                          "name": "${jsonEscape(broker.name)}",
                          "host": "${jsonEscape(broker.host)}",
                          "port": ${broker.port},
                          "tls": ${broker.tls},
                          "enabled": ${broker.enabled},
                          "username": ${nullableJsonString(broker.username)},
                          "passwordConfigured": ${broker.passwordConfigured},
                          "passwordEnv": ${nullableJsonString(broker.passwordEnv)},
                          "passwordEnvironment": "${jsonEscape(broker.passwordEnvironment)}",
                          "activePasswordEnvironment": ${nullableJsonString(broker.activePasswordEnvironment)},
                          "passwordFromEnvironment": ${broker.passwordFromEnvironment},
                          "passwordStoredInFile": ${broker.passwordStoredInFile},
                          "connected": ${runtime.connected(broker.id)}
                        }
                        """.trimIndent()
                    }

            sendJson(
                exchange,
                200,
                """
                {
                  "success": true,
                  "path": "${jsonEscape(snapshot.path)}",
                  "editable": ${snapshot.editable},
                  "brokers": $brokersJson
                }
                """.trimIndent()
            )
        } catch (e: Exception) {
            sendJson(
                exchange,
                500,
                jsonError(
                    e.message
                        ?: "Impossible de lire la configuration MQTT."
                )
            )
        }
    }

    private fun saveServer(
        exchange: HttpExchange
    ) {
        try {
            val body =
                readSmallFormBody(
                    exchange
                )

            val id =
                requiredFormParameter(
                    body,
                    "id"
                )

            val name =
                requiredFormParameter(
                    body,
                    "name"
                )

            val host =
                requiredFormParameter(
                    body,
                    "host"
                )

            val port =
                requiredFormParameter(
                    body,
                    "port"
                )
                    .toIntOrNull()
                    ?: error(
                        "Port MQTT invalide."
                    )

            val passwordMode =
                when (
                    formParameter(
                        body,
                        "passwordMode"
                    )
                        ?.trim()
                        ?.lowercase()
                ) {
                    "set" ->
                        MqttConfigFileManager.PasswordMode.SET

                    "clear" ->
                        MqttConfigFileManager.PasswordMode.CLEAR

                    else ->
                        MqttConfigFileManager.PasswordMode.KEEP
                }

            config.save(
                MqttConfigFileManager.SaveRequest(
                    previousId = formParameter(
                        body,
                        "previousId"
                    ),
                    id = id,
                    name = name,
                    host = host,
                    port = port,
                    tls = formBoolean(
                        body,
                        "tls"
                    ),
                    enabled = formBoolean(
                        body,
                        "enabled",
                        default = true
                    ),
                    username = formParameter(
                        body,
                        "username"
                    ),
                    passwordEnv = formParameter(
                        body,
                        "passwordEnv"
                    ),
                    passwordMode = passwordMode,
                    password = formParameter(
                        body,
                        "password"
                    )
                )
            )

            runtime.reload()

            sendJson(
                exchange,
                200,
                """
                {
                  "success": true
                }
                """.trimIndent()
            )
        } catch (e: Exception) {
            log.warn(
                "Enregistrement MQTT impossible : {}",
                e.message
            )

            sendJson(
                exchange,
                400,
                jsonError(
                    e.message
                        ?: "Configuration MQTT invalide."
                )
            )
        }
    }

    private fun deleteServer(
        exchange: HttpExchange,
        id: String
    ) {
        try {
            config.delete(
                id
            )

            runtime.reload()

            sendJson(
                exchange,
                200,
                """
                {
                  "success": true
                }
                """.trimIndent()
            )
        } catch (e: Exception) {
            sendJson(
                exchange,
                400,
                jsonError(
                    e.message
                        ?: "Suppression MQTT impossible."
                )
            )
        }
    }

    private fun sendValue(
        exchange: HttpExchange
    ) {
        val serverId =
            queryParameter(
                exchange,
                "server"
            )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val topic =
            queryParameter(
                exchange,
                "topic"
            )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        if (
            serverId == null ||
            topic == null
        ) {
            sendJson(
                exchange,
                400,
                jsonError(
                    "Paramètres server et topic obligatoires."
                )
            )

            return
        }

        try {
            val value =
                runtime.value(
                    serverId,
                    topic
                )

            sendJson(
                exchange,
                200,
                """
                {
                  "success": true,
                  "connected": ${value.connected},
                  "payload": ${nullableJsonString(value.payload)},
                  "receivedAt": ${value.receivedAt?.toString() ?: "null"}
                }
                """.trimIndent()
            )
        } catch (e: IllegalArgumentException) {
            sendJson(
                exchange,
                400,
                jsonError(
                    e.message
                        ?: "Source MQTT invalide."
                )
            )
        } catch (e: Exception) {
            sendJson(
                exchange,
                500,
                jsonError(
                    e.message
                        ?: "Lecture MQTT impossible."
                )
            )
        }
    }

    private fun formBoolean(
        body: String,
        name: String,
        default: Boolean = false
    ): Boolean {
        val raw =
            formParameter(
                body,
                name
            )
                ?: return default

        return raw.equals(
            "true",
            ignoreCase = true
        ) ||
        raw.equals(
            "on",
            ignoreCase = true
        ) ||
        raw ==
        "1"
    }

    private fun readSmallFormBody(
        exchange: HttpExchange
    ): String {
        val contentLength =
            exchange.requestHeaders
                .getFirst(
                    "Content-Length"
                )
                ?.toLongOrNull()

        if (
            contentLength != null &&
            contentLength > 32_768L
        ) {
            error(
                "Requête trop volumineuse."
            )
        }

        val bytes =
            exchange.requestBody.use { input ->
                input.readNBytes(
                    32_769
                )
            }

        if (
            bytes.size > 32_768
        ) {
            error(
                "Requête trop volumineuse."
            )
        }

        return String(
            bytes,
            StandardCharsets.UTF_8
        )
    }

    private fun requiredFormParameter(
        body: String,
        name: String
    ): String {
        return formParameter(
            body,
            name
        )
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: error(
                "Paramètre absent : $name"
            )
    }

    private fun formParameter(
        body: String,
        name: String
    ): String? {
        for (
        part in
        body.split(
            "&"
        )
        ) {
            if (
                part.isBlank()
            ) {
                continue
            }

            val separator =
                part.indexOf(
                    '='
                )

            val rawName =
                if (
                    separator >= 0
                ) {
                    part.substring(
                        0,
                        separator
                    )
                } else {
                    part
                }

            val rawValue =
                if (
                    separator >= 0
                ) {
                    part.substring(
                        separator + 1
                    )
                } else {
                    ""
                }

            val decodedName =
                URLDecoder.decode(
                    rawName,
                    StandardCharsets.UTF_8
                )

            if (
                decodedName != name
            ) {
                continue
            }

            return URLDecoder.decode(
                rawValue,
                StandardCharsets.UTF_8
            )
        }

        return null
    }

    private fun queryParameter(
        exchange: HttpExchange,
        name: String
    ): String? {
        val rawQuery =
            exchange.requestURI
                .rawQuery
                ?: return null

        for (
        part in
        rawQuery.split(
            "&"
        )
        ) {
            if (
                part.isBlank()
            ) {
                continue
            }

            val separator =
                part.indexOf(
                    '='
                )

            val rawName =
                if (
                    separator >= 0
                ) {
                    part.substring(
                        0,
                        separator
                    )
                } else {
                    part
                }

            val rawValue =
                if (
                    separator >= 0
                ) {
                    part.substring(
                        separator + 1
                    )
                } else {
                    ""
                }

            val decodedName =
                URLDecoder.decode(
                    rawName,
                    StandardCharsets.UTF_8
                )

            if (
                decodedName != name
            ) {
                continue
            }

            return URLDecoder.decode(
                rawValue,
                StandardCharsets.UTF_8
            )
        }

        return null
    }

    private fun methodNotAllowed(
        exchange: HttpExchange
    ) {
        sendJson(
            exchange,
            405,
            jsonError(
                "Méthode HTTP non autorisée."
            )
        )
    }

    private fun nullableJsonString(
        value: String?
    ): String {
        return if (
            value == null
        ) {
            "null"
        } else {
            "\"" +
            jsonEscape(
                value
            ) +
            "\""
        }
    }

    private fun jsonError(
        message: String
    ): String {
        return """
        {
          "success": false,
          "error": "${jsonEscape(message)}"
        }
        """.trimIndent()
    }

    private fun jsonEscape(
        value: String
    ): String {
        return value
            .replace(
                "\\",
                "\\\\"
            )
            .replace(
                "\"",
                "\\\""
            )
            .replace(
                "\r",
                "\\r"
            )
            .replace(
                "\n",
                "\\n"
            )
            .replace(
                "\t",
                "\\t"
            )
    }

    private fun sendJson(
        exchange: HttpExchange,
        status: Int,
        json: String
    ) {
        val bytes =
            json.toByteArray(
                StandardCharsets.UTF_8
            )

        exchange.responseHeaders.set(
            "Content-Type",
            "application/json; charset=UTF-8"
        )

        exchange.responseHeaders.set(
            "Cache-Control",
            "no-store"
        )

        exchange.sendResponseHeaders(
            status,
            bytes.size.toLong()
        )

        exchange.responseBody.use { output ->
            output.write(
                bytes
            )
        }
    }

    override fun close() {
        runtime.close()
    }
}
