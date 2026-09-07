package fr.nachidel.xiaovv

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fr.nachidel.xiaovv.camera.CameraSupervisor
import fr.nachidel.xiaovv.logging.logger
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraApiServer(
    private val cameraRuntime: CameraRuntimeManager,
    private val castManager: CastManager,
    private val bindAddress: String,
    private val port: Int,
    private val apiToken: String,
    private val rtspPort: Int
) : Closeable {

    private val log =
        logger<CameraApiServer>()

    private val running =
        AtomicBoolean(false)

    private var server:
            HttpServer? =
        null

    private val executor =
        Executors.newCachedThreadPool()

    /*
     * Client utilisé uniquement par les tuiles "bouton".
     *
     * Les appels sont faits côté serveur Xiaovv afin d'éviter les
     * problèmes CORS du navigateur avec Jeedom ou d'autres services LAN.
     */
    private val cameraConfigFileManager =
        CameraConfigFileManager()

    /*
     * Module MQTT du dashboard : configuration des brokers,
     * abonnements et cache des dernières valeurs.
     */
    private val mqttFeature =
        MqttFeature()

    private val httpActionClient =
        HttpClient.newBuilder()
            .connectTimeout(
                Duration.ofSeconds(
                    5
                )
            )
            .followRedirects(
                HttpClient.Redirect.NORMAL
            )
            .build()

    fun start() {

        if (
            !running.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        try {

            mqttFeature.start()

            val httpServer =
                HttpServer.create(
                    InetSocketAddress(
                        bindAddress,
                        port
                    ),
                    0
                )

            httpServer.createContext(
                "/"
            ) { exchange ->

                handle(
                    exchange
                )
            }

            httpServer.executor =
                executor

            server =
                httpServer

            httpServer.start()

            log.info(
                "API caméras démarrée sur http://{}:{}",
                bindAddress,
                port
            )

            log.info(
                "Authentification API activée"
            )

        } catch (e: Exception) {

            try {
                mqttFeature.close()
            } catch (_: Exception) {
            }

            running.set(
                false
            )

            throw e
        }
    }

    private fun handle(
        exchange: HttpExchange
    ) {

        try {

            val requestOrigin =
                exchange.requestHeaders
                    .getFirst(
                        "Origin"
                    )
                    ?.takeIf {
                        it.isNotBlank()
                    }

            exchange.responseHeaders.set(
                "Access-Control-Allow-Origin",
                requestOrigin ?: "*"
            )

            exchange.responseHeaders.set(
                "Vary",
                "Origin"
            )

            exchange.responseHeaders.set(
                "Access-Control-Allow-Methods",
                "GET, HEAD, POST, DELETE, OPTIONS"
            )

            exchange.responseHeaders.set(
                "Access-Control-Allow-Headers",
                "Content-Type, X-API-Token, Authorization, Accept-Encoding, Range, Origin"
            )

            exchange.responseHeaders.set(
                "Access-Control-Expose-Headers",
                "Content-Type, Content-Length, Accept-Ranges, Content-Range"
            )

            if (
                exchange.requestMethod.equals(
                    "OPTIONS",
                    ignoreCase = true
                )
            ) {

                exchange.sendResponseHeaders(
                    204,
                    -1
                )

                return
            }

            val path =
                exchange.requestURI
                    .path
                    .trimEnd('/')

            if (
                path.isEmpty()
            ) {

                sendHtml(
                    exchange,
                    buildLivePage()
                )

                return
            }

            if (
                path ==
                "/live"
            ) {

                sendHtml(
                    exchange,
                    buildLivePage()
                )

                return
            }

            /*
             * Flux HLS temporaire destiné aux appareils Google Cast.
             *
             * Ce chemin n'utilise PAS le token API : le Chromecast ne
             * saurait pas envoyer notre en-tête X-API-Token. Le chemin
             * contient un identifiant de session aléatoire et non devinable.
             */
            if (
                path.startsWith(
                    "/cast-media/"
                )
            ) {

                if (
                    !exchange.requestMethod.equals(
                        "GET",
                        ignoreCase = true
                    ) &&
                    !exchange.requestMethod.equals(
                        "HEAD",
                        ignoreCase = true
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                castManager.handleMedia(
                    exchange
                )

                return
            }

            if (
                path.startsWith(
                    "/api/"
                ) &&
                !isAuthorized(
                    exchange
                )
            ) {

                sendJson(
                    exchange,
                    401,
                    """
                    {
                      "success": false,
                      "error": "unauthorized"
                    }
                    """.trimIndent()
                )

                return
            }

            /*
             * Routes MQTT protégées par le même token API que le reste.
             * MqttFeature renvoie false lorsqu'il ne reconnaît pas la route.
             */
            if (
                path.startsWith(
                    "/api/mqtt/"
                ) &&
                mqttFeature.handle(
                    exchange,
                    path
                )
            ) {
                return
            }

            if (
                path ==
                "/api/cast/devices"
            ) {

                if (
                    !isGet(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                sendCastDevices(
                    exchange
                )

                return
            }

            if (
                path ==
                "/api/cast/status"
            ) {

                if (
                    !isGet(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                sendCastStatus(
                    exchange
                )

                return
            }

            if (
                path ==
                "/api/cast/start"
            ) {

                if (
                    !exchange.requestMethod.equals(
                        "POST",
                        ignoreCase = true
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                handleStartCast(
                    exchange
                )

                return
            }

            if (
                path ==
                "/api/cast/stop"
            ) {

                if (
                    !exchange.requestMethod.equals(
                        "POST",
                        ignoreCase = true
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                handleStopCast(
                    exchange
                )

                return
            }

            if (
                path ==
                "/api/config/cameras"
            ) {

                if (
                    !isGet(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                sendCameraConfigSnapshot(
                    exchange
                )

                return
            }

            if (
                path ==
                "/api/config/cameras/save"
            ) {

                if (
                    !exchange.requestMethod.equals(
                        "POST",
                        ignoreCase = true
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                handleSaveCameraConfig(
                    exchange
                )

                return
            }

            if (
                path.startsWith(
                    "/api/config/cameras/"
                )
            ) {

                if (
                    !exchange.requestMethod.equals(
                        "DELETE",
                        ignoreCase = true
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                val cameraId =
                    path.substringAfterLast(
                        '/'
                    )

                handleDeleteCameraConfig(
                    exchange,
                    cameraId
                )

                return
            }

            if (
                path ==
                "/api/http-action"
            ) {

                if (
                    !exchange.requestMethod.equals(
                        "POST",
                        ignoreCase = true
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                handleHttpAction(
                    exchange
                )

                return
            }

            if (
                path ==
                "/api/cameras"
            ) {

                if (
                    !isGet(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                sendJson(
                    exchange,
                    200,
                    buildCameraListJson()
                )

                return
            }

            val segments =
                path
                    .trim('/')
                    .split('/')
                    .filter {
                        it.isNotBlank()
                    }

            if (
                segments.size < 4 ||
                segments[0] != "api" ||
                segments[1] != "cameras"
            ) {

                sendJson(
                    exchange,
                    404,
                    jsonError(
                        "Route inconnue"
                    )
                )

                return
            }

            val cameraId =
                segments[2]

            val camera =
                cameraRuntime.get(
                    cameraId
                )

            if (
                camera == null
            ) {

                sendJson(
                    exchange,
                    404,
                    jsonError(
                        "Caméra inconnue : $cameraId"
                    )
                )

                return
            }

            if (
                segments.size == 4 &&
                segments[3] == "status"
            ) {

                if (
                    !isGet(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                sendJson(
                    exchange,
                    200,
                    cameraStatusJson(
                        camera
                    )
                )

                return
            }

            if (
                segments.size == 4 &&
                segments[3] == "live.mjpeg"
            ) {

                if (
                    !isGet(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                streamLiveMjpeg(
                    exchange,
                    camera
                )

                return
            }

            if (
                segments.size == 4 &&
                segments[3] == "live.mp4"
            ) {

                if (
                    !isGet(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                streamLiveMp4(
                    exchange,
                    camera
                )

                return
            }

            if (
                segments.size == 4 &&
                segments[3] == "snapshot"
            ) {

                if (
                    !isGet(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                val snapshot =
                    camera.getLatestSnapshot()

                if (snapshot == null) {

                    sendJson(
                        exchange,
                        404,
                        jsonError(
                            "Aucune image disponible"
                        )
                    )

                    return
                }

                sendJpeg(
                    exchange,
                    snapshot,
                    camera.getLatestSnapshotTimestamp()
                )

                return
            }

            if (
                segments.size == 5 &&
                segments[3] == "ptz"
            ) {

                if (
                    !isGetOrPost(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                handlePtz(
                    exchange,
                    camera,
                    segments[4]
                )

                return
            }

            if (
                segments.size == 5 &&
                segments[3] == "light"
            ) {

                if (
                    !isGetOrPost(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                handleLight(
                    exchange,
                    camera,
                    segments[4]
                )

                return
            }

            if (
                segments.size == 5 &&
                segments[3] == "image"
            ) {

                if (
                    !isGetOrPost(
                        exchange
                    )
                ) {

                    methodNotAllowed(
                        exchange
                    )

                    return
                }

                handleImage(
                    exchange,
                    camera,
                    segments[4]
                )

                return
            }

            sendJson(
                exchange,
                404,
                jsonError(
                    "Route inconnue"
                )
            )

        } catch (e: Exception) {

            log.error(
                "Erreur API caméra",
                e
            )

            try {

                sendJson(
                    exchange,
                    500,
                    jsonError(
                        e.message
                            ?: "Erreur interne"
                    )
                )

            } catch (_: Exception) {
            }

        } finally {

            exchange.close()
        }
    }

    private fun isAuthorized(
        exchange: HttpExchange
    ): Boolean {

        val directToken =
            exchange.requestHeaders
                .getFirst(
                    "X-API-Token"
                )
                ?.trim()

        if (
            !directToken.isNullOrEmpty() &&
            tokenMatches(
                directToken
            )
        ) {
            return true
        }

        val authorization =
            exchange.requestHeaders
                .getFirst(
                    "Authorization"
                )
                ?.trim()

        if (
            authorization != null &&
            authorization.startsWith(
                "Bearer ",
                ignoreCase = true
            )
        ) {

            val bearerToken =
                authorization
                    .substring(
                        7
                    )
                    .trim()

            if (
                tokenMatches(
                    bearerToken
                )
            ) {
                return true
            }
        }

        /*
         * Un élément <video> ne permet pas d'envoyer X-API-Token.
         * Le mur vidéo transmet donc le jeton sur l'URL de son flux.
         */
        val queryToken =
            queryParameter(
                exchange,
                "token"
            )
                ?.trim()

        if (
            !queryToken.isNullOrEmpty() &&
            tokenMatches(
                queryToken
            )
        ) {
            return true
        }

        return false
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
        rawQuery.split("&")
        ) {

            if (
                part.isBlank()
            ) {
                continue
            }

            val separator =
                part.indexOf('=')

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

    private fun tokenMatches(
        suppliedToken: String
    ): Boolean {

        return MessageDigest.isEqual(
            apiToken.toByteArray(
                StandardCharsets.UTF_8
            ),
            suppliedToken.toByteArray(
                StandardCharsets.UTF_8
            )
        )
    }

    /*
     * ============================================================
     * MUR VIDÉO STABLE : RTSP XIAOVV -> MJPEG HTTP
     * ============================================================
     *
     * Le flux RTSP reste la source unique.
     *
     * Pour le mur vidéo, FFmpeg décode H.265 et produit uniquement
     * des JPEG successifs. Le navigateur les reçoit dans une réponse
     * multipart/x-mixed-replace.
     *
     * Avantages pour un mur multi-caméras :
     *
     * - quasiment aucune mise en mémoire tampon côté navigateur ;
     * - pas de conteneur MP4 fragmenté à resynchroniser ;
     * - redimensionner la tuile ne touche pas à la connexion ;
     * - fonctionnement identique dans Chrome / Edge / Firefox ;
     * - quand l'image est supprimée, la connexion HTTP et FFmpeg
     *   sont fermés, donc la demande RTSP disparaît.
     *
     * Ce mode est volontairement sans audio : plusieurs flux audio
     * simultanés sur un mur vidéo sont peu utiles et augmenteraient
     * fortement la complexité côté navigateur.
     */
    private fun streamLiveMjpeg(
        exchange: HttpExchange,
        camera: CameraSupervisor
    ) {

        val source =
            "rtsp://127.0.0.1:" +
                    rtspPort +
                    "/" +
                    camera.config.streamName

        val ffmpeg =
            System.getProperty(
                "xiaovv.ffmpeg"
            )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: System.getenv(
                    "XIAOVV_FFMPEG"
                )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: "ffmpeg"

        /*
         * 10 i/s et 960 px de large donnent un bon compromis pour
         * plusieurs caméras simultanées.
         *
         * On ne demande PAS à FFmpeg de produire un nouveau flux
         * H.264 : chaque image est immédiatement envoyée en JPEG.
         */
        val command =
            listOf(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-rtsp_transport",
                "tcp",
                "-analyzeduration",
                "500000",
                "-probesize",
                "1000000",
                "-i",
                source,
                "-an",
                "-vf",
                "fps=10,scale=960:-2:flags=fast_bilinear",
                "-c:v",
                "mjpeg",
                "-q:v",
                "5",
                "-f",
                "image2pipe",
                "pipe:1"
            )

        val process =
            try {

                ProcessBuilder(
                    command
                )
                    .redirectError(
                        ProcessBuilder.Redirect.DISCARD
                    )
                    .start()

            } catch (e: Exception) {

                log.warn(
                    "[{}] impossible de démarrer FFmpeg pour le mur vidéo : {}",
                    camera.config.id,
                    e.message
                )

                sendJson(
                    exchange,
                    503,
                    jsonError(
                        "FFmpeg indisponible pour le mur vidéo"
                    )
                )

                return
            }

        log.info(
            "[{}] mur vidéo MJPEG : ouverture RTSP local",
            camera.config.id
        )

        val boundary =
            "xiaovvframe"

        exchange.responseHeaders.set(
            "Content-Type",
            "multipart/x-mixed-replace; boundary=$boundary"
        )

        exchange.responseHeaders.set(
            "Cache-Control",
            "no-store, no-cache, must-revalidate, max-age=0"
        )

        exchange.responseHeaders.set(
            "Pragma",
            "no-cache"
        )

        exchange.responseHeaders.set(
            "X-Content-Type-Options",
            "nosniff"
        )

        /*
         * 0 avec HttpServer = réponse chunked.
         */
        exchange.sendResponseHeaders(
            200,
            0
        )

        try {

            process.inputStream.use {
                    input ->

                exchange.responseBody.use {
                        output ->

                    val readBuffer =
                        ByteArray(
                            64 * 1024
                        )

                    val jpeg =
                        java.io.ByteArrayOutputStream(
                            256 * 1024
                        )

                    var inJpeg =
                        false

                    var previous =
                        -1

                    while (
                        running.get()
                    ) {

                        val read =
                            input.read(
                                readBuffer
                            )

                        if (
                            read < 0
                        ) {
                            break
                        }

                        for (
                        index in
                        0 until read
                        ) {

                            val value =
                                readBuffer[index]
                                    .toInt() and 0xFF

                            if (
                                !inJpeg
                            ) {

                                /*
                                 * SOI : FF D8
                                 */
                                if (
                                    previous == 0xFF &&
                                    value == 0xD8
                                ) {

                                    jpeg.reset()

                                    jpeg.write(
                                        0xFF
                                    )

                                    jpeg.write(
                                        0xD8
                                    )

                                    inJpeg =
                                        true
                                }

                                previous =
                                    value

                                continue
                            }

                            jpeg.write(
                                value
                            )

                            /*
                             * EOI : FF D9
                             */
                            if (
                                previous == 0xFF &&
                                value == 0xD9
                            ) {

                                val frame =
                                    jpeg.toByteArray()

                                val header =
                                    (
                                            "--$boundary\r\n" +
                                                    "Content-Type: image/jpeg\r\n" +
                                                    "Content-Length: ${frame.size}\r\n" +
                                                    "Cache-Control: no-cache\r\n" +
                                                    "\r\n"
                                            ).toByteArray(
                                            StandardCharsets.US_ASCII
                                        )

                                output.write(
                                    header
                                )

                                output.write(
                                    frame
                                )

                                output.write(
                                    "\r\n".toByteArray(
                                        StandardCharsets.US_ASCII
                                    )
                                )

                                /*
                                 * Un flush PAR IMAGE, pas par paquet de
                                 * 64 ko : moins de jitter et moins de
                                 * surcharge HTTP.
                                 */
                                output.flush()

                                jpeg.reset()

                                inJpeg =
                                    false
                            }

                            previous =
                                value
                        }
                    }
                }
            }

        } catch (_: Exception) {

            /*
             * Normal lorsqu'une tuile disparaît ou que le navigateur
             * ferme l'onglet : la socket HTTP est alors interrompue.
             */

        } finally {

            try {
                process.destroy()
            } catch (_: Exception) {
            }

            try {

                if (
                    process.isAlive &&
                    !process.waitFor(
                        700,
                        java.util.concurrent.TimeUnit.MILLISECONDS
                    )
                ) {

                    process.destroyForcibly()
                }

            } catch (_: Exception) {

                try {
                    process.destroyForcibly()
                } catch (_: Exception) {
                }
            }

            log.info(
                "[{}] mur vidéo MJPEG : fermeture RTSP local",
                camera.config.id
            )
        }
    }

    /*
     * ============================================================
     * MUR VIDÉO : RTSP XIAOVV -> MP4 FRAGMENTÉ HTTP
     * ============================================================
     *
     * FFmpeg lit directement le serveur RTSP local de Xiaovv.
     * Il ne réencode rien : H.265 + AAC sont remuxés en MP4
     * fragmenté pour l'élément <video> du navigateur.
     */
    private fun streamLiveMp4(
        exchange: HttpExchange,
        camera: CameraSupervisor
    ) {

        val source =
            "rtsp://127.0.0.1:" +
                    rtspPort +
                    "/" +
                    camera.config.streamName

        val ffmpeg =
            System.getProperty(
                "xiaovv.ffmpeg"
            )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: System.getenv(
                    "XIAOVV_FFMPEG"
                )
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: "ffmpeg"

        val command =
            listOf(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-rtsp_transport",
                "tcp",
                "-fflags",
                "nobuffer",
                "-flags",
                "low_delay",
                "-i",
                source,
                "-map",
                "0:v:0",
                "-map",
                "0:a:0?",
                "-c:v",
                "copy",
                "-tag:v",
                "hvc1",
                "-c:a",
                "copy",
                "-movflags",
                "+empty_moov+default_base_moof+frag_keyframe",
                "-frag_duration",
                "1000000",
                "-f",
                "mp4",
                "pipe:1"
            )

        val process =
            try {

                ProcessBuilder(
                    command
                )
                    .redirectError(
                        ProcessBuilder.Redirect.DISCARD
                    )
                    .start()

            } catch (e: Exception) {

                log.warn(
                    "[{}] impossible de démarrer FFmpeg pour le mur vidéo : {}",
                    camera.config.id,
                    e.message
                )

                sendJson(
                    exchange,
                    503,
                    jsonError(
                        "FFmpeg indisponible pour le mur vidéo"
                    )
                )

                return
            }

        log.info(
            "[{}] mur vidéo : ouverture RTSP local",
            camera.config.id
        )

        exchange.responseHeaders.set(
            "Content-Type",
            "video/mp4"
        )

        exchange.responseHeaders.set(
            "Cache-Control",
            "no-store, no-cache, must-revalidate"
        )

        exchange.responseHeaders.set(
            "Pragma",
            "no-cache"
        )

        exchange.responseHeaders.set(
            "X-Content-Type-Options",
            "nosniff"
        )

        /*
         * Longueur 0 avec HttpServer = transfert chunked.
         */
        exchange.sendResponseHeaders(
            200,
            0
        )

        try {

            process.inputStream.use {
                    input ->

                exchange.responseBody.use {
                        output ->

                    val buffer =
                        ByteArray(
                            64 * 1024
                        )

                    while (
                        running.get()
                    ) {

                        val read =
                            input.read(
                                buffer
                            )

                        if (
                            read < 0
                        ) {
                            break
                        }

                        if (
                            read == 0
                        ) {
                            continue
                        }

                        output.write(
                            buffer,
                            0,
                            read
                        )

                        output.flush()
                    }
                }
            }

        } catch (_: Exception) {

            /*
             * Normal lorsqu'une tuile est retirée :
             * le navigateur ferme la connexion HTTP.
             */

        } finally {

            try {
                process.destroy()
            } catch (_: Exception) {
            }

            try {

                if (
                    process.isAlive &&
                    !process.waitFor(
                        700,
                        java.util.concurrent.TimeUnit.MILLISECONDS
                    )
                ) {

                    process.destroyForcibly()
                }

            } catch (_: Exception) {

                try {
                    process.destroyForcibly()
                } catch (_: Exception) {
                }
            }

            log.info(
                "[{}] mur vidéo : fermeture RTSP local",
                camera.config.id
            )
        }
    }

    /*
     * ============================================================
     * GOOGLE CAST
     * ============================================================
     */

    private fun sendCastDevices(
        exchange: HttpExchange
    ) {

        val devices =
            castManager.devices()

        val json =
            devices.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) { device ->

                """
                {
                  "name": "${jsonEscape(device.name)}",
                  "model": ${
                    device.model
                        ?.let {
                            "\"${jsonEscape(it)}\""
                        }
                        ?: "null"
                },
                  "address": "${jsonEscape(device.address)}",
                  "port": ${device.port}
                }
                """.trimIndent()
            }

        sendJson(
            exchange,
            200,
            json
        )
    }

    private fun sendCastStatus(
        exchange: HttpExchange
    ) {

        val sessions =
            castManager.activeSessions()

        val json =
            sessions.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) { session ->

                """
                {
                  "cameraId": "${jsonEscape(session.cameraId)}",
                  "deviceName": "${jsonEscape(session.deviceName)}",
                  "deviceAddress": "${jsonEscape(session.deviceAddress)}",
                  "active": ${session.active}
                }
                """.trimIndent()
            }

        sendJson(
            exchange,
            200,
            json
        )
    }

    private fun handleStartCast(
        exchange: HttpExchange
    ) {

        try {

            val body =
                readSmallFormBody(
                    exchange
                )

            val cameraId =
                requiredFormParameter(
                    body,
                    "cameraId"
                )

            val deviceAddress =
                requiredFormParameter(
                    body,
                    "deviceAddress"
                )

            val camera =
                cameraRuntime.get(
                    cameraId
                )
                    ?: error(
                        "Caméra inconnue : $cameraId"
                    )

            val session =
                castManager.startCast(
                    cameraId =
                        cameraId,

                    streamName =
                        camera.config.streamName,

                    deviceAddress =
                        deviceAddress
                )

            sendJson(
                exchange,
                200,
                """
                {
                  "success": true,
                  "cameraId": "${jsonEscape(session.cameraId)}",
                  "deviceName": "${jsonEscape(session.deviceName)}",
                  "deviceAddress": "${jsonEscape(session.deviceAddress)}"
                }
                """.trimIndent()
            )

        } catch (e: Exception) {

            log.warn(
                "Démarrage Cast impossible : {}",
                e.message
            )

            sendJson(
                exchange,
                502,
                jsonError(
                    e.message
                        ?: "Démarrage Cast impossible"
                )
            )
        }
    }

    private fun handleStopCast(
        exchange: HttpExchange
    ) {

        try {

            val body =
                readSmallFormBody(
                    exchange
                )

            val cameraId =
                requiredFormParameter(
                    body,
                    "cameraId"
                )

            val stopped =
                castManager.stopCast(
                    cameraId
                )

            sendJson(
                exchange,
                200,
                """
                {
                  "success": true,
                  "stopped": $stopped
                }
                """.trimIndent()
            )

        } catch (e: Exception) {

            sendJson(
                exchange,
                400,
                jsonError(
                    e.message
                        ?: "Arrêt Cast impossible"
                )
            )
        }
    }

    /*
     * ============================================================
     * CONFIGURATION DES CAMÉRAS
     * ============================================================
     */

    private fun sendCameraConfigSnapshot(
        exchange: HttpExchange
    ) {

        try {

            val snapshot =
                cameraConfigFileManager.snapshot()

            val camerasJson =
                snapshot.cameras
                    .joinToString(
                        prefix = "[",
                        postfix = "]",
                        separator = ","
                    ) { camera ->

                        val overrides =
                            camera.environmentOverrides
                                .joinToString(
                                    prefix = "[",
                                    postfix = "]",
                                    separator = ","
                                ) {
                                    "\"${jsonEscape(it)}\""
                                }

                        """
                        {
                          "id": "${jsonEscape(camera.id)}",
                          "enabled": ${camera.enabled},
                          "streamName": "${jsonEscape(camera.streamName)}",
                          "host": "${jsonEscape(camera.host)}",
                          "port": ${camera.port},
                          "deviceId": "${jsonEscape(camera.deviceId)}",
                          "username": "${jsonEscape(camera.username)}",
                          "resolution": "${jsonEscape(camera.resolution)}",
                          "reconnectDelayMs": ${camera.reconnectDelayMs},
                          "passwordConfigured": ${camera.passwordConfigured},
                          "passwordEnv": ${
                            camera.passwordEnv
                                ?.let {
                                    "\"${jsonEscape(it)}\""
                                }
                                ?: "null"
                        },
                          "automaticPasswordEnvironmentAvailable": ${camera.automaticPasswordEnvironmentAvailable},
                          "environmentOverrides": $overrides
                        }
                        """.trimIndent()
                    }

            sendJson(
                exchange,
                200,
                """
                {
                  "editable": ${snapshot.editable},
                  "path": ${
                    snapshot.path
                        ?.let {
                            "\"${jsonEscape(it)}\""
                        }
                        ?: "null"
                },
                  "cameras": $camerasJson
                }
                """.trimIndent()
            )

        } catch (e: Exception) {

            log.warn(
                "Lecture de la configuration des caméras impossible : {}",
                e.message
            )

            sendJson(
                exchange,
                500,
                jsonError(
                    e.message
                        ?: "Lecture de la configuration impossible"
                )
            )
        }
    }

    private fun handleSaveCameraConfig(
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

            val enabled =
                requiredFormParameter(
                    body,
                    "enabled"
                )
                    .toBooleanStrictOrNull()
                    ?: error(
                        "Valeur enabled invalide."
                    )

            val streamName =
                requiredFormParameter(
                    body,
                    "streamName"
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
                        "Port caméra invalide."
                    )

            val deviceId =
                requiredFormParameter(
                    body,
                    "deviceId"
                )

            val username =
                requiredFormParameter(
                    body,
                    "username"
                )

            val resolution =
                requiredFormParameter(
                    body,
                    "resolution"
                )

            val reconnectDelayMs =
                requiredFormParameter(
                    body,
                    "reconnectDelayMs"
                )
                    .toLongOrNull()
                    ?: error(
                        "Délai de reconnexion invalide."
                    )

            val passwordMode =
                requiredFormParameter(
                    body,
                    "passwordMode"
                )

            val password =
                formParameter(
                    body,
                    "password"
                )

            val passwordEnv =
                formParameter(
                    body,
                    "passwordEnv"
                )

            cameraConfigFileManager.save(
                CameraConfigUpdate(
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

                    passwordMode =
                        passwordMode,

                    password =
                        password,

                    passwordEnv =
                        passwordEnv
                )
            )

            try {

                val reload =
                    reloadCameraRuntime()

                sendJson(
                    exchange,
                    200,
                    cameraReloadSuccessJson(
                        reload
                    )
                )

            } catch (reloadError: Exception) {

                log.warn(
                    "Configuration caméra enregistrée mais rechargement à chaud impossible : {}",
                    reloadError.message
                )

                sendJson(
                    exchange,
                    200,
                    cameraReloadDeferredJson(
                        reloadError.message
                            ?: "Rechargement à chaud impossible"
                    )
                )
            }

        } catch (e: Exception) {

            log.warn(
                "Enregistrement de la configuration caméra impossible : {}",
                e.message
            )

            sendJson(
                exchange,
                400,
                jsonError(
                    e.message
                        ?: "Configuration caméra invalide"
                )
            )
        }
    }

    private fun handleDeleteCameraConfig(
        exchange: HttpExchange,
        cameraId: String
    ) {

        try {

            cameraConfigFileManager.delete(
                cameraId
            )

            try {

                val reload =
                    reloadCameraRuntime()

                sendJson(
                    exchange,
                    200,
                    cameraReloadSuccessJson(
                        reload
                    )
                )

            } catch (reloadError: Exception) {

                log.warn(
                    "Configuration caméra supprimée mais rechargement à chaud impossible : {}",
                    reloadError.message
                )

                sendJson(
                    exchange,
                    200,
                    cameraReloadDeferredJson(
                        reloadError.message
                            ?: "Rechargement à chaud impossible"
                    )
                )
            }

        } catch (e: Exception) {

            log.warn(
                "Suppression de la configuration caméra impossible : {}",
                e.message
            )

            sendJson(
                exchange,
                400,
                jsonError(
                    e.message
                        ?: "Suppression impossible"
                )
            )
        }
    }

    private fun reloadCameraRuntime():
            CameraRuntimeReloadResult {

        val desired =
            cameraConfigFileManager
                .loadEnabledCameraConfigs()

        val reload =
            cameraRuntime.reload(
                desired
            )

        /*
         * Une session Cast mémorise le nom de stream utilisé au départ.
         * Si la caméra a été modifiée ou supprimée, on arrête donc le
         * Cast associé pour ne pas laisser un watchdog relancer un ancien
         * chemin RTSP.
         */
        (
                reload.updated +
                        reload.removed
                )
            .distinct()
            .forEach {
                    cameraId ->

                castManager.stopCast(
                    cameraId
                )
            }

        return reload
    }

    private fun cameraReloadSuccessJson(
        reload: CameraRuntimeReloadResult
    ): String {

        fun listJson(
            values: List<String>
        ): String {

            return values.joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) {
                "\"${jsonEscape(it)}\""
            }
        }

        return """
        {
          "success": true,
          "runtimeApplied": true,
          "restartRequired": false,
          "added": ${listJson(reload.added)},
          "updated": ${listJson(reload.updated)},
          "removed": ${listJson(reload.removed)},
          "unchanged": ${listJson(reload.unchanged)}
        }
        """.trimIndent()
    }

    private fun cameraReloadDeferredJson(
        message: String
    ): String {

        return """
        {
          "success": true,
          "runtimeApplied": false,
          "restartRequired": true,
          "warning": "${jsonEscape(message)}"
        }
        """.trimIndent()
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
            exchange.requestBody.use {
                    input ->

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

    /*
     * ============================================================
     * TUILES BOUTON : appel HTTP générique
     * ============================================================
     *
     * Le navigateur envoie méthode + URL à Xiaovv.
     * Xiaovv exécute ensuite l'appel côté serveur.
     *
     * On ne journalise volontairement JAMAIS l'URL : elle peut contenir
     * une clé API Jeedom ou un autre secret dans sa query string.
     */
    private fun handleHttpAction(
        exchange: HttpExchange
    ) {

        val contentLength =
            exchange.requestHeaders
                .getFirst(
                    "Content-Length"
                )
                ?.toLongOrNull()

        if (
            contentLength != null &&
            contentLength > 16_384L
        ) {

            sendJson(
                exchange,
                413,
                jsonError(
                    "Requête trop volumineuse"
                )
            )

            return
        }

        val bodyBytes =
            exchange.requestBody.use {
                    input ->

                input.readNBytes(
                    16_385
                )
            }

        if (
            bodyBytes.size > 16_384
        ) {

            sendJson(
                exchange,
                413,
                jsonError(
                    "Requête trop volumineuse"
                )
            )

            return
        }

        val body =
            String(
                bodyBytes,
                StandardCharsets.UTF_8
            )

        val method =
            formParameter(
                body,
                "method"
            )
                ?.trim()
                ?.uppercase()
                ?: ""

        val targetUrl =
            formParameter(
                body,
                "url"
            )
                ?.trim()
                ?: ""

        val allowedMethods =
            setOf(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE"
            )

        if (
            method !in allowedMethods
        ) {

            sendJson(
                exchange,
                400,
                jsonError(
                    "Méthode HTTP non autorisée"
                )
            )

            return
        }

        if (
            targetUrl.isBlank()
        ) {

            sendJson(
                exchange,
                400,
                jsonError(
                    "URL absente"
                )
            )

            return
        }

        val uri =
            try {

                URI(
                    targetUrl
                )

            } catch (_: Exception) {

                sendJson(
                    exchange,
                    400,
                    jsonError(
                        "URL invalide"
                    )
                )

                return
            }

        if (
            uri.scheme == null ||
            (
                    !uri.scheme.equals(
                        "http",
                        ignoreCase = true
                    ) &&
                            !uri.scheme.equals(
                                "https",
                                ignoreCase = true
                            )
                    )
        ) {

            sendJson(
                exchange,
                400,
                jsonError(
                    "Seules les URL HTTP et HTTPS sont autorisées"
                )
            )

            return
        }

        val request =
            try {

                HttpRequest.newBuilder()
                    .uri(
                        uri
                    )
                    .timeout(
                        Duration.ofSeconds(
                            10
                        )
                    )
                    .header(
                        "User-Agent",
                        "Xiaovv-HTTP-Action/1.0"
                    )
                    .method(
                        method,
                        HttpRequest.BodyPublishers.noBody()
                    )
                    .build()

            } catch (_: Exception) {

                sendJson(
                    exchange,
                    400,
                    jsonError(
                        "Impossible de préparer la requête HTTP"
                    )
                )

                return
            }

        try {

            val response =
                httpActionClient.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
                )

            val remoteStatus =
                response.statusCode()

            if (
                remoteStatus in
                200..399
            ) {

                sendJson(
                    exchange,
                    200,
                    """
                    {
                      "success": true,
                      "status": $remoteStatus,
                      "method": "${jsonEscape(method)}"
                    }
                    """.trimIndent()
                )

            } else {

                sendJson(
                    exchange,
                    502,
                    """
                    {
                      "success": false,
                      "status": $remoteStatus,
                      "method": "${jsonEscape(method)}",
                      "error": "Le serveur distant a répondu HTTP $remoteStatus"
                    }
                    """.trimIndent()
                )
            }

        } catch (e: Exception) {

            log.warn(
                "Action HTTP {} impossible : {}",
                method,
                e.message
            )

            sendJson(
                exchange,
                502,
                jsonError(
                    "Impossible de joindre le serveur distant"
                )
            )
        }
    }

    private fun formParameter(
        body: String,
        name: String
    ): String? {

        for (
        part in
        body.split("&")
        ) {

            if (
                part.isBlank()
            ) {
                continue
            }

            val separator =
                part.indexOf('=')

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

    private fun handlePtz(
        exchange: HttpExchange,
        camera: CameraSupervisor,
        direction: String
    ) {

        val success =
            when (
                direction.lowercase()
            ) {

                "up" ->
                    camera.ptzUp()

                "down" ->
                    camera.ptzDown()

                "left" ->
                    camera.ptzLeft()

                "right" ->
                    camera.ptzRight()

                "stop" ->
                    camera.ptzStop()

                else -> {

                    sendJson(
                        exchange,
                        400,
                        jsonError(
                            "Direction PTZ inconnue : $direction"
                        )
                    )

                    return
                }
            }

        sendCommandResult(
            exchange,
            camera,
            "ptz/${direction.lowercase()}",
            success
        )

        if (
            success
        ) {

            log.info(
                "[{}] API PTZ {}",
                camera.config.id,
                direction.uppercase()
            )
        }
    }

    private fun handleLight(
        exchange: HttpExchange,
        camera: CameraSupervisor,
        mode: String
    ) {

        val success =
            when (
                mode.lowercase()
            ) {

                "on" ->
                    camera.lightOn()

                "off" ->
                    camera.lightOff()

                "auto" ->
                    camera.lightAuto()

                else -> {

                    sendJson(
                        exchange,
                        400,
                        jsonError(
                            "Mode lumière inconnu : $mode"
                        )
                    )

                    return
                }
            }

        sendCommandResult(
            exchange,
            camera,
            "light/${mode.lowercase()}",
            success
        )

        if (
            success
        ) {

            log.info(
                "[{}] API LUMIÈRE {}",
                camera.config.id,
                mode.uppercase()
            )
        }
    }

    private fun handleImage(
        exchange: HttpExchange,
        camera: CameraSupervisor,
        mode: String
    ) {

        val success =
            when (
                mode.lowercase()
            ) {

                "color" ->
                    camera.imageColor()

                "bw" ->
                    camera.imageBw()

                "auto" ->
                    camera.imageAuto()

                "flip" ->
                    camera.imageFlip()

                else -> {

                    sendJson(
                        exchange,
                        400,
                        jsonError(
                            "Mode image inconnu : $mode"
                        )
                    )

                    return
                }
            }

        sendCommandResult(
            exchange,
            camera,
            "image/${mode.lowercase()}",
            success
        )

        if (
            success
        ) {

            log.info(
                "[{}] API IMAGE {}",
                camera.config.id,
                mode.uppercase()
            )
        }
    }

    private fun sendCommandResult(
        exchange: HttpExchange,
        camera: CameraSupervisor,
        command: String,
        success: Boolean
    ) {

        if (
            success
        ) {

            sendJson(
                exchange,
                200,
                """
                {
                  "success": true,
                  "camera": "${jsonEscape(camera.config.id)}",
                  "command": "${jsonEscape(command)}"
                }
                """.trimIndent()
            )

            return
        }

        val state =
            camera.getState()

        val error =
            when (
                state
            ) {

                CameraSupervisor.CameraState.STANDBY ->
                    "Caméra en veille : ouvrez le flux RTSP avant d'envoyer une commande"

                CameraSupervisor.CameraState.UNAVAILABLE ->
                    "Caméra indisponible"

                CameraSupervisor.CameraState.ACTIVE ->
                    "Échec de la commande"
            }

        val status =
            when (
                state
            ) {

                CameraSupervisor.CameraState.STANDBY ->
                    409

                CameraSupervisor.CameraState.UNAVAILABLE ->
                    503

                CameraSupervisor.CameraState.ACTIVE ->
                    500
            }

        sendJson(
            exchange,
            status,
            """
            {
              "success": false,
              "camera": "${jsonEscape(camera.config.id)}",
              "state": "${state.apiValue}",
              "error": "${jsonEscape(error)}"
            }
            """.trimIndent()
        )
    }

    private fun buildCameraListJson():
            String {

        return cameraRuntime
            .snapshot()
            .joinToString(
                prefix = "[",
                postfix = "]",
                separator = ","
            ) { camera ->

                val state =
                    camera.getState()

                """
                {
                  "id": "${jsonEscape(camera.config.id)}",
                  "stream": "${jsonEscape(camera.config.streamName)}",
                  "running": ${camera.isRunning()},
                  "available": ${camera.isNetworkAvailable()},
                  "connected": ${camera.isConnected()},
                  "state": "${state.apiValue}",
                  "snapshotAvailable": ${camera.hasSnapshot()},
                  "snapshotTimestamp": ${camera.getLatestSnapshotTimestamp()}
                }
                """.trimIndent()
            }
    }

    private fun cameraStatusJson(
        camera: CameraSupervisor
    ): String {

        val state =
            camera.getState()

        return """
        {
          "id": "${jsonEscape(camera.config.id)}",
          "stream": "${jsonEscape(camera.config.streamName)}",
          "host": "${jsonEscape(camera.config.host)}",
          "running": ${camera.isRunning()},
          "available": ${camera.isNetworkAvailable()},
          "connected": ${camera.isConnected()},
          "state": "${state.apiValue}",
          "snapshotAvailable": ${camera.hasSnapshot()},
          "snapshotTimestamp": ${camera.getLatestSnapshotTimestamp()}
        }
        """.trimIndent()
    }

    private fun buildControlPage():
            String {

        return """
<!DOCTYPE html>
<html lang="fr">

<head>

<meta charset="UTF-8">

<meta
    name="viewport"
    content="width=device-width, initial-scale=1.0"
>

<title>Xiaovv - Caméras</title>

<style>

* {
    box-sizing: border-box;
}

body {
    margin: 0;
    padding: 24px;

    background: #15171a;
    color: #eeeeee;

    font-family:
        Arial,
        Helvetica,
        sans-serif;
}

.header {
    display: flex;

    justify-content: space-between;
    align-items: center;

    gap: 20px;

    margin-bottom: 25px;
}

h1 {
    margin: 0;
}

.subtitle {
    margin-top: 5px;
    color: #aaaaaa;
}

.header-actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
}

.token-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;

    padding: 10px 14px;

    border: 0;
    border-radius: 8px;

    background: #41464d;
    color: white;

    cursor: pointer;

    font: inherit;
    text-decoration: none;
}

#cameras {
    display: grid;

    grid-template-columns:
        repeat(
            auto-fit,
            minmax(320px, 1fr)
        );

    gap: 20px;
}

.camera {
    background: #23262a;

    border: 1px solid #33373c;
    border-radius: 12px;

    padding: 20px;
}

.camera-name {
    font-size: 22px;
    font-weight: bold;

    margin-bottom: 5px;
}

.status {
    margin-bottom: 20px;
    font-size: 14px;
    font-weight: bold;
}

.active {
    color: #70d88b;
}

.standby {
    color: #e2b84f;
}

.unavailable {
    color: #ef7777;
}

/*
 * NOTIFICATIONS TRANSITOIRES
 */

.toast-container {
    position: fixed;
    top: 18px;
    right: 18px;
    z-index: 10000;

    display: flex;
    flex-direction: column;
    align-items: flex-end;

    gap: 10px;

    pointer-events: none;
}

.toast {
    min-width: 280px;
    max-width: min(430px, calc(100vw - 36px));

    padding: 12px 15px;

    border-radius: 8px;
    border: 1px solid rgba(255, 255, 255, 0.12);

    background: #2b2f34;
    color: #f3f3f3;

    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.32);

    font-size: 14px;
    line-height: 1.35;

    opacity: 0;
    transform: translateY(-8px);

    transition:
        opacity 160ms ease,
        transform 160ms ease;
}

.toast.visible {
    opacity: 1;
    transform: translateY(0);
}

.toast.warning {
    border-left: 5px solid #e2b84f;
}

.toast.error {
    border-left: 5px solid #ef7777;
}

.toast.info {
    border-left: 5px solid #70aee8;
}

.toast-title {
    margin-bottom: 3px;
    font-weight: bold;
}

.toast-message {
    color: #d5d8dc;
}

/*
 * DERNIÈRE IMAGE
 */

.snapshot {
    position: relative;
    width: 100%;
    aspect-ratio: 16 / 9;
    margin-bottom: 18px;
    overflow: hidden;
    border-radius: 9px;
    background: #111315;
    border: 1px solid #33373c;
}

.snapshot img {
    width: 100%;
    height: 100%;
    display: none;
    object-fit: cover;
}

.snapshot-placeholder {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 15px;
    color: #777d84;
    font-size: 13px;
    text-align: center;
}

.snapshot-time {
    position: absolute;
    right: 8px;
    bottom: 7px;
    padding: 4px 7px;
    border-radius: 5px;
    background: rgba(0, 0, 0, 0.65);
    color: #eeeeee;
    font-size: 11px;
    display: none;
}

/*
 * PTZ
 */

.ptz {
    display: grid;

    grid-template-columns:
        70px 70px 70px;

    grid-template-rows:
        60px 60px 60px;

    justify-content: center;

    gap: 8px;
}

.ptz button {
    border: 0;
    border-radius: 9px;

    background: #41464d;
    color: white;

    font-size: 28px;

    cursor: pointer;

    user-select: none;
    touch-action: none;
}

.up {
    grid-column: 2;
    grid-row: 1;
}

.left {
    grid-column: 1;
    grid-row: 2;
}

.stop {
    grid-column: 2;
    grid-row: 2;

    font-size: 13px !important;

    background: #824444 !important;
}

.right {
    grid-column: 3;
    grid-row: 2;
}

.down {
    grid-column: 2;
    grid-row: 3;
}

.group-title {
    margin-top: 22px;
    margin-bottom: 10px;

    text-align: center;

    font-size: 12px;

    color: #999999;

    text-transform: uppercase;
}

.controls {
    display: flex;

    justify-content: center;
    flex-wrap: wrap;

    gap: 8px;
}

.controls button {
    padding: 10px 14px;

    border: 0;
    border-radius: 8px;

    background: #41464d;
    color: white;

    cursor: pointer;

    font-weight: bold;
}

.light-on {
    background: #8a762e !important;
}

.light-auto {
    background: #365d82 !important;
}

.image-color {
    background: #755f31 !important;
}

.image-bw {
    background: #55585c !important;
}

.image-auto {
    background: #365d82 !important;
}

.image-flip {
    background: #734c73 !important;
}

</style>

</head>

<body>

<div
    id="toast-container"
    class="toast-container"
></div>

<div class="header">

    <div>

        <h1>
            Xiaovv
        </h1>

        <div class="subtitle">
            Pilotage des caméras
        </div>

    </div>

    <div class="header-actions">

        <button
            class="token-button"
            type="button"
            onclick="openNamedPage('/live', 'xiaovv-live')"
        >
            Mur vidéo
        </button>

        <button
            class="token-button"
            onclick="changeToken()"
        >
            Jeton API
        </button>

    </div>

</div>

<div id="cameras">
    Chargement...
</div>

<script>

let activeCamera = null;
let activePtzMove = null;

const snapshotObjectUrls = {};

let lastToastKey = "";
let lastToastAt = 0;

/*
 * Cet onglet devient l'onglet de pilotage connu du navigateur.
 * Un clic depuis le mur vidéo pourra donc le retrouver et le
 * remettre au premier plan au lieu d'en ouvrir un autre.
 */
window.name =
    "xiaovv-control";

function openNamedPage(
    url,
    windowName
) {

    const target =
        window.open(
            url,
            windowName
        );

    if (target) {

        try {
            target.focus();
        } catch (_) {
        }
    }
}

function showToast(
    title,
    message,
    type
) {

    const now =
        Date.now();

    const key =
        type +
        "|" +
        title +
        "|" +
        message;

    /*
     * Évite le doublon typique PTZ :
     * POINTER DOWN échoue puis STOP échoue immédiatement après.
     */
    if (
        key === lastToastKey &&
        now - lastToastAt < 1500
    ) {
        return;
    }

    lastToastKey =
        key;

    lastToastAt =
        now;

    const container =
        document.getElementById(
            "toast-container"
        );

    if (!container) {
        return;
    }

    const toast =
        document.createElement(
            "div"
        );

    toast.className =
        "toast " +
        (
            type ||
            "info"
        );

    const toastTitle =
        document.createElement(
            "div"
        );

    toastTitle.className =
        "toast-title";

    toastTitle.textContent =
        title;

    const toastMessage =
        document.createElement(
            "div"
        );

    toastMessage.className =
        "toast-message";

    toastMessage.textContent =
        message;

    toast.appendChild(
        toastTitle
    );

    toast.appendChild(
        toastMessage
    );

    container.appendChild(
        toast
    );

    requestAnimationFrame(
        function() {

            toast.classList.add(
                "visible"
            );
        }
    );

    const removeToast =
        function() {

            toast.classList.remove(
                "visible"
            );

            setTimeout(
                function() {

                    toast.remove();
                },
                220
            );
        };

    setTimeout(
        removeToast,
        4500
    );
}

function commandErrorMessage(
    camera,
    payload,
    httpStatus
) {

    if (
        payload &&
        payload.state === "standby"
    ) {

        return {
            type: "warning",
            title: "Caméra " + camera,
            message:
                "La caméra est en ligne mais en veille. " +
                "Ouvrez son flux RTSP avant d'utiliser les commandes."
        };
    }

    if (
        payload &&
        payload.state === "unavailable"
    ) {

        return {
            type: "error",
            title: "Caméra " + camera,
            message:
                "La caméra est actuellement indisponible sur le réseau."
        };
    }

    return {
        type: "error",
        title: "Commande impossible",
        message:
            (
                payload &&
                payload.error
            )
                ? payload.error
                : "Erreur HTTP " + httpStatus
    };
}

function getToken() {

    let token =
        localStorage.getItem(
            "xiaovvApiToken"
        );

    if (!token) {

        token =
            window.prompt(
                "Jeton API Xiaovv :"
            ) || "";

        if (token) {

            localStorage.setItem(
                "xiaovvApiToken",
                token
            );
        }
    }

    return token;
}

function changeToken() {

    localStorage.removeItem(
        "xiaovvApiToken"
    );

    getToken();

    refresh();
}

async function apiFetch(
    url,
    options
) {

    options =
        options || {};

    options.headers =
        options.headers || {};

    options.headers[
        "X-API-Token"
    ] =
        getToken();

    const response =
        await fetch(
            url,
            options
        );

    if (
        response.status === 401
    ) {

        localStorage.removeItem(
            "xiaovvApiToken"
        );

        throw new Error(
            "Jeton API invalide"
        );
    }

    return response;
}

async function command(
    camera,
    type,
    commandName
) {

    try {

        const response =
            await apiFetch(
                "/api/cameras/" +
                encodeURIComponent(camera) +
                "/" +
                type +
                "/" +
                commandName,
                {
                    method: "POST"
                }
            );

        let payload =
            null;

        try {

            payload =
                await response.json();

        } catch (_) {
        }

        if (!response.ok) {

            console.error(
                payload ||
                (
                    "HTTP " +
                    response.status
                )
            );

            const notification =
                commandErrorMessage(
                    camera,
                    payload,
                    response.status
                );

            showToast(
                notification.title,
                notification.message,
                notification.type
            );

            /*
             * Actualise rapidement l'état affiché.
             */
            setTimeout(
                refresh,
                250
            );

            return false;
        }

        return true;

    } catch (e) {

        console.error(
            e
        );

        showToast(
            "Erreur de communication",
            e.message ||
                "Impossible de joindre l'API Xiaovv.",
            "error"
        );

        return false;
    }
}

async function startMove(
    camera,
    direction
) {

    const move = {
        camera: camera,
        direction: direction,
        released: false,
        started: false
    };

    activePtzMove =
        move;

    const success =
        await command(
            camera,
            "ptz",
            direction
        );

    /*
     * Un autre mouvement a pu commencer entre-temps.
     */
    if (
        activePtzMove !== move
    ) {
        return;
    }

    if (!success) {

        activePtzMove =
            null;

        activeCamera =
            null;

        return;
    }

    move.started =
        true;

    activeCamera =
        camera;

    /*
     * L'utilisateur peut avoir relâché le bouton pendant
     * l'aller-retour HTTP. Dans ce cas le MOVE vient juste
     * d'être accepté : il faut envoyer STOP maintenant.
     */
    if (
        move.released
    ) {

        await command(
            camera,
            "ptz",
            "stop"
        );

        if (
            activePtzMove === move
        ) {
            activePtzMove =
                null;
        }

        activeCamera =
            null;
    }
}

function stopMove() {

    const move =
        activePtzMove;

    if (
        move == null
    ) {
        return;
    }

    move.released =
        true;

    /*
     * Tant que la commande MOVE n'a pas été confirmée,
     * on n'envoie surtout pas STOP.
     *
     * Si MOVE échoue parce que la caméra est en veille,
     * cela évite les deux WARN identiques que l'on voyait.
     */
    if (
        !move.started
    ) {
        return;
    }

    activePtzMove =
        null;

    activeCamera =
        null;

    command(
        move.camera,
        "ptz",
        "stop"
    );
}

function makePtzButton(
    label,
    cssClass,
    camera,
    direction
) {

    const button =
        document.createElement(
            "button"
        );

    button.textContent =
        label;

    button.className =
        cssClass;

    button.addEventListener(
        "pointerdown",
        function(event) {

            event.preventDefault();

            startMove(
                camera,
                direction
            );
        }
    );

    button.addEventListener(
        "pointerup",
        function(event) {

            event.preventDefault();

            stopMove();
        }
    );

    button.addEventListener(
        "pointercancel",
        stopMove
    );

    return button;
}

function makeCommandButton(
    label,
    cssClass,
    camera,
    type,
    commandName
) {

    const button =
        document.createElement(
            "button"
        );

    button.textContent =
        label;

    button.className =
        cssClass;

    button.addEventListener(
        "click",
        function() {

            command(
                camera,
                type,
                commandName
            );
        }
    );

    return button;
}

function applyCameraState(
    status,
    camera
) {

    switch (
        camera.state
    ) {

        case "active":

            status.className =
                "status active";

            status.textContent =
                "● Diffusion active";

            break;

        case "standby":

            status.className =
                "status standby";

            status.textContent =
                "● En ligne — en veille";

            break;

        default:

            status.className =
                "status unavailable";

            status.textContent =
                "● Indisponible";

            break;
    }
}

async function loadSnapshot(
    image,
    placeholder,
    timeLabel,
    camera
) {

    if (!camera.snapshotAvailable) {
        placeholder.textContent =
            "Aucune image enregistrée";
        return;
    }

    try {

        const response =
            await apiFetch(
                "/api/cameras/" +
                encodeURIComponent(camera.id) +
                "/snapshot?ts=" +
                camera.snapshotTimestamp
            );

        if (!response.ok) {
            placeholder.textContent =
                "Image indisponible";
            return;
        }

        const blob =
            await response.blob();

        if (snapshotObjectUrls[camera.id]) {
            URL.revokeObjectURL(
                snapshotObjectUrls[camera.id]
            );
        }

        const objectUrl =
            URL.createObjectURL(blob);

        snapshotObjectUrls[camera.id] =
            objectUrl;

        image.src =
            objectUrl;

        image.style.display =
            "block";

        placeholder.style.display =
            "none";

        if (camera.snapshotTimestamp > 0) {
            const date =
                new Date(camera.snapshotTimestamp);

            timeLabel.textContent =
                date.toLocaleString();

            timeLabel.style.display =
                "block";
        }

    } catch (e) {
        placeholder.textContent =
            "Image indisponible";
    }
}

function buildCamera(
    camera
) {

    const card =
        document.createElement(
            "div"
        );

    card.className =
        "camera";

    const title =
        document.createElement(
            "div"
        );

    title.className =
        "camera-name";

    title.textContent =
        camera.id;

    const status =
        document.createElement(
            "div"
        );

    applyCameraState(
        status,
        camera
    );

    const snapshot =
        document.createElement(
            "div"
        );

    snapshot.className =
        "snapshot";

    const snapshotImage =
        document.createElement(
            "img"
        );

    snapshotImage.alt =
        "Dernière image de " + camera.id;

    const snapshotPlaceholder =
        document.createElement(
            "div"
        );

    snapshotPlaceholder.className =
        "snapshot-placeholder";

    snapshotPlaceholder.textContent =
        "Aucune image enregistrée";

    const snapshotTime =
        document.createElement(
            "div"
        );

    snapshotTime.className =
        "snapshot-time";

    snapshot.appendChild(snapshotImage);
    snapshot.appendChild(snapshotPlaceholder);
    snapshot.appendChild(snapshotTime);

    loadSnapshot(
        snapshotImage,
        snapshotPlaceholder,
        snapshotTime,
        camera
    );

    const ptz =
        document.createElement(
            "div"
        );

    ptz.className =
        "ptz";

    ptz.appendChild(
        makePtzButton(
            "▲",
            "up",
            camera.id,
            "up"
        )
    );

    ptz.appendChild(
        makePtzButton(
            "◀",
            "left",
            camera.id,
            "left"
        )
    );

    const stop =
        document.createElement(
            "button"
        );

    stop.className =
        "stop";

    stop.textContent =
        "STOP";

    stop.onclick =
        function() {

            command(
                camera.id,
                "ptz",
                "stop"
            );

            activeCamera =
                null;

            activePtzMove =
                null;
        };

    ptz.appendChild(
        stop
    );

    ptz.appendChild(
        makePtzButton(
            "▶",
            "right",
            camera.id,
            "right"
        )
    );

    ptz.appendChild(
        makePtzButton(
            "▼",
            "down",
            camera.id,
            "down"
        )
    );

    const lightTitle =
        document.createElement(
            "div"
        );

    lightTitle.className =
        "group-title";

    lightTitle.textContent =
        "Lumière";

    const light =
        document.createElement(
            "div"
        );

    light.className =
        "controls";

    light.appendChild(
        makeCommandButton(
            "ON",
            "light-on",
            camera.id,
            "light",
            "on"
        )
    );

    light.appendChild(
        makeCommandButton(
            "OFF",
            "",
            camera.id,
            "light",
            "off"
        )
    );

    light.appendChild(
        makeCommandButton(
            "AUTO",
            "light-auto",
            camera.id,
            "light",
            "auto"
        )
    );

    const imageTitle =
        document.createElement(
            "div"
        );

    imageTitle.className =
        "group-title";

    imageTitle.textContent =
        "Mode image";

    const image =
        document.createElement(
            "div"
        );

    image.className =
        "controls";

    image.appendChild(
        makeCommandButton(
            "COULEUR",
            "image-color",
            camera.id,
            "image",
            "color"
        )
    );

    image.appendChild(
        makeCommandButton(
            "N&B / IR",
            "image-bw",
            camera.id,
            "image",
            "bw"
        )
    );

    image.appendChild(
        makeCommandButton(
            "AUTO",
            "image-auto",
            camera.id,
            "image",
            "auto"
        )
    );

    image.appendChild(
        makeCommandButton(
            "↻ 180°",
            "image-flip",
            camera.id,
            "image",
            "flip"
        )
    );

    card.appendChild(
        title
    );

    card.appendChild(
        status
    );

    card.appendChild(
        snapshot
    );

    card.appendChild(
        ptz
    );

    card.appendChild(
        lightTitle
    );

    card.appendChild(
        light
    );

    card.appendChild(
        imageTitle
    );

    card.appendChild(
        image
    );

    return card;
}

async function refresh() {

    try {

        const response =
            await apiFetch(
                "/api/cameras"
            );

        if (!response.ok) {

            throw new Error(
                "HTTP " +
                response.status
            );
        }

        const cameras =
            await response.json();

        const container =
            document.getElementById(
                "cameras"
            );

        container.innerHTML =
            "";

        cameras.forEach(
            function(camera) {

                container.appendChild(
                    buildCamera(
                        camera
                    )
                );
            }
        );

    } catch (e) {

        document.getElementById(
            "cameras"
        ).textContent =
            e.message;
    }
}

window.addEventListener(
    "pointerup",
    stopMove
);

window.addEventListener(
    "pointercancel",
    stopMove
);

window.addEventListener(
    "blur",
    stopMove
);

refresh();

setInterval(
    refresh,
    5000
);

</script>

</body>

</html>
        """.trimIndent()
    }

    private fun buildLivePage():
            String {

        return """
<!DOCTYPE html>
<html lang="fr">

<head>

<meta charset="UTF-8">

<meta
    name="viewport"
    content="width=device-width, initial-scale=1.0"
>

<title>Xiaovv - Mur vidéo</title>

<style>

* {
    box-sizing: border-box;
}

html,
body {
    min-height: 100%;
}

body {
    margin: 0;
    padding: 18px;

    background: #15171a;
    color: #eeeeee;

    font-family:
        Arial,
        Helvetica,
        sans-serif;
}

.topbar {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    flex-wrap: wrap;

    gap: 14px;

    margin-bottom: 16px;
}

.title {
    min-width: 0;
}

h1 {
    margin: 0;
    font-size: 26px;
}

.subtitle {
    margin-top: 5px;
    color: #a8adb3;
    font-size: 13px;
}

.actions {
    display: flex;
    align-items: center;
    flex-wrap: wrap;

    gap: 9px;
}

/*
 * Mode "plein mur".
 *
 * Quand l'interface est masquée, seuls les flux restent visibles.
 * Un petit bouton discret en haut à droite permet de la réafficher.
 */
body.wall-only .topbar,
body.wall-only .selector {
    display: none;
}

body.wall-only {
    padding-top: 12px;
}

.interface-toggle {
    position: fixed;
    top: 12px;
    right: 12px;
    z-index: 11000;

    display: none;

    width: 42px;
    height: 42px;

    align-items: center;
    justify-content: center;

    border: 1px solid #3b4148;
    border-radius: 9px;

    background: rgba(32, 35, 40, 0.92);
    color: #eeeeee;

    font-size: 20px;
    line-height: 1;

    cursor: pointer;

    box-shadow:
        0 5px 16px rgba(0, 0, 0, 0.28);
}

body.wall-only .interface-toggle {
    display: flex;
}

.interface-toggle:hover {
    background: #353a40;
}

.button {
    display: inline-flex;
    align-items: center;
    justify-content: center;

    min-height: 40px;

    padding: 9px 13px;

    border: 0;
    border-radius: 8px;

    background: #41464d;
    color: #ffffff;

    font: inherit;
    font-size: 14px;
    text-decoration: none;

    cursor: pointer;
}

.button:hover {
    background: #4c525a;
}

.selector {
    margin-bottom: 18px;
    padding: 13px 15px;

    border: 1px solid #33373c;
    border-radius: 10px;

    background: #202328;
}

.selector-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;

    gap: 10px;

    margin-bottom: 11px;
}

.selector-title {
    font-weight: bold;
}

.selector-actions {
    display: flex;
    gap: 8px;
}

.selector-actions button {
    min-height: 34px;
    padding: 7px 10px;

    border: 0;
    border-radius: 7px;

    background: #353a40;
    color: #eeeeee;

    cursor: pointer;
}

.camera-choices {
    display: flex;
    align-items: center;
    flex-wrap: wrap;

    gap: 8px 14px;
}

.camera-choice {
    display: inline-flex;
    align-items: center;
    gap: 7px;

    min-height: 34px;

    cursor: pointer;
    user-select: none;
}

.camera-choice input {
    width: 18px;
    height: 18px;

    accent-color: #70aee8;
}

.camera-choice .state-dot {
    width: 8px;
    height: 8px;

    border-radius: 50%;
    flex: 0 0 auto;

    background: #777d84;
}

.camera-choice .state-dot.active {
    background: #70d88b;
}

.camera-choice .state-dot.standby {
    background: #e2b84f;
}

.camera-choice .state-dot.unavailable {
    background: #ef7777;
}

.hint {
    margin-top: 10px;

    color: #858b92;
    font-size: 12px;
}

.action-wall {
    display: flex;
    align-items: stretch;
    flex-wrap: wrap;

    gap: 14px;

    margin-bottom: 14px;
}

.action-wall:empty {
    display: none;
}

.action-tile {
    position: relative;

    display: flex;
    flex-direction: column;

    width: 250px;
    min-height: 128px;

    padding: 10px;

    border: 1px solid #3a3f45;
    border-radius: 10px;

    background: #202328;

    box-shadow:
        0 6px 18px rgba(0, 0, 0, 0.22);
}

.action-tile-header {
    display: flex;
    align-items: center;

    min-height: 30px;

    margin-bottom: 9px;
}

.action-method {
    padding: 3px 7px;

    border-radius: 999px;

    background: #353a40;
    color: #aeb4ba;

    font-size: 10px;
    font-weight: bold;
}

.action-spacer {
    flex: 1;
}

.action-icon-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;

    width: 31px;
    height: 29px;

    margin-left: 5px;

    border: 0;
    border-radius: 6px;

    background: transparent;
    color: #c9cdd1;

    font-size: 16px;

    cursor: pointer;
}

.action-icon-button:hover {
    background: #3a3f45;
    color: #ffffff;
}

.action-trigger {
    flex: 1;

    display: flex;
    align-items: center;
    justify-content: center;

    min-height: 68px;

    padding: 12px 16px;

    border: 1px solid #496b8d;
    border-radius: 9px;

    background:
        linear-gradient(
            180deg,
            #416b91,
            #315675
        );

    color: #ffffff;

    font-size: 17px;
    font-weight: bold;

    text-align: center;

    cursor: pointer;

    box-shadow:
        inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

.action-trigger:hover {
    filter: brightness(1.08);
}

.action-trigger:active {
    transform: translateY(1px);
}

.action-trigger.busy {
    opacity: 0.65;
    cursor: wait;
}

.wall {
    display: flex;
    align-items: flex-start;
    align-content: flex-start;
    flex-wrap: wrap;

    gap: 14px;

    min-height: 220px;
}

/*
 * MODALE DES TUILES BOUTON
 */

.action-modal-backdrop {
    position: fixed;
    inset: 0;
    z-index: 12000;

    display: none;
    align-items: center;
    justify-content: center;

    padding: 18px;

    background: rgba(0, 0, 0, 0.66);
}

.action-modal-backdrop.open {
    display: flex;
}

.action-modal {
    width: min(620px, 100%);
    max-height: calc(100vh - 36px);

    overflow: auto;

    padding: 18px;

    border: 1px solid #3d4248;
    border-radius: 12px;

    background: #202328;
    color: #eeeeee;

    box-shadow:
        0 12px 36px rgba(0, 0, 0, 0.42);
}

.action-modal h2 {
    margin: 0 0 15px 0;
}

.action-modal-section {
    margin-top: 18px;
}

.action-modal-section:first-of-type {
    margin-top: 0;
}

.action-modal-label {
    display: block;

    margin-bottom: 6px;

    color: #aeb4ba;

    font-size: 12px;
    font-weight: bold;
}

.action-modal input,
.action-modal select {
    width: 100%;
    min-height: 42px;

    margin-bottom: 12px;
    padding: 9px 11px;

    border: 1px solid #42484f;
    border-radius: 8px;

    background: #15171a;
    color: #eeeeee;

    font: inherit;
}

.action-modal-actions {
    display: flex;
    justify-content: flex-end;
    flex-wrap: wrap;

    gap: 8px;

    margin-top: 8px;
}

.action-modal-actions button,
.saved-action-buttons button {
    min-height: 38px;

    padding: 8px 11px;

    border: 0;
    border-radius: 7px;

    background: #41464d;
    color: #ffffff;

    cursor: pointer;
}

.action-modal-actions .primary {
    background: #365d82;
}

.saved-actions {
    display: flex;
    flex-direction: column;

    gap: 8px;
}

.saved-action {
    display: flex;
    align-items: center;
    flex-wrap: wrap;

    gap: 9px;

    padding: 10px;

    border: 1px solid #383d43;
    border-radius: 8px;

    background: #181b1f;
}

.saved-action-info {
    flex: 1;
    min-width: 160px;
}

.saved-action-name {
    font-weight: bold;
}

.saved-action-detail {
    margin-top: 3px;

    overflow: hidden;

    color: #858b92;

    font-size: 11px;

    text-overflow: ellipsis;
    white-space: nowrap;
}

.saved-action-buttons {
    display: flex;
    flex-wrap: wrap;

    gap: 6px;
}

.saved-action-buttons .danger {
    background: #763f3f;
}

.saved-actions-empty {
    padding: 10px;

    border: 1px dashed #3d4248;
    border-radius: 8px;

    color: #777d84;

    font-size: 12px;
    text-align: center;
}


/*
 * CONFIGURATION DES CAMÉRAS
 */

.camera-config-modal {
    width: min(920px, 100%);
}

.camera-config-meta {
    margin-bottom: 14px;
    padding: 10px 12px;

    border: 1px solid #383d43;
    border-radius: 8px;

    background: #181b1f;
    color: #9aa0a6;

    font-size: 12px;

    overflow-wrap: anywhere;
}

.camera-config-meta.warning {
    border-color: #6d5b2d;
    color: #e2b84f;
}

.camera-config-list {
    display: flex;
    flex-direction: column;

    gap: 8px;
}

.camera-config-row {
    display: flex;
    align-items: center;
    flex-wrap: wrap;

    gap: 10px;

    padding: 10px 11px;

    border: 1px solid #383d43;
    border-radius: 8px;

    background: #181b1f;
}

.camera-config-main {
    flex: 1;
    min-width: 190px;
}

.camera-config-name {
    font-weight: bold;
}

.camera-config-detail {
    margin-top: 4px;

    color: #858b92;

    font-size: 11px;
}

.camera-config-badge {
    padding: 3px 7px;

    border-radius: 999px;

    font-size: 10px;
    font-weight: bold;
}

.camera-config-badge.enabled {
    background: #274a34;
    color: #70d88b;
}

.camera-config-badge.disabled {
    background: #4a3e26;
    color: #e2b84f;
}

.camera-config-row-actions {
    display: flex;
    flex-wrap: wrap;

    gap: 6px;
}

.camera-config-row-actions button {
    min-height: 36px;

    padding: 7px 10px;

    border: 0;
    border-radius: 7px;

    background: #41464d;
    color: #ffffff;

    cursor: pointer;
}

.camera-config-row-actions .danger {
    background: #763f3f;
}

.camera-config-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;

    gap: 8px;

    margin-bottom: 12px;
}

.camera-config-toolbar button {
    min-height: 38px;

    padding: 8px 11px;

    border: 0;
    border-radius: 7px;

    background: #365d82;
    color: #ffffff;

    cursor: pointer;
}

.camera-config-form {
    display: none;
}

.camera-config-form.open {
    display: block;
}

.camera-config-grid {
    display: grid;

    grid-template-columns:
        repeat(2, minmax(0, 1fr));

    gap: 0 12px;
}

.camera-config-field.full {
    grid-column: 1 / -1;
}

.camera-config-check {
    display: flex;
    align-items: center;

    gap: 8px;

    min-height: 42px;
    margin-bottom: 12px;
}

.camera-config-check input {
    width: 18px;
    height: 18px;
    min-height: 18px;

    margin: 0;
    padding: 0;
}

.camera-config-help {
    margin: -5px 0 12px 0;

    color: #858b92;

    font-size: 11px;
}

.camera-config-env-warning {
    display: none;

    margin-bottom: 12px;
    padding: 9px 10px;

    border: 1px solid #6d5b2d;
    border-radius: 8px;

    background: #292318;
    color: #e2b84f;

    font-size: 11px;

    overflow-wrap: anywhere;
}

.camera-config-password-state {
    margin-bottom: 12px;

    color: #aeb4ba;

    font-size: 11px;
}

.camera-config-empty {
    padding: 14px;

    border: 1px dashed #3d4248;
    border-radius: 8px;

    color: #777d84;

    text-align: center;
}

@media (max-width: 700px) {

    .camera-config-grid {
        grid-template-columns:
            1fr;
    }
}

.empty {
    width: 100%;

    padding: 46px 20px;

    border: 1px dashed #3d4248;
    border-radius: 10px;

    color: #777d84;
    text-align: center;
}

.tile-shell {
    display: flex;
    flex-direction: column;
    align-items: stretch;

    max-width: 100%;
}

.tile {
    position: relative;

    display: flex;
    flex-direction: column;

    width: 520px;
    height: auto;

    min-width: 320px;
    min-height: 0;

    max-width: 100%;

    overflow: hidden;

    /*
     * On ne redimensionne que la largeur.
     * La hauteur de l'image est recalculée automatiquement en 16:9.
     */
    resize: horizontal;

    border: 1px solid #3a3f45;
    border-radius: 10px;

    background: #0e1012;

    box-shadow:
        0 6px 18px rgba(0, 0, 0, 0.22);
}

.tile.dragging {
    opacity: 0.45;
}

.tile.drop-before,
.tile-shell.drop-before {
    outline: 2px solid #70aee8;
    outline-offset: 3px;
}

.tile-header {
    display: flex;
    align-items: center;

    min-height: 42px;

    padding: 7px 8px 7px 12px;

    background: #24282d;
    border-bottom: 1px solid #353a40;

    cursor: grab;
    user-select: none;
}

.tile-header:active {
    cursor: grabbing;
}

.tile-name {
    min-width: 0;

    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    font-weight: bold;
}

.tile-status {
    display: inline-flex;
    align-items: center;

    margin-left: 9px;

    color: #999fa6;
    font-size: 11px;
    white-space: nowrap;
}

.tile-status.active {
    color: #70d88b;
}

.tile-status.standby {
    color: #e2b84f;
}

.tile-status.unavailable {
    color: #ef7777;
}

.tile-spacer {
    flex: 1;
}

.close-tile {
    width: 32px;
    height: 30px;

    margin-left: 8px;

    border: 0;
    border-radius: 6px;

    background: transparent;
    color: #c9cdd1;

    font-size: 19px;

    cursor: pointer;
}

.close-tile:hover {
    background: #3a3f45;
    color: #ffffff;
}

.control-toggle {
    width: 34px;
    height: 30px;

    margin-left: 5px;

    border: 0;
    border-radius: 6px;

    background: transparent;
    color: #c9cdd1;

    font-size: 17px;

    cursor: pointer;
}

.control-toggle:hover,
.control-toggle.active {
    background: #3a3f45;
    color: #ffffff;
}

.player {
    position: relative;

    flex: 0 0 auto;

    width: 100%;
    aspect-ratio: 16 / 9;

    min-height: 0;

    overflow: hidden;

    background: #000000;
}

.player img {
    display: block;

    width: 100%;
    height: 100%;

    border: 0;

    background: #000000;

    object-fit: cover;

    user-select: none;
    -webkit-user-drag: none;
}


.cast-button {
    position: absolute;
    left: 5px;
    bottom: 5px;
    z-index: 12;

    display: flex;
    align-items: center;
    justify-content: center;

    width: 38px;
    height: 34px;
    padding: 0;

    border: 1px solid rgba(255, 255, 255, 0.20);
    border-radius: 7px;

    background: rgba(15, 17, 19, 0.72);
    color: #f1f1f1;

    cursor: pointer;

    box-shadow:
        0 3px 10px rgba(0, 0, 0, 0.28);

    backdrop-filter: blur(4px);
}

.cast-button:hover {
    background: rgba(47, 53, 59, 0.92);
}

.cast-button.active {
    border-color: #70d88b;
    background: rgba(38, 88, 55, 0.94);
    color: #b8f4c7;
}

.cast-button.busy {
    opacity: 0.65;
    cursor: wait;
}

.cast-button svg {
    width: 22px;
    height: 22px;
    fill: currentColor;
}

.cast-device-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
}

.cast-device {
    display: flex;
    align-items: center;
    gap: 10px;

    width: 100%;
    padding: 11px 12px;

    border: 1px solid #3a3f45;
    border-radius: 8px;

    background: #181b1f;
    color: #eeeeee;

    text-align: left;
    cursor: pointer;
}

.cast-device:hover {
    background: #272c31;
}

.cast-device-main {
    flex: 1;
    min-width: 0;
}

.cast-device-name {
    font-weight: bold;
}

.cast-device-detail {
    margin-top: 3px;

    color: #858b92;
    font-size: 11px;

    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.cast-device-icon {
    flex: 0 0 auto;

    width: 32px;
    height: 32px;

    display: flex;
    align-items: center;
    justify-content: center;

    border-radius: 7px;
    background: #30353b;
}

.cast-device-icon svg {
    width: 20px;
    height: 20px;
    fill: #d7dadd;
}

.cast-chooser-empty {
    padding: 18px;

    border: 1px dashed #3d4248;
    border-radius: 8px;

    color: #858b92;
    text-align: center;
}

.cast-chooser-note {
    margin-bottom: 12px;

    color: #9aa0a6;
    font-size: 12px;
}

.starting {
    position: absolute;
    inset: 0;

    display: flex;
    align-items: center;
    justify-content: center;

    padding: 20px;

    background: #0e1012;
    color: #9ca2a8;

    text-align: center;
    font-size: 13px;

    pointer-events: none;
}

.starting.hidden {
    display: none;
}

/*
 * Panneau de pilotage SOUS la tuile.
 *
 * Il ne recouvre jamais la vidéo et n'altère pas la connexion MJPEG.
 * La taille enregistrée reste celle de la fenêtre vidéo elle-même.
 */
.tile-controls {
    display: none;

    margin-top: 8px;
    padding: 12px;

    border: 1px solid rgba(255, 255, 255, 0.13);
    border-radius: 10px;

    background: #202328;

    box-shadow:
        0 6px 18px rgba(0, 0, 0, 0.22);
}

.tile-controls.open {
    display: block;
}

.tile-controls-layout {
    display: flex;
    align-items: flex-start;
    justify-content: center;
    flex-wrap: wrap;

    gap: 14px 18px;
}

.tile-control-section {
    min-width: 155px;
}

.tile-control-title {
    margin-bottom: 8px;

    color: #aeb4ba;

    font-size: 11px;
    font-weight: bold;

    text-align: center;
    text-transform: uppercase;
}

.tile-ptz {
    display: grid;

    grid-template-columns:
        46px 46px 46px;

    grid-template-rows:
        40px 40px 40px;

    justify-content: center;

    gap: 5px;
}

.tile-ptz button,
.tile-control-buttons button {
    border: 0;
    border-radius: 7px;

    background: #41464d;
    color: #ffffff;

    cursor: pointer;

    user-select: none;
    touch-action: none;
}

.tile-ptz button {
    font-size: 19px;
}

.tile-ptz .ptz-up {
    grid-column: 2;
    grid-row: 1;
}

.tile-ptz .ptz-left {
    grid-column: 1;
    grid-row: 2;
}

.tile-ptz .ptz-stop {
    grid-column: 2;
    grid-row: 2;

    background: #824444;

    font-size: 10px;
    font-weight: bold;
}

.tile-ptz .ptz-right {
    grid-column: 3;
    grid-row: 2;
}

.tile-ptz .ptz-down {
    grid-column: 2;
    grid-row: 3;
}

.tile-control-buttons {
    display: flex;
    justify-content: center;
    flex-wrap: wrap;

    gap: 6px;
}

.tile-control-buttons button {
    min-height: 34px;

    padding: 7px 10px;

    font-size: 11px;
    font-weight: bold;
}

.tile-control-buttons .light-on {
    background: #8a762e;
}

.tile-control-buttons .light-auto,
.tile-control-buttons .image-auto {
    background: #365d82;
}

.tile-control-buttons .image-color {
    background: #755f31;
}

.tile-control-buttons .image-bw {
    background: #55585c;
}

.tile-control-buttons .image-flip {
    background: #734c73;
}

.resize-note {
    position: absolute;
    right: 5px;
    bottom: 4px;

    z-index: 5;

    padding: 2px 4px;

    border-radius: 4px;

    background: rgba(0, 0, 0, 0.58);
    color: #b7bcc1;

    font-size: 10px;

    pointer-events: none;
}

.toast-container {
    position: fixed;
    top: 18px;
    right: 18px;
    z-index: 10000;

    display: flex;
    flex-direction: column;
    align-items: flex-end;

    gap: 10px;

    pointer-events: none;
}

.toast {
    min-width: 280px;
    max-width: min(430px, calc(100vw - 36px));

    padding: 12px 15px;

    border-radius: 8px;
    border: 1px solid rgba(255, 255, 255, 0.12);

    background: #2b2f34;
    color: #f3f3f3;

    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.32);

    font-size: 14px;
    line-height: 1.35;

    opacity: 0;
    transform: translateY(-8px);

    transition:
        opacity 160ms ease,
        transform 160ms ease;
}

.toast.visible {
    opacity: 1;
    transform: translateY(0);
}

.toast.warning {
    border-left: 5px solid #e2b84f;
}

.toast.error {
    border-left: 5px solid #ef7777;
}

.toast.info {
    border-left: 5px solid #70aee8;
}

.toast-title {
    margin-bottom: 3px;
    font-weight: bold;
}

.toast-message {
    color: #d5d8dc;
}

@media (max-width: 700px) {

    body {
        padding: 12px;
    }

    .tile-shell {
        width: 100%;
    }

    .tile {
        width: 100% !important;
        min-width: 0;
        resize: none;
    }
}

</style>

</head>

<body>

<div
    id="toast-container"
    class="toast-container"
></div>

<button
    id="show-interface"
    class="interface-toggle"
    type="button"
    title="Réafficher l'interface"
    aria-label="Réafficher l'interface"
>
    ☰
</button>

<div class="topbar">

    <div class="title">

        <h1>
            Mur vidéo
        </h1>

        <div class="subtitle">
            Sélectionnez les caméras à afficher. Chaque tuile ouvre directement le flux du serveur RTSP Xiaovv.
        </div>

    </div>

    <div class="actions">

        <button
            id="config-button"
            class="button"
            type="button"
        >
            Configuration
        </button>

        <button
            id="hide-interface"
            class="button"
            type="button"
        >
            Masquer l'interface
        </button>

        <button
            id="token-button"
            class="button"
            type="button"
        >
            Jeton API
        </button>

    </div>

</div>

<section class="selector">

    <div class="selector-header">

        <div class="selector-title">
            Caméras affichées
        </div>

        <div class="selector-actions">

            <button
                id="select-all"
                type="button"
            >
                Toutes
            </button>

            <button
                id="select-none"
                type="button"
            >
                Aucune
            </button>

            <button
                id="add-action-button"
                type="button"
            >
                + Bouton
            </button>

        </div>

    </div>

    <div
        id="camera-choices"
        class="camera-choices"
    >
        Chargement...
    </div>


</section>

<section
    id="action-wall"
    class="action-wall"
></section>

<main
    id="wall"
    class="wall"
>
</main>

<div
    id="action-modal-backdrop"
    class="action-modal-backdrop"
>

    <div
        class="action-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="action-modal-title"
    >

        <h2 id="action-modal-title">
            Ajouter un bouton
        </h2>

        <section
            id="saved-actions-section"
            class="action-modal-section"
        >

            <div class="action-modal-label">
                Boutons déjà créés mais masqués
            </div>

            <div
                id="saved-actions"
                class="saved-actions"
            ></div>

        </section>

        <section class="action-modal-section">

            <label
                class="action-modal-label"
                for="action-name"
            >
                Nom du bouton
            </label>

            <input
                id="action-name"
                type="text"
                maxlength="80"
                placeholder="Ex. Ouvrir le portail"
            >

            <label
                class="action-modal-label"
                for="action-method"
            >
                Méthode HTTP
            </label>

            <select id="action-method">
                <option value="GET">GET</option>
                <option value="POST">POST</option>
                <option value="PUT">PUT</option>
                <option value="PATCH">PATCH</option>
                <option value="DELETE">DELETE</option>
            </select>

            <label
                class="action-modal-label"
                for="action-url"
            >
                URL
            </label>

            <input
                id="action-url"
                type="url"
                placeholder="http://jeedom/..."
            >

        </section>

        <div class="action-modal-actions">

            <button
                id="action-cancel"
                type="button"
            >
                Annuler
            </button>

            <button
                id="action-save"
                class="primary"
                type="button"
            >
                Enregistrer
            </button>

        </div>

    </div>

</div>

<div
    id="camera-config-backdrop"
    class="action-modal-backdrop"
>

    <div
        class="action-modal camera-config-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="camera-config-title"
    >

        <h2 id="camera-config-title">
            Configuration des caméras
        </h2>

        <div
            id="camera-config-meta"
            class="camera-config-meta"
        >
            Chargement...
        </div>

        <div
            id="camera-config-list-panel"
        >

            <div class="camera-config-toolbar">

                <strong>
                    Caméras du fichier de configuration
                </strong>

                <button
                    id="camera-config-add"
                    type="button"
                >
                    + Ajouter une caméra
                </button>

            </div>

            <div
                id="camera-config-list"
                class="camera-config-list"
            ></div>

            <div class="action-modal-actions">

                <button
                    id="camera-config-close"
                    type="button"
                >
                    Fermer
                </button>

            </div>

        </div>

        <div
            id="camera-config-form"
            class="camera-config-form"
        >

            <div
                id="camera-config-env-warning"
                class="camera-config-env-warning"
            ></div>

            <div class="camera-config-grid">

                <div class="camera-config-field">

                    <label
                        class="action-modal-label"
                        for="camera-config-id"
                    >
                        Identifiant
                    </label>

                    <input
                        id="camera-config-id"
                        type="text"
                        maxlength="80"
                        placeholder="garage"
                    >

                </div>

                <div class="camera-config-field">

                    <label
                        class="action-modal-label"
                        for="camera-config-stream"
                    >
                        Nom du flux RTSP
                    </label>

                    <input
                        id="camera-config-stream"
                        type="text"
                        maxlength="80"
                        placeholder="garage"
                    >

                </div>

                <div class="camera-config-field">

                    <label
                        class="action-modal-label"
                        for="camera-config-host"
                    >
                        Adresse / IP
                    </label>

                    <input
                        id="camera-config-host"
                        type="text"
                        placeholder="192.168.1.x"
                    >

                </div>

                <div class="camera-config-field">

                    <label
                        class="action-modal-label"
                        for="camera-config-port"
                    >
                        Port caméra
                    </label>

                    <input
                        id="camera-config-port"
                        type="number"
                        min="1"
                        max="65535"
                        value="8800"
                    >

                </div>

                <div class="camera-config-field">

                    <label
                        class="action-modal-label"
                        for="camera-config-device"
                    >
                        Device ID
                    </label>

                    <input
                        id="camera-config-device"
                        type="text"
                    >

                </div>

                <div class="camera-config-field">

                    <label
                        class="action-modal-label"
                        for="camera-config-username"
                    >
                        Utilisateur
                    </label>

                    <input
                        id="camera-config-username"
                        type="text"
                    >

                </div>

                <div class="camera-config-field">

                    <label
                        class="action-modal-label"
                        for="camera-config-resolution"
                    >
                        Résolution
                    </label>

                    <select id="camera-config-resolution">
                        <option value="HIGH">HIGH</option>
                        <option value="LOW">LOW</option>
                    </select>

                </div>

                <div class="camera-config-field">

                    <label
                        class="action-modal-label"
                        for="camera-config-reconnect"
                    >
                        Reconnexion (ms)
                    </label>

                    <input
                        id="camera-config-reconnect"
                        type="number"
                        min="1000"
                        step="500"
                        value="5000"
                    >

                </div>

                <div class="camera-config-field full">

                    <label class="camera-config-check">

                        <input
                            id="camera-config-enabled"
                            type="checkbox"
                            checked
                        >

                        <span>
                            Caméra activée
                        </span>

                    </label>

                </div>

                <div class="camera-config-field full">

                    <div
                        id="camera-config-password-state"
                        class="camera-config-password-state"
                    ></div>

                    <label
                        class="action-modal-label"
                        for="camera-config-password-mode"
                    >
                        Mot de passe
                    </label>

                    <select id="camera-config-password-mode">
                        <option value="keep">Conserver la configuration actuelle</option>
                        <option value="file">Définir un nouveau mot de passe dans le fichier</option>
                        <option value="env">Utiliser une variable d'environnement</option>
                    </select>

                </div>

                <div
                    id="camera-config-password-field"
                    class="camera-config-field full"
                    style="display:none"
                >

                    <label
                        class="action-modal-label"
                        for="camera-config-password"
                    >
                        Nouveau mot de passe
                    </label>

                    <input
                        id="camera-config-password"
                        type="password"
                        autocomplete="new-password"
                        placeholder="Le mot de passe existant n'est jamais affiché"
                    >

                </div>

                <div
                    id="camera-config-password-env-field"
                    class="camera-config-field full"
                    style="display:none"
                >

                    <label
                        class="action-modal-label"
                        for="camera-config-password-env"
                    >
                        Variable d'environnement
                    </label>

                    <input
                        id="camera-config-password-env"
                        type="text"
                        placeholder="XIAOVV_CAMERA_GARAGE_PASSWORD"
                    >

                </div>

            </div>

            <div class="camera-config-help">
                L'enregistrement modifie application.properties et crée automatiquement
                application.properties.bak. Le mot de passe existant n'est jamais envoyé au navigateur.
            </div>

            <div class="action-modal-actions">

                <button
                    id="camera-config-back"
                    type="button"
                >
                    Retour
                </button>

                <button
                    id="camera-config-save"
                    class="primary"
                    type="button"
                >
                    Enregistrer
                </button>

            </div>

        </div>

    </div>

</div>

<div
    id="cast-modal-backdrop"
    class="action-modal-backdrop"
>
    <div
        class="action-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="cast-modal-title"
    >
        <h2 id="cast-modal-title">
            Diffuser la caméra
        </h2>

        <div
            id="cast-modal-note"
            class="cast-chooser-note"
        >
            Choisissez l'écran sur lequel diffuser le flux.
        </div>

        <div
            id="cast-device-list"
            class="cast-device-list"
        ></div>

        <div class="action-modal-actions">
            <button
                id="cast-refresh"
                type="button"
            >
                Actualiser
            </button>

            <button
                id="cast-cancel"
                type="button"
            >
                Annuler
            </button>
        </div>
    </div>
</div>

<script>

const STORAGE_SELECTED =
    "xiaovvLiveSelected";

const STORAGE_ORDER =
    "xiaovvLiveOrder";

const STORAGE_SIZES =
    "xiaovvLiveSizes";

const STORAGE_WALL_ONLY =
    "xiaovvLiveWallOnly";

const STORAGE_ACTION_TILES =
    "xiaovvActionTilesV1";

let actionTiles =
    [];

let editingActionId =
    null;

/*
 * Nom stable de cet onglet.
 * Depuis la page Pilotage, window.open(..., "xiaovv-live")
 * réutilise cet onglet s'il existe déjà.
 */
window.name =
    "xiaovv-live";

function openNamedPage(
    url,
    windowName
) {

    const target =
        window.open(
            url,
            windowName
        );

    if (target) {

        try {
            target.focus();
        } catch (_) {
        }
    }
}

function setWallOnly(
    enabled
) {

    document.body.classList.toggle(
        "wall-only",
        enabled
    );

    localStorage.setItem(
        STORAGE_WALL_ONLY,
        enabled
            ? "1"
            : "0"
    );
}

let cameras =
    [];

let selected =
    [];

let order =
    [];

let sizes =
    {};

let draggedStream =
    null;

let activePtzMove =
    null;

let castSessions =
    [];

let castChooserCamera =
    null;

let cameraConfigSnapshot =
    null;

let editingCameraId =
    null;

function getToken() {

    let token =
        localStorage.getItem(
            "xiaovvApiToken"
        );

    if (!token) {

        token =
            window.prompt(
                "Jeton API Xiaovv :"
            ) || "";

        if (token) {

            localStorage.setItem(
                "xiaovvApiToken",
                token
            );
        }
    }

    return token;
}

function changeToken() {

    localStorage.removeItem(
        "xiaovvApiToken"
    );

    getToken();

    const wall =
        document.getElementById(
            "wall"
        );

    if (wall) {

        wall.querySelectorAll(
            ".player img"
        ).forEach(
            function(image) {

                image.removeAttribute(
                    "src"
                );

                image.src =
                    "";
            }
        );

        wall.innerHTML =
            "";
    }

    refreshCameras(
        true
    );
}

async function apiFetch(
    url,
    options
) {

    options =
        options ||
        {};

    options.headers =
        options.headers ||
        {};

    options.headers[
        "X-API-Token"
    ] =
        getToken();

    const response =
        await fetch(
            url,
            options
        );

    if (
        response.status === 401
    ) {

        localStorage.removeItem(
            "xiaovvApiToken"
        );

        throw new Error(
            "Jeton API invalide"
        );
    }

    return response;
}


async function command(
    camera,
    type,
    commandName
) {

    try {

        const response =
            await apiFetch(
                "/api/cameras/" +
                encodeURIComponent(camera) +
                "/" +
                type +
                "/" +
                commandName,
                {
                    method: "POST"
                }
            );

        let payload =
            null;

        try {

            payload =
                await response.json();

        } catch (_) {
        }

        if (!response.ok) {

            let message =
                (
                    payload &&
                    payload.error
                )
                    ? payload.error
                    : "Erreur HTTP " +
                        response.status;

            let level =
                "error";

            if (
                payload &&
                payload.state === "standby"
            ) {

                message =
                    "La caméra est en ligne mais le flux n'est pas encore actif.";

                level =
                    "warning";
            }

            if (
                payload &&
                payload.state === "unavailable"
            ) {

                message =
                    "La caméra est actuellement indisponible.";
            }

            showToast(
                "Caméra " + camera,
                message,
                level
            );

            return false;
        }

        return true;

    } catch (e) {

        showToast(
            "Erreur de communication",
            e.message ||
                "Impossible de joindre l'API Xiaovv.",
            "error"
        );

        return false;
    }
}

async function startPtzMove(
    camera,
    direction
) {

    const move = {
        camera: camera,
        direction: direction,
        released: false,
        started: false
    };

    activePtzMove =
        move;

    const success =
        await command(
            camera,
            "ptz",
            direction
        );

    if (
        activePtzMove !== move
    ) {
        return;
    }

    if (!success) {

        activePtzMove =
            null;

        return;
    }

    move.started =
        true;

    /*
     * Si le bouton a déjà été relâché pendant l'aller-retour HTTP,
     * on stoppe immédiatement le mouvement désormais accepté.
     */
    if (
        move.released
    ) {

        await command(
            camera,
            "ptz",
            "stop"
        );

        if (
            activePtzMove === move
        ) {

            activePtzMove =
                null;
        }
    }
}

function stopPtzMove() {

    const move =
        activePtzMove;

    if (!move) {
        return;
    }

    move.released =
        true;

    if (
        !move.started
    ) {
        return;
    }

    activePtzMove =
        null;

    command(
        move.camera,
        "ptz",
        "stop"
    );
}

function makeTilePtzButton(
    label,
    cssClass,
    camera,
    direction
) {

    const button =
        document.createElement(
            "button"
        );

    button.type =
        "button";

    button.className =
        cssClass;

    button.textContent =
        label;

    button.addEventListener(
        "pointerdown",
        function(event) {

            event.preventDefault();
            event.stopPropagation();

            startPtzMove(
                camera,
                direction
            );
        }
    );

    button.addEventListener(
        "pointerup",
        function(event) {

            event.preventDefault();
            event.stopPropagation();

            stopPtzMove();
        }
    );

    button.addEventListener(
        "pointercancel",
        stopPtzMove
    );

    button.addEventListener(
        "pointerleave",
        function(event) {

            if (
                event.buttons !== 0
            ) {

                stopPtzMove();
            }
        }
    );

    return button;
}

function makeTileCommandButton(
    label,
    cssClass,
    camera,
    type,
    commandName
) {

    const button =
        document.createElement(
            "button"
        );

    button.type =
        "button";

    button.className =
        cssClass ||
        "";

    button.textContent =
        label;

    button.addEventListener(
        "click",
        function(event) {

            event.stopPropagation();

            command(
                camera,
                type,
                commandName
            );
        }
    );

    return button;
}

function buildTileControls(
    camera
) {

    const panel =
        document.createElement(
            "div"
        );

    panel.className =
        "tile-controls";

    const layout =
        document.createElement(
            "div"
        );

    layout.className =
        "tile-controls-layout";

    /*
     * PTZ
     */
    const ptzSection =
        document.createElement(
            "div"
        );

    ptzSection.className =
        "tile-control-section";

    const ptzTitle =
        document.createElement(
            "div"
        );

    ptzTitle.className =
        "tile-control-title";

    ptzTitle.textContent =
        "PTZ";

    const ptz =
        document.createElement(
            "div"
        );

    ptz.className =
        "tile-ptz";

    ptz.appendChild(
        makeTilePtzButton(
            "▲",
            "ptz-up",
            camera.id,
            "up"
        )
    );

    ptz.appendChild(
        makeTilePtzButton(
            "◀",
            "ptz-left",
            camera.id,
            "left"
        )
    );

    const stopButton =
        makeTileCommandButton(
            "STOP",
            "ptz-stop",
            camera.id,
            "ptz",
            "stop"
        );

    stopButton.addEventListener(
        "click",
        function() {

            activePtzMove =
                null;
        }
    );

    ptz.appendChild(
        stopButton
    );

    ptz.appendChild(
        makeTilePtzButton(
            "▶",
            "ptz-right",
            camera.id,
            "right"
        )
    );

    ptz.appendChild(
        makeTilePtzButton(
            "▼",
            "ptz-down",
            camera.id,
            "down"
        )
    );

    ptzSection.appendChild(
        ptzTitle
    );

    ptzSection.appendChild(
        ptz
    );

    /*
     * Lumière
     */
    const lightSection =
        document.createElement(
            "div"
        );

    lightSection.className =
        "tile-control-section";

    const lightTitle =
        document.createElement(
            "div"
        );

    lightTitle.className =
        "tile-control-title";

    lightTitle.textContent =
        "Lumière";

    const lightButtons =
        document.createElement(
            "div"
        );

    lightButtons.className =
        "tile-control-buttons";

    lightButtons.appendChild(
        makeTileCommandButton(
            "ON",
            "light-on",
            camera.id,
            "light",
            "on"
        )
    );

    lightButtons.appendChild(
        makeTileCommandButton(
            "OFF",
            "",
            camera.id,
            "light",
            "off"
        )
    );

    lightButtons.appendChild(
        makeTileCommandButton(
            "AUTO",
            "light-auto",
            camera.id,
            "light",
            "auto"
        )
    );

    lightSection.appendChild(
        lightTitle
    );

    lightSection.appendChild(
        lightButtons
    );

    /*
     * Image
     */
    const imageSection =
        document.createElement(
            "div"
        );

    imageSection.className =
        "tile-control-section";

    const imageTitle =
        document.createElement(
            "div"
        );

    imageTitle.className =
        "tile-control-title";

    imageTitle.textContent =
        "Image";

    const imageButtons =
        document.createElement(
            "div"
        );

    imageButtons.className =
        "tile-control-buttons";

    imageButtons.appendChild(
        makeTileCommandButton(
            "COULEUR",
            "image-color",
            camera.id,
            "image",
            "color"
        )
    );

    imageButtons.appendChild(
        makeTileCommandButton(
            "N&B / IR",
            "image-bw",
            camera.id,
            "image",
            "bw"
        )
    );

    imageButtons.appendChild(
        makeTileCommandButton(
            "AUTO",
            "image-auto",
            camera.id,
            "image",
            "auto"
        )
    );

    imageButtons.appendChild(
        makeTileCommandButton(
            "↻ 180°",
            "image-flip",
            camera.id,
            "image",
            "flip"
        )
    );

    imageSection.appendChild(
        imageTitle
    );

    imageSection.appendChild(
        imageButtons
    );

    layout.appendChild(
        ptzSection
    );

    layout.appendChild(
        lightSection
    );

    layout.appendChild(
        imageSection
    );

    panel.appendChild(
        layout
    );

    /*
     * Les interactions dans le panneau ne doivent jamais déclencher
     * le glisser-déposer de la tuile.
     */
    panel.addEventListener(
        "pointerdown",
        function(event) {

            event.stopPropagation();
        }
    );

    return panel;
}

function loadArray(
    key
) {

    try {

        const value =
            JSON.parse(
                localStorage.getItem(
                    key
                ) ||
                "[]"
            );

        return Array.isArray(value)
            ? value
            : [];

    } catch (_) {

        return [];
    }
}

function loadObject(
    key
) {

    try {

        const value =
            JSON.parse(
                localStorage.getItem(
                    key
                ) ||
                "{}"
            );

        return (
            value &&
            typeof value === "object" &&
            !Array.isArray(value)
        )
            ? value
            : {};

    } catch (_) {

        return {};
    }
}

function saveState() {

    localStorage.setItem(
        STORAGE_SELECTED,
        JSON.stringify(
            selected
        )
    );

    localStorage.setItem(
        STORAGE_ORDER,
        JSON.stringify(
            order
        )
    );

    localStorage.setItem(
        STORAGE_SIZES,
        JSON.stringify(
            sizes
        )
    );
}

function showToast(
    title,
    message,
    type
) {

    const container =
        document.getElementById(
            "toast-container"
        );

    if (!container) {
        return;
    }

    const toast =
        document.createElement(
            "div"
        );

    toast.className =
        "toast " +
        (
            type ||
            "info"
        );

    const titleNode =
        document.createElement(
            "div"
        );

    titleNode.className =
        "toast-title";

    titleNode.textContent =
        title;

    const messageNode =
        document.createElement(
            "div"
        );

    messageNode.className =
        "toast-message";

    messageNode.textContent =
        message;

    toast.appendChild(
        titleNode
    );

    toast.appendChild(
        messageNode
    );

    container.appendChild(
        toast
    );

    requestAnimationFrame(
        function() {

            toast.classList.add(
                "visible"
            );
        }
    );

    setTimeout(
        function() {

            toast.classList.remove(
                "visible"
            );

            setTimeout(
                function() {
                    toast.remove();
                },
                220
            );
        },
        4200
    );
}

function cameraByStream(
    stream
) {

    return cameras.find(
        function(camera) {
            return camera.stream === stream;
        }
    ) ||
        null;
}

function normalizeState() {

    const validStreams =
        cameras.map(
            function(camera) {
                return camera.stream;
            }
        );

    selected =
        selected.filter(
            function(stream) {
                return validStreams.includes(
                    stream
                );
            }
        );

    order =
        order.filter(
            function(stream) {
                return validStreams.includes(
                    stream
                );
            }
        );

    validStreams.forEach(
        function(stream) {

            if (
                !order.includes(
                    stream
                )
            ) {

                order.push(
                    stream
                );
            }
        }
    );

    saveState();
}

function liveStreamUrl(
    cameraId
) {

    return "/api/cameras/" +
        encodeURIComponent(cameraId) +
        "/live.mjpeg?token=" +
        encodeURIComponent(
            getToken()
        );
}

function castIconSvg() {

    return `
        <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M1 18v3h3a3 3 0 0 0-3-3zm0-4v2a5 5 0 0 1 5 5h2a7 7 0 0 0-7-7zm0-4v2c4.97 0 9 4.03 9 9h2c0-6.08-4.92-11-11-11zm3-7a3 3 0 0 0-3 3v2h2V6a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-6v2h6a3 3 0 0 0 3-3V6a3 3 0 0 0-3-3H4z"/>
        </svg>
    `;
}

function castSessionForCamera(
    cameraId
) {

    return castSessions.find(
        function(session) {

            return (
                session.cameraId === cameraId &&
                session.active !== false
            );
        }
    ) || null;
}

function updateCastButtons() {

    document.querySelectorAll(
        ".cast-button"
    ).forEach(
        function(button) {

            const session =
                castSessionForCamera(
                    button.dataset.cameraId
                );

            button.classList.toggle(
                "active",
                Boolean(session)
            );

            button.title =
                session
                    ? "Arrêter la diffusion sur " + session.deviceName
                    : "Diffuser cette caméra sur un écran";
        }
    );
}

async function refreshCastStatus() {

    try {

        const response =
            await apiFetch(
                "/api/cast/status"
            );

        if (!response.ok) {
            return;
        }

        castSessions =
            await response.json();

        updateCastButtons();

    } catch (_) {
    }
}

async function stopCameraCast(
    cameraId,
    button
) {

    if (button) {
        button.disabled = true;
        button.classList.add("busy");
    }

    try {

        const body =
            new URLSearchParams();

        body.set(
            "cameraId",
            cameraId
        );

        const response =
            await apiFetch(
                "/api/cast/stop",
                {
                    method: "POST",
                    headers: {
                        "Content-Type":
                            "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: body.toString()
                }
            );

        const payload =
            await response.json();

        if (!response.ok) {
            throw new Error(
                payload.error ||
                "Impossible d'arrêter le cast."
            );
        }

        castSessions =
            castSessions.filter(
                function(session) {
                    return session.cameraId !== cameraId;
                }
            );

        updateCastButtons();

        showToast(
            cameraId,
            "Diffusion Cast arrêtée.",
            "info"
        );

    } catch (e) {

        showToast(
            "Cast",
            e.message || "Impossible d'arrêter le cast.",
            "error"
        );

    } finally {

        if (button) {
            button.disabled = false;
            button.classList.remove("busy");
        }
    }
}

async function handleCastButton(
    camera,
    button
) {

    if (
        castSessionForCamera(
            camera.id
        )
    ) {

        await stopCameraCast(
            camera.id,
            button
        );

        return;
    }

    castChooserCamera =
        camera;

    document.getElementById(
        "cast-modal-title"
    ).textContent =
        "Diffuser « " + camera.id + " »";

    document.getElementById(
        "cast-modal-backdrop"
    ).classList.add(
        "open"
    );

    await loadCastDevices();
}

function closeCastChooser() {

    castChooserCamera =
        null;

    document.getElementById(
        "cast-modal-backdrop"
    ).classList.remove(
        "open"
    );
}

async function loadCastDevices() {

    const container =
        document.getElementById(
            "cast-device-list"
        );

    const note =
        document.getElementById(
            "cast-modal-note"
        );

    container.innerHTML = "";
    note.textContent =
        "Recherche des appareils Google Cast sur le réseau…";

    try {

        const response =
            await apiFetch(
                "/api/cast/devices"
            );

        const devices =
            await response.json();

        if (!response.ok) {
            throw new Error(
                devices.error ||
                "Découverte Cast impossible."
            );
        }

        if (
            !Array.isArray(devices) ||
            devices.length === 0
        ) {

            note.textContent =
                "Aucun appareil Google Cast détecté.";

            const empty =
                document.createElement(
                    "div"
                );

            empty.className =
                "cast-chooser-empty";

            empty.textContent =
                "Aucun écran trouvé. Vérifiez que Xiaovv et vos Hub / téléviseurs sont sur le même réseau, puis cliquez sur Actualiser.";

            container.appendChild(
                empty
            );

            return;
        }

        note.textContent =
            devices.length +
            " appareil(s) Cast détecté(s).";

        devices.forEach(
            function(device) {

                const row =
                    document.createElement(
                        "button"
                    );

                row.type = "button";
                row.className = "cast-device";

                const icon =
                    document.createElement(
                        "div"
                    );

                icon.className =
                    "cast-device-icon";

                icon.innerHTML =
                    castIconSvg();

                const main =
                    document.createElement(
                        "div"
                    );

                main.className =
                    "cast-device-main";

                const name =
                    document.createElement(
                        "div"
                    );

                name.className =
                    "cast-device-name";

                name.textContent =
                    device.name;

                const detail =
                    document.createElement(
                        "div"
                    );

                detail.className =
                    "cast-device-detail";

                detail.textContent =
                    (device.model ? device.model + " — " : "") +
                    device.address;

                main.appendChild(name);
                main.appendChild(detail);
                row.appendChild(icon);
                row.appendChild(main);

                row.addEventListener(
                    "click",
                    function() {
                        startCameraCast(
                            device,
                            row
                        );
                    }
                );

                container.appendChild(row);
            }
        );

    } catch (e) {

        note.textContent =
            "Erreur de découverte.";

        const empty =
            document.createElement(
                "div"
            );

        empty.className =
            "cast-chooser-empty";

        empty.textContent =
            e.message ||
            "Impossible de rechercher les appareils Cast.";

        container.appendChild(empty);
    }
}

async function startCameraCast(
    device,
    row
) {

    const camera =
        castChooserCamera;

    if (!camera) {
        return;
    }

    row.disabled = true;

    try {

        const body =
            new URLSearchParams();

        body.set(
            "cameraId",
            camera.id
        );

        body.set(
            "deviceAddress",
            device.address
        );

        const response =
            await apiFetch(
                "/api/cast/start",
                {
                    method: "POST",
                    headers: {
                        "Content-Type":
                            "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: body.toString()
                }
            );

        const payload =
            await response.json();

        if (!response.ok) {
            throw new Error(
                payload.error ||
                "Démarrage du cast impossible."
            );
        }

        castSessions =
            castSessions.filter(
                function(session) {

                    return (
                        session.cameraId !== camera.id &&
                        session.deviceAddress !== device.address
                    );
                }
            );

        castSessions.push(
            {
                cameraId: camera.id,
                deviceName:
                    payload.deviceName || device.name,
                deviceAddress:
                    payload.deviceAddress || device.address,
                active: true
            }
        );

        closeCastChooser();
        updateCastButtons();

        showToast(
            camera.id,
            "Diffusion lancée sur " +
                (payload.deviceName || device.name) +
                ".",
            "info"
        );

    } catch (e) {

        showToast(
            "Cast",
            e.message ||
                "Démarrage du cast impossible.",
            "error"
        );

        row.disabled = false;
    }
}

async function openCameraConfiguration() {

    document.getElementById(
        "camera-config-backdrop"
    ).classList.add(
        "open"
    );

    showCameraConfigList();

    await loadCameraConfiguration();
}

function closeCameraConfiguration() {

    editingCameraId =
        null;

    document.getElementById(
        "camera-config-backdrop"
    ).classList.remove(
        "open"
    );
}

function showCameraConfigList() {

    document.getElementById(
        "camera-config-list-panel"
    ).style.display =
        "";

    document.getElementById(
        "camera-config-form"
    ).classList.remove(
        "open"
    );

    editingCameraId =
        null;
}

async function loadCameraConfiguration() {

    const meta =
        document.getElementById(
            "camera-config-meta"
        );

    const list =
        document.getElementById(
            "camera-config-list"
        );

    meta.className =
        "camera-config-meta";

    meta.textContent =
        "Chargement de la configuration...";

    list.innerHTML =
        "";

    try {

        const response =
            await apiFetch(
                "/api/config/cameras"
            );

        const payload =
            await response.json();

        if (!response.ok) {

            throw new Error(
                payload.error ||
                (
                    "HTTP " +
                    response.status
                )
            );
        }

        cameraConfigSnapshot =
            payload;

        const addButton =
            document.getElementById(
                "camera-config-add"
            );

        addButton.disabled =
            !payload.editable;

        if (
            payload.editable
        ) {

            meta.textContent =
                "Fichier : " +
                (
                    payload.path ||
                    "application.properties"
                ) +
                " — les modifications sont appliquées immédiatement.";

        } else {

            meta.className =
                "camera-config-meta warning";

            meta.textContent =
                "Configuration en lecture seule. " +
                "Xiaovv doit être lancé avec -Dxiaovv.config=... " +
                "ou XIAOVV_CONFIG pour permettre les modifications.";
        }

        renderCameraConfigurationList();

    } catch (e) {

        cameraConfigSnapshot =
            null;

        meta.className =
            "camera-config-meta warning";

        meta.textContent =
            e.message ||
            "Impossible de lire la configuration.";

        list.innerHTML =
            "";
    }
}

function renderCameraConfigurationList() {

    const list =
        document.getElementById(
            "camera-config-list"
        );

    list.innerHTML =
        "";

    if (
        !cameraConfigSnapshot ||
        !Array.isArray(
            cameraConfigSnapshot.cameras
        ) ||
        cameraConfigSnapshot.cameras.length === 0
    ) {

        const empty =
            document.createElement(
                "div"
            );

        empty.className =
            "camera-config-empty";

        empty.textContent =
            "Aucune caméra définie dans le fichier.";

        list.appendChild(
            empty
        );

        return;
    }

    cameraConfigSnapshot.cameras.forEach(
        function(camera) {

            const row =
                document.createElement(
                    "div"
                );

            row.className =
                "camera-config-row";

            const main =
                document.createElement(
                    "div"
                );

            main.className =
                "camera-config-main";

            const name =
                document.createElement(
                    "div"
                );

            name.className =
                "camera-config-name";

            name.textContent =
                camera.id;

            const detail =
                document.createElement(
                    "div"
                );

            detail.className =
                "camera-config-detail";

            detail.textContent =
                camera.host +
                ":" +
                camera.port +
                "  →  /" +
                camera.streamName +
                "  (" +
                camera.resolution +
                ")";

            main.appendChild(
                name
            );

            main.appendChild(
                detail
            );

            const badge =
                document.createElement(
                    "span"
                );

            badge.className =
                "camera-config-badge " +
                (
                    camera.enabled
                        ? "enabled"
                        : "disabled"
                );

            badge.textContent =
                camera.enabled
                    ? "ACTIVÉE"
                    : "DÉSACTIVÉE";

            const actions =
                document.createElement(
                    "div"
                );

            actions.className =
                "camera-config-row-actions";

            const edit =
                document.createElement(
                    "button"
                );

            edit.type =
                "button";

            edit.textContent =
                "Modifier";

            edit.disabled =
                !cameraConfigSnapshot.editable;

            edit.addEventListener(
                "click",
                function() {

                    openCameraEditor(
                        camera.id
                    );
                }
            );

            const remove =
                document.createElement(
                    "button"
                );

            remove.type =
                "button";

            remove.className =
                "danger";

            remove.textContent =
                "Supprimer";

            remove.disabled =
                !cameraConfigSnapshot.editable;

            remove.addEventListener(
                "click",
                function() {

                    deleteCameraConfiguration(
                        camera.id
                    );
                }
            );

            actions.appendChild(
                edit
            );

            actions.appendChild(
                remove
            );

            row.appendChild(
                main
            );

            row.appendChild(
                badge
            );

            row.appendChild(
                actions
            );

            list.appendChild(
                row
            );
        }
    );
}

function openCameraEditor(
    cameraId
) {

    if (
        !cameraConfigSnapshot ||
        !cameraConfigSnapshot.editable
    ) {
        return;
    }

    const existing =
        cameraId
            ? cameraConfigSnapshot.cameras.find(
                function(camera) {

                    return camera.id === cameraId;
                }
            )
            : null;

    editingCameraId =
        existing
            ? existing.id
            : null;

    document.getElementById(
        "camera-config-list-panel"
    ).style.display =
        "none";

    document.getElementById(
        "camera-config-form"
    ).classList.add(
        "open"
    );

    const idInput =
        document.getElementById(
            "camera-config-id"
        );

    idInput.disabled =
        Boolean(
            existing
        );

    idInput.value =
        existing
            ? existing.id
            : "";

    document.getElementById(
        "camera-config-stream"
    ).value =
        existing
            ? existing.streamName
            : "";

    document.getElementById(
        "camera-config-host"
    ).value =
        existing
            ? existing.host
            : "";

    document.getElementById(
        "camera-config-port"
    ).value =
        existing
            ? String(
                existing.port
            )
            : "8800";

    document.getElementById(
        "camera-config-device"
    ).value =
        existing
            ? existing.deviceId
            : "";

    document.getElementById(
        "camera-config-username"
    ).value =
        existing
            ? existing.username
            : "";

    document.getElementById(
        "camera-config-resolution"
    ).value =
        existing
            ? existing.resolution
            : "HIGH";

    document.getElementById(
        "camera-config-reconnect"
    ).value =
        existing
            ? String(
                existing.reconnectDelayMs
            )
            : "5000";

    document.getElementById(
        "camera-config-enabled"
    ).checked =
        existing
            ? existing.enabled
            : true;

    const passwordState =
        document.getElementById(
            "camera-config-password-state"
        );

    if (existing) {

        const parts =
            [];

        if (
            existing.passwordConfigured
        ) {

            parts.push(
                "Un mot de passe est configuré. Sa valeur n'est jamais envoyée au navigateur."
            );

        } else {

            parts.push(
                "Aucun mot de passe exploitable n'est actuellement détecté."
            );
        }

        if (
            existing.passwordEnv
        ) {

            parts.push(
                "Variable explicite : " +
                existing.passwordEnv +
                "."
            );
        }

        if (
            existing.automaticPasswordEnvironmentAvailable
        ) {

            parts.push(
                "La variable automatique de mot de passe est présente."
            );
        }

        passwordState.textContent =
            parts.join(
                " "
            );

    } else {

        passwordState.textContent =
            "Pour une nouvelle caméra, définissez un mot de passe ou une variable d'environnement.";
    }

    const passwordMode =
        document.getElementById(
            "camera-config-password-mode"
        );

    passwordMode.value =
        existing
            ? "keep"
            : "file";

    document.getElementById(
        "camera-config-password"
    ).value =
        "";

    document.getElementById(
        "camera-config-password-env"
    ).value =
        existing &&
        existing.passwordEnv
            ? existing.passwordEnv
            : "";

    const envWarning =
        document.getElementById(
            "camera-config-env-warning"
        );

    if (
        existing &&
        Array.isArray(
            existing.environmentOverrides
        ) &&
        existing.environmentOverrides.length > 0
    ) {

        envWarning.style.display =
            "block";

        envWarning.textContent =
            "Attention : certaines valeurs sont actuellement surchargées " +
            "par l'environnement : " +
            existing.environmentOverrides.join(
                ", "
            ) +
            ". Une modification du fichier n'écrasera pas ces variables.";

    } else {

        envWarning.style.display =
            "none";

        envWarning.textContent =
            "";
    }

    updateCameraPasswordFields();

    setTimeout(
        function() {

            (
                existing
                    ? document.getElementById(
                        "camera-config-host"
                    )
                    : idInput
            ).focus();
        },
        0
    );
}

function updateCameraPasswordFields() {

    const mode =
        document.getElementById(
            "camera-config-password-mode"
        ).value;

    document.getElementById(
        "camera-config-password-field"
    ).style.display =
        mode === "file"
            ? ""
            : "none";

    document.getElementById(
        "camera-config-password-env-field"
    ).style.display =
        mode === "env"
            ? ""
            : "none";
}

async function saveCameraConfiguration() {

    const id =
        document.getElementById(
            "camera-config-id"
        )
            .value
            .trim();

    const streamName =
        document.getElementById(
            "camera-config-stream"
        )
            .value
            .trim();

    const host =
        document.getElementById(
            "camera-config-host"
        )
            .value
            .trim();

    const port =
        document.getElementById(
            "camera-config-port"
        )
            .value
            .trim();

    const deviceId =
        document.getElementById(
            "camera-config-device"
        )
            .value
            .trim();

    let username =
        document.getElementById(
            "camera-config-username"
        )
            .value
            .trim();

    if (
        !username &&
        deviceId
    ) {

        username =
            deviceId;

        document.getElementById(
            "camera-config-username"
        ).value =
            username;
    }

    if (
        !/^[A-Za-z0-9_-]+$/.test(
            id
        )
    ) {

        showToast(
            "Configuration",
            "Identifiant caméra invalide.",
            "warning"
        );

        return;
    }

    if (
        !/^[A-Za-z0-9_-]+$/.test(
            streamName
        )
    ) {

        showToast(
            "Configuration",
            "Nom de flux RTSP invalide.",
            "warning"
        );

        return;
    }

    if (
        !host ||
        !deviceId ||
        !username
    ) {

        showToast(
            "Configuration",
            "Adresse, Device ID et utilisateur sont obligatoires.",
            "warning"
        );

        return;
    }

    const body =
        new URLSearchParams();

    body.set(
        "id",
        id
    );

    body.set(
        "enabled",
        document.getElementById(
            "camera-config-enabled"
        ).checked
            ? "true"
            : "false"
    );

    body.set(
        "streamName",
        streamName
    );

    body.set(
        "host",
        host
    );

    body.set(
        "port",
        port
    );

    body.set(
        "deviceId",
        deviceId
    );

    body.set(
        "username",
        username
    );

    body.set(
        "resolution",
        document.getElementById(
            "camera-config-resolution"
        ).value
    );

    body.set(
        "reconnectDelayMs",
        document.getElementById(
            "camera-config-reconnect"
        ).value
    );

    const passwordMode =
        document.getElementById(
            "camera-config-password-mode"
        ).value;

    body.set(
        "passwordMode",
        passwordMode
    );

    body.set(
        "password",
        document.getElementById(
            "camera-config-password"
        ).value
    );

    body.set(
        "passwordEnv",
        document.getElementById(
            "camera-config-password-env"
        )
            .value
            .trim()
    );

    const saveButton =
        document.getElementById(
            "camera-config-save"
        );

    saveButton.disabled =
        true;

    try {

        const response =
            await apiFetch(
                "/api/config/cameras/save",
                {
                    method: "POST",
                    headers: {
                        "Content-Type":
                            "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body:
                        body.toString()
                }
            );

        const payload =
            await response.json();

        if (!response.ok) {

            throw new Error(
                payload.error ||
                "Enregistrement impossible."
            );
        }

        if (
            payload.runtimeApplied === true
        ) {

            showToast(
                id,
                "Configuration enregistrée et appliquée immédiatement.",
                "info"
            );

        } else {

            showToast(
                id,
                "Configuration enregistrée. Un redémarrage reste nécessaire.",
                "warning"
            );
        }

        /*
         * Si le nom du stream a changé, on conserve autant que possible
         * la sélection, l'ordre et la taille de la tuile.
         */
        const previousCamera =
            editingCameraId &&
            cameraConfigSnapshot
                ? cameraConfigSnapshot.cameras.find(
                    function(camera) {
                        return camera.id === editingCameraId;
                    }
                )
                : null;

        if (
            previousCamera &&
            previousCamera.streamName !== streamName
        ) {

            selected =
                selected.map(
                    function(stream) {

                        return stream === previousCamera.streamName
                            ? streamName
                            : stream;
                    }
                );

            order =
                order.map(
                    function(stream) {

                        return stream === previousCamera.streamName
                            ? streamName
                            : stream;
                    }
                );

            if (
                sizes[previousCamera.streamName] &&
                !sizes[streamName]
            ) {

                sizes[streamName] =
                    sizes[previousCamera.streamName];

                delete sizes[previousCamera.streamName];
            }

            saveState();
        }

        showCameraConfigList();

        await loadCameraConfiguration();
        await refreshCameras(
            true
        );

    } catch (e) {

        showToast(
            "Configuration",
            e.message ||
                "Enregistrement impossible.",
            "error"
        );

    } finally {

        saveButton.disabled =
            false;
    }
}

async function deleteCameraConfiguration(
    cameraId
) {

    if (
        !window.confirm(
            "Supprimer définitivement la configuration de la caméra « " +
            cameraId +
            " » ?\n\n" +
            "Une sauvegarde application.properties.bak sera créée."
        )
    ) {
        return;
    }

    try {

        const response =
            await apiFetch(
                "/api/config/cameras/" +
                encodeURIComponent(
                    cameraId
                ),
                {
                    method: "DELETE"
                }
            );

        const payload =
            await response.json();

        if (!response.ok) {

            throw new Error(
                payload.error ||
                "Suppression impossible."
            );
        }

        if (
            payload.runtimeApplied === true
        ) {

            showToast(
                cameraId,
                "Configuration supprimée et appliquée immédiatement.",
                "info"
            );

        } else {

            showToast(
                cameraId,
                "Configuration supprimée. Un redémarrage reste nécessaire.",
                "warning"
            );
        }

        await loadCameraConfiguration();
        await refreshCameras(
            true
        );

    } catch (e) {

        showToast(
            "Configuration",
            e.message ||
                "Suppression impossible.",
            "error"
        );
    }
}

function createActionId() {

    if (
        window.crypto &&
        typeof window.crypto.randomUUID ===
            "function"
    ) {

        return window.crypto.randomUUID();
    }

    return "action-" +
        Date.now() +
        "-" +
        Math.random()
            .toString(16)
            .slice(2);
}

function loadActionTiles() {

    try {

        const raw =
            JSON.parse(
                localStorage.getItem(
                    STORAGE_ACTION_TILES
                ) ||
                "[]"
            );

        if (
            !Array.isArray(raw)
        ) {

            actionTiles =
                [];

            return;
        }

        actionTiles =
            raw
                .filter(
                    function(item) {

                        return (
                            item &&
                            typeof item.id === "string" &&
                            typeof item.name === "string" &&
                            typeof item.url === "string" &&
                            typeof item.method === "string"
                        );
                    }
                )
                .map(
                    function(item) {

                        const method =
                            item.method
                                .toUpperCase();

                        return {
                            id: item.id,
                            name: item.name,
                            url: item.url,
                            method:
                                [
                                    "GET",
                                    "POST",
                                    "PUT",
                                    "PATCH",
                                    "DELETE"
                                ].includes(method)
                                    ? method
                                    : "GET",
                            active:
                                item.active !== false
                        };
                    }
                );

    } catch (_) {

        actionTiles =
            [];
    }
}

function saveActionTiles() {

    localStorage.setItem(
        STORAGE_ACTION_TILES,
        JSON.stringify(
            actionTiles
        )
    );
}

function actionById(
    id
) {

    return actionTiles.find(
        function(item) {

            return item.id === id;
        }
    ) ||
        null;
}

async function executeAction(
    action,
    button
) {

    if (
        !action ||
        !action.url
    ) {
        return;
    }

    if (button) {

        button.disabled =
            true;

        button.classList.add(
            "busy"
        );
    }

    try {

        const body =
            new URLSearchParams();

        body.set(
            "method",
            action.method
        );

        body.set(
            "url",
            action.url
        );

        const response =
            await apiFetch(
                "/api/http-action",
                {
                    method: "POST",
                    headers: {
                        "Content-Type":
                            "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body:
                        body.toString()
                }
            );

        let payload =
            null;

        try {

            payload =
                await response.json();

        } catch (_) {
        }

        if (
            !response.ok ||
            !payload ||
            payload.success !== true
        ) {

            throw new Error(
                (
                    payload &&
                    payload.error
                )
                    ? payload.error
                    : "Échec de l'appel HTTP"
            );
        }

        showToast(
            action.name,
            action.method +
                " envoyé — HTTP " +
                payload.status,
            "info"
        );

    } catch (e) {

        showToast(
            action.name,
            e.message ||
                "Impossible d'exécuter le bouton.",
            "error"
        );

    } finally {

        if (button) {

            button.disabled =
                false;

            button.classList.remove(
                "busy"
            );
        }
    }
}

function createActionTile(
    action
) {

    const tile =
        document.createElement(
            "section"
        );

    tile.className =
        "action-tile";

    tile.dataset.actionId =
        action.id;

    const header =
        document.createElement(
            "div"
        );

    header.className =
        "action-tile-header";

    const method =
        document.createElement(
            "span"
        );

    method.className =
        "action-method";

    method.textContent =
        action.method;

    const spacer =
        document.createElement(
            "div"
        );

    spacer.className =
        "action-spacer";

    const settings =
        document.createElement(
            "button"
        );

    settings.className =
        "action-icon-button";

    settings.type =
        "button";

    settings.title =
        "Configurer ce bouton";

    settings.textContent =
        "⚙";

    settings.addEventListener(
        "click",
        function() {

            openActionEditor(
                action.id
            );
        }
    );

    const hide =
        document.createElement(
            "button"
        );

    hide.className =
        "action-icon-button";

    hide.type =
        "button";

    hide.title =
        "Masquer ce bouton";

    hide.textContent =
        "×";

    hide.addEventListener(
        "click",
        function() {

            action.active =
                false;

            saveActionTiles();
            renderActionTiles();

            showToast(
                action.name,
                "Bouton masqué. Il restera disponible via « + Bouton ».",
                "info"
            );
        }
    );

    const trigger =
        document.createElement(
            "button"
        );

    trigger.className =
        "action-trigger";

    trigger.type =
        "button";

    trigger.textContent =
        action.name;

    trigger.title =
        action.url;

    trigger.addEventListener(
        "click",
        function() {

            executeAction(
                action,
                trigger
            );
        }
    );

    header.appendChild(
        method
    );

    header.appendChild(
        spacer
    );

    header.appendChild(
        settings
    );

    header.appendChild(
        hide
    );

    tile.appendChild(
        header
    );

    tile.appendChild(
        trigger
    );

    return tile;
}

function renderActionTiles() {

    const wall =
        document.getElementById(
            "action-wall"
        );

    wall.innerHTML =
        "";

    actionTiles
        .filter(
            function(action) {

                return action.active;
            }
        )
        .forEach(
            function(action) {

                wall.appendChild(
                    createActionTile(
                        action
                    )
                );
            }
        );
}

function openActionModal(
    editId
) {

    editingActionId =
        editId ||
        null;

    const action =
        editingActionId
            ? actionById(
                editingActionId
            )
            : null;

    document.getElementById(
        "action-modal-title"
    ).textContent =
        action
            ? "Configurer le bouton"
            : "Ajouter un bouton";

    document.getElementById(
        "saved-actions-section"
    ).style.display =
        action
            ? "none"
            : "";

    document.getElementById(
        "action-name"
    ).value =
        action
            ? action.name
            : "";

    document.getElementById(
        "action-method"
    ).value =
        action
            ? action.method
            : "GET";

    document.getElementById(
        "action-url"
    ).value =
        action
            ? action.url
            : "";

    if (!action) {

        renderSavedActions();
    }

    document.getElementById(
        "action-modal-backdrop"
    ).classList.add(
        "open"
    );

    setTimeout(
        function() {

            document.getElementById(
                "action-name"
            ).focus();
        },
        0
    );
}

function openActionEditor(
    id
) {

    openActionModal(
        id
    );
}

function closeActionModal() {

    editingActionId =
        null;

    document.getElementById(
        "action-modal-backdrop"
    ).classList.remove(
        "open"
    );
}

function renderSavedActions() {

    const container =
        document.getElementById(
            "saved-actions"
        );

    container.innerHTML =
        "";

    const archived =
        actionTiles.filter(
            function(action) {

                return !action.active;
            }
        );

    if (
        archived.length === 0
    ) {

        const empty =
            document.createElement(
                "div"
            );

        empty.className =
            "saved-actions-empty";

        empty.textContent =
            "Aucun ancien bouton à restaurer.";

        container.appendChild(
            empty
        );

        return;
    }

    archived.forEach(
        function(action) {

            const row =
                document.createElement(
                    "div"
                );

            row.className =
                "saved-action";

            const info =
                document.createElement(
                    "div"
                );

            info.className =
                "saved-action-info";

            const name =
                document.createElement(
                    "div"
                );

            name.className =
                "saved-action-name";

            name.textContent =
                action.name;

            const detail =
                document.createElement(
                    "div"
                );

            detail.className =
                "saved-action-detail";

            detail.textContent =
                action.method +
                " — " +
                action.url;

            info.appendChild(
                name
            );

            info.appendChild(
                detail
            );

            const buttons =
                document.createElement(
                    "div"
                );

            buttons.className =
                "saved-action-buttons";

            const restore =
                document.createElement(
                    "button"
                );

            restore.type =
                "button";

            restore.textContent =
                "Restaurer";

            restore.addEventListener(
                "click",
                function() {

                    action.active =
                        true;

                    saveActionTiles();
                    renderActionTiles();
                    renderSavedActions();

                    showToast(
                        action.name,
                        "Bouton restauré.",
                        "info"
                    );
                }
            );

            const remove =
                document.createElement(
                    "button"
                );

            remove.type =
                "button";

            remove.className =
                "danger";

            remove.textContent =
                "Supprimer définitivement";

            remove.addEventListener(
                "click",
                function() {

                    if (
                        !window.confirm(
                            "Supprimer définitivement le bouton « " +
                            action.name +
                            " » ?"
                        )
                    ) {
                        return;
                    }

                    actionTiles =
                        actionTiles.filter(
                            function(item) {

                                return item.id !== action.id;
                            }
                        );

                    saveActionTiles();
                    renderSavedActions();
                }
            );

            buttons.appendChild(
                restore
            );

            buttons.appendChild(
                remove
            );

            row.appendChild(
                info
            );

            row.appendChild(
                buttons
            );

            container.appendChild(
                row
            );
        }
    );
}

function saveActionFromModal() {

    const name =
        document.getElementById(
            "action-name"
        )
            .value
            .trim();

    const method =
        document.getElementById(
            "action-method"
        )
            .value
            .trim()
            .toUpperCase();

    const url =
        document.getElementById(
            "action-url"
        )
            .value
            .trim();

    if (!name) {

        showToast(
            "Bouton",
            "Donnez un nom au bouton.",
            "warning"
        );

        return;
    }

    if (
        !/^https?:\/\//i.test(
            url
        )
    ) {

        showToast(
            name,
            "L'URL doit commencer par http:// ou https://.",
            "warning"
        );

        return;
    }

    const allowed =
        [
            "GET",
            "POST",
            "PUT",
            "PATCH",
            "DELETE"
        ];

    if (
        !allowed.includes(
            method
        )
    ) {

        showToast(
            name,
            "Méthode HTTP invalide.",
            "warning"
        );

        return;
    }

    if (
        editingActionId
    ) {

        const action =
            actionById(
                editingActionId
            );

        if (!action) {

            closeActionModal();
            return;
        }

        action.name =
            name;

        action.method =
            method;

        action.url =
            url;

        action.active =
            true;

    } else {

        actionTiles.push(
            {
                id:
                    createActionId(),
                name:
                    name,
                method:
                    method,
                url:
                    url,
                active:
                    true
            }
        );
    }

    saveActionTiles();
    renderActionTiles();
    closeActionModal();
}

function stateText(
    state
) {

    switch (
        state
    ) {

        case "active":
            return "Direct actif";

        case "standby":
            return "En veille";

        default:
            return "Indisponible";
    }
}

function renderChoices() {

    const container =
        document.getElementById(
            "camera-choices"
        );

    container.innerHTML =
        "";

    cameras.forEach(
        function(camera) {

            const label =
                document.createElement(
                    "label"
                );

            label.className =
                "camera-choice";

            const checkbox =
                document.createElement(
                    "input"
                );

            checkbox.type =
                "checkbox";

            checkbox.checked =
                selected.includes(
                    camera.stream
                );

            checkbox.addEventListener(
                "change",
                function() {

                    setCameraVisible(
                        camera.stream,
                        checkbox.checked
                    );
                }
            );

            const dot =
                document.createElement(
                    "span"
                );

            dot.className =
                "state-dot " +
                camera.state;

            const name =
                document.createElement(
                    "span"
                );

            name.textContent =
                camera.id;

            label.appendChild(
                checkbox
            );

            label.appendChild(
                dot
            );

            label.appendChild(
                name
            );

            container.appendChild(
                label
            );
        }
    );
}

function setCameraVisible(
    stream,
    visible
) {

    if (
        visible
    ) {

        if (
            !selected.includes(
                stream
            )
        ) {

            selected.push(
                stream
            );
        }

    } else {

        selected =
            selected.filter(
                function(value) {
                    return value !== stream;
                }
            );
    }

    saveState();

    renderChoices();
    syncWall();
}

function createTile(
    camera
) {

    const shell =
        document.createElement(
            "div"
        );

    shell.className =
        "tile-shell";

    shell.dataset.stream =
        camera.stream;

    const tile =
        document.createElement(
            "section"
        );

    tile.className =
        "tile";

    tile.dataset.stream =
        camera.stream;

    const savedSize =
        sizes[camera.stream];

    if (
        savedSize &&
        Number.isFinite(savedSize.width)
    ) {

        tile.style.width =
            Math.max(
                320,
                savedSize.width
            ) +
            "px";
    }

    const header =
        document.createElement(
            "div"
        );

    header.className =
        "tile-header";

    header.draggable =
        true;

    header.title =
        "Glisser pour déplacer";

    const name =
        document.createElement(
            "div"
        );

    name.className =
        "tile-name";

    name.textContent =
        camera.id;

    const status =
        document.createElement(
            "div"
        );

    status.className =
        "tile-status " +
        camera.state;

    status.textContent =
        "● " +
        stateText(
            camera.state
        );

    const spacer =
        document.createElement(
            "div"
        );

    spacer.className =
        "tile-spacer";

    const controlButton =
        document.createElement(
            "button"
        );

    controlButton.className =
        "control-toggle";

    controlButton.type =
        "button";

    controlButton.title =
        "Afficher / masquer les contrôles";

    controlButton.textContent =
        "⚙";

    controlButton.draggable =
        false;

    controlButton.addEventListener(
        "pointerdown",
        function(event) {

            event.stopPropagation();
        }
    );

    const closeButton =
        document.createElement(
            "button"
        );

    closeButton.className =
        "close-tile";

    closeButton.type =
        "button";

    closeButton.title =
        "Masquer cette caméra";

    closeButton.textContent =
        "×";

    closeButton.addEventListener(
        "click",
        function(event) {

            event.stopPropagation();

            setCameraVisible(
                camera.stream,
                false
            );
        }
    );

    header.appendChild(
        name
    );

    header.appendChild(
        status
    );

    header.appendChild(
        spacer
    );

    header.appendChild(
        controlButton
    );

    header.appendChild(
        closeButton
    );

    const player =
        document.createElement(
            "div"
        );

    player.className =
        "player";

    const image =
        document.createElement(
            "img"
        );

    image.src =
        liveStreamUrl(
            camera.id
        );

    image.alt =
        "Direct " +
        camera.id;

    image.draggable =
        false;

    const castButton =
        document.createElement(
            "button"
        );

    castButton.className =
        "cast-button";

    castButton.type =
        "button";

    castButton.dataset.cameraId =
        camera.id;

    castButton.innerHTML =
        castIconSvg();

    castButton.title =
        "Diffuser cette caméra sur un écran";

    castButton.setAttribute(
        "aria-label",
        "Diffuser cette caméra sur un écran"
    );

    castButton.addEventListener(
        "click",
        function(event) {

            event.preventDefault();
            event.stopPropagation();

            handleCastButton(
                camera,
                castButton
            );
        }
    );

    const starting =
        document.createElement(
            "div"
        );

    starting.className =
        "starting";

    starting.textContent =
        camera.state === "unavailable"
            ? "Caméra actuellement indisponible."
            : "Démarrage du direct…";

    image.addEventListener(
        "load",
        function() {

            starting.classList.add(
                "hidden"
            );
        }
    );

    image.addEventListener(
        "error",
        function() {

            starting.classList.remove(
                "hidden"
            );

            starting.textContent =
                "Flux indisponible. Vérifiez la caméra et FFmpeg.";
        }
    );

    const controls =
        buildTileControls(
            camera
        );

    controlButton.addEventListener(
        "click",
        function(event) {

            event.preventDefault();
            event.stopPropagation();

            const open =
                !controls.classList.contains(
                    "open"
                );

            /*
             * Une seule tuile ouverte à la fois pour ne pas masquer
             * inutilement plusieurs vidéos.
             */
            document.querySelectorAll(
                ".tile-controls.open"
            ).forEach(
                function(panel) {

                    panel.classList.remove(
                        "open"
                    );

                    const parentTile =
                        panel.closest(
                            ".tile-shell"
                        );

                    if (parentTile) {

                        const otherButton =
                            parentTile.querySelector(
                                ".control-toggle"
                            );

                        if (otherButton) {

                            otherButton.classList.remove(
                                "active"
                            );
                        }
                    }
                }
            );

            if (
                open
            ) {

                controls.classList.add(
                    "open"
                );

                controlButton.classList.add(
                    "active"
                );

            } else {

                controlButton.classList.remove(
                    "active"
                );
            }
        }
    );

    const resizeNote =
        document.createElement(
            "div"
        );

    resizeNote.className =
        "resize-note";

    resizeNote.textContent =
        "↔";

    player.appendChild(
        image
    );

    player.appendChild(
        starting
    );

    player.appendChild(
        castButton
    );

    tile.appendChild(
        header
    );

    tile.appendChild(
        player
    );

    tile.appendChild(
        resizeNote
    );

    header.addEventListener(
        "dragstart",
        function(event) {

            draggedStream =
                camera.stream;

            tile.classList.add(
                "dragging"
            );

            if (
                event.dataTransfer
            ) {

                event.dataTransfer.effectAllowed =
                    "move";

                event.dataTransfer.setData(
                    "text/plain",
                    camera.stream
                );
            }
        }
    );

    header.addEventListener(
        "dragend",
        function() {

            draggedStream =
                null;

            document.querySelectorAll(
                ".tile, .tile-shell"
            ).forEach(
                function(node) {

                    node.classList.remove(
                        "dragging"
                    );

                    node.classList.remove(
                        "drop-before"
                    );
                }
            );
        }
    );

    shell.addEventListener(
        "dragover",
        function(event) {

            if (
                !draggedStream ||
                draggedStream === camera.stream
            ) {
                return;
            }

            event.preventDefault();

            shell.classList.add(
                "drop-before"
            );

            if (
                event.dataTransfer
            ) {
                event.dataTransfer.dropEffect =
                    "move";
            }
        }
    );

    shell.addEventListener(
        "dragleave",
        function() {

            shell.classList.remove(
                "drop-before"
            );
        }
    );

    shell.addEventListener(
        "drop",
        function(event) {

            event.preventDefault();

            shell.classList.remove(
                "drop-before"
            );

            const source =
                draggedStream ||
                (
                    event.dataTransfer
                        ? event.dataTransfer.getData(
                            "text/plain"
                        )
                        : ""
                );

            if (
                !source ||
                source === camera.stream
            ) {
                return;
            }

            moveBefore(
                source,
                camera.stream
            );
        }
    );

    if (
        typeof ResizeObserver !==
        "undefined"
    ) {

        const observer =
            new ResizeObserver(
                function(entries) {

                    const entry =
                        entries[0];

                    if (!entry) {
                        return;
                    }

                    const width =
                        Math.round(
                            entry.contentRect.width
                        );

                    if (
                        width < 300
                    ) {
                        return;
                    }

                    sizes[camera.stream] = {
                        width: width
                    };

                    saveState();
                }
            );

        observer.observe(
            tile
        );
    }

    shell.appendChild(
        tile
    );

    shell.appendChild(
        controls
    );

    const currentCast =
        castSessionForCamera(
            camera.id
        );

    if (currentCast) {

        castButton.classList.add(
            "active"
        );

        castButton.title =
            "Arrêter la diffusion sur " +
            currentCast.deviceName;
    }

    return shell;
}

function moveBefore(
    source,
    target
) {

    const sourceIndex =
        order.indexOf(
            source
        );

    const targetIndex =
        order.indexOf(
            target
        );

    if (
        sourceIndex < 0 ||
        targetIndex < 0 ||
        sourceIndex === targetIndex
    ) {
        return;
    }

    order.splice(
        sourceIndex,
        1
    );

    const newTargetIndex =
        order.indexOf(
            target
        );

    order.splice(
        newTargetIndex,
        0,
        source
    );

    saveState();

    /*
     * On change uniquement l'ordre CSS des tuiles.
     * Les connexions MJPEG restent vivantes et les directs ne redémarrent pas.
     */
    applyTileOrder();
}

function applyTileOrder() {

    document.querySelectorAll(
        ".tile-shell"
    ).forEach(
        function(shell) {

            const stream =
                shell.dataset.stream;

            const index =
                order.indexOf(
                    stream
                );

            shell.style.order =
                index >= 0
                    ? String(index)
                    : "9999";
        }
    );
}

function syncWall() {

    const wall =
        document.getElementById(
            "wall"
        );

    const empty =
        wall.querySelector(
            ".empty"
        );

    if (empty) {
        empty.remove();
    }

    /*
     * On ne détruit que les caméras réellement décochées.
     * Les autres lecteurs restent ouverts et ne redémarrent pas.
     */
    wall.querySelectorAll(
        ".tile-shell"
    ).forEach(
        function(tile) {

            if (
                !selected.includes(
                    tile.dataset.stream
                )
            ) {

                const image =
                    tile.querySelector(
                        "img"
                    );

                if (image) {

                    image.removeAttribute(
                        "src"
                    );

                    image.src =
                        "";
                }

                tile.remove();
            }
        }
    );

    const visibleStreams =
        order.filter(
            function(stream) {

                return selected.includes(
                    stream
                );
            }
        );

    visibleStreams.forEach(
        function(stream) {

            let tile =
                wall.querySelector(
                    '.tile-shell[data-stream="' +
                    CSS.escape(stream) +
                    '"]'
                );

            if (!tile) {

                const camera =
                    cameraByStream(
                        stream
                    );

                if (!camera) {
                    return;
                }

                tile =
                    createTile(
                        camera
                    );

                wall.appendChild(
                    tile
                );
            }
        }
    );

    applyTileOrder();

    if (
        visibleStreams.length === 0
    ) {

        const placeholder =
            document.createElement(
                "div"
            );

        placeholder.className =
            "empty";

        placeholder.textContent =
            "Aucune caméra sélectionnée.";

        wall.appendChild(
            placeholder
        );
    }
}

function renderWall() {

    const wall =
        document.getElementById(
            "wall"
        );

    wall.innerHTML =
        "";

    syncWall();
}

function updateTileStates() {

    document.querySelectorAll(
        ".tile-shell"
    ).forEach(
        function(tile) {

            const stream =
                tile.dataset.stream;

            const camera =
                cameraByStream(
                    stream
                );

            if (!camera) {
                return;
            }

            const status =
                tile.querySelector(
                    ".tile-status"
                );

            if (!status) {
                return;
            }

            status.className =
                "tile-status " +
                camera.state;

            status.textContent =
                "● " +
                stateText(
                    camera.state
                );
        }
    );

    renderChoices();
}

async function refreshCameras(
    rebuild
) {

    try {

        const response =
            await apiFetch(
                "/api/cameras"
            );

        if (!response.ok) {

            throw new Error(
                "HTTP " +
                response.status
            );
        }

        cameras =
            await response.json();

        normalizeState();

        if (
            rebuild
        ) {

            renderChoices();
            renderWall();

        } else {

            updateTileStates();
        }

    } catch (e) {

        showToast(
            "Mur vidéo",
            e.message ||
                "Impossible de charger la liste des caméras.",
            "error"
        );
    }
}

document.getElementById(
    "cast-cancel"
).addEventListener(
    "click",
    closeCastChooser
);

document.getElementById(
    "cast-refresh"
).addEventListener(
    "click",
    loadCastDevices
);

document.getElementById(
    "cast-modal-backdrop"
).addEventListener(
    "pointerdown",
    function(event) {

        if (
            event.target === event.currentTarget
        ) {
            closeCastChooser();
        }
    }
);

document.getElementById(
    "config-button"
).addEventListener(
    "click",
    openCameraConfiguration
);

document.getElementById(
    "camera-config-close"
).addEventListener(
    "click",
    closeCameraConfiguration
);

document.getElementById(
    "camera-config-add"
).addEventListener(
    "click",
    function() {

        openCameraEditor(
            null
        );
    }
);

document.getElementById(
    "camera-config-back"
).addEventListener(
    "click",
    showCameraConfigList
);

document.getElementById(
    "camera-config-save"
).addEventListener(
    "click",
    saveCameraConfiguration
);

document.getElementById(
    "camera-config-password-mode"
).addEventListener(
    "change",
    updateCameraPasswordFields
);

document.getElementById(
    "camera-config-backdrop"
).addEventListener(
    "pointerdown",
    function(event) {

        if (
            event.target ===
            event.currentTarget
        ) {

            closeCameraConfiguration();
        }
    }
);

document.getElementById(
    "add-action-button"
).addEventListener(
    "click",
    function() {

        openActionModal(
            null
        );
    }
);

document.getElementById(
    "action-cancel"
).addEventListener(
    "click",
    closeActionModal
);

document.getElementById(
    "action-save"
).addEventListener(
    "click",
    saveActionFromModal
);

document.getElementById(
    "action-modal-backdrop"
).addEventListener(
    "pointerdown",
    function(event) {

        if (
            event.target ===
            event.currentTarget
        ) {

            closeActionModal();
        }
    }
);

document.getElementById(
    "hide-interface"
).addEventListener(
    "click",
    function() {

        setWallOnly(
            true
        );
    }
);

document.getElementById(
    "show-interface"
).addEventListener(
    "click",
    function() {

        setWallOnly(
            false
        );
    }
);

document.getElementById(
    "token-button"
).addEventListener(
    "click",
    changeToken
);

document.getElementById(
    "select-all"
).addEventListener(
    "click",
    function() {

        selected =
            cameras.map(
                function(camera) {
                    return camera.stream;
                }
            );

        saveState();

        renderChoices();
        syncWall();
    }
);

document.getElementById(
    "select-none"
).addEventListener(
    "click",
    function() {

        selected =
            [];

        saveState();

        renderChoices();
        syncWall();
    }
);

/*
 * Extension MQTT : doit être chargée après les fonctions du dashboard
 * mais avant le premier renderActionTiles()/refreshCameras().
 */
${MqttDashboardUi.javascript()}

selected =
    loadArray(
        STORAGE_SELECTED
    );

order =
    loadArray(
        STORAGE_ORDER
    );

sizes =
    loadObject(
        STORAGE_SIZES
    );

loadActionTiles();
renderActionTiles();

setWallOnly(
    localStorage.getItem(
        STORAGE_WALL_ONLY
    ) === "1"
);

refreshCameras(
    true
);

refreshCastStatus();

window.addEventListener(
    "pointerup",
    stopPtzMove
);

window.addEventListener(
    "pointercancel",
    stopPtzMove
);

window.addEventListener(
    "blur",
    stopPtzMove
);

setInterval(
    function() {

        refreshCameras(
            false
        );
    },
    5000
);

setInterval(
    function() {

        refreshCastStatus();
    },
    5000
);

</script>

</body>

</html>
        """.trimIndent()
    }

    private fun isGet(
        exchange: HttpExchange
    ): Boolean {

        return exchange.requestMethod.equals(
            "GET",
            ignoreCase = true
        )
    }

    private fun isGetOrPost(
        exchange: HttpExchange
    ): Boolean {

        return exchange.requestMethod.equals(
            "GET",
            ignoreCase = true
        ) ||
                exchange.requestMethod.equals(
                    "POST",
                    ignoreCase = true
                )
    }

    private fun methodNotAllowed(
        exchange: HttpExchange
    ) {

        sendJson(
            exchange,
            405,
            jsonError(
                "Méthode HTTP non autorisée"
            )
        )
    }

    private fun sendJpeg(
        exchange: HttpExchange,
        jpeg: ByteArray,
        timestamp: Long
    ) {

        exchange.responseHeaders.set(
            "Content-Type",
            "image/jpeg"
        )

        exchange.responseHeaders.set(
            "Cache-Control",
            "no-store"
        )

        exchange.responseHeaders.set(
            "X-Snapshot-Timestamp",
            timestamp.toString()
        )

        exchange.sendResponseHeaders(
            200,
            jpeg.size.toLong()
        )

        exchange.responseBody.use { output ->
            output.write(jpeg)
        }
    }

    private fun sendJson(
        exchange: HttpExchange,
        status: Int,
        json: String
    ) {

        send(
            exchange,
            status,
            "application/json; charset=UTF-8",
            json
        )
    }

    private fun sendHtml(
        exchange: HttpExchange,
        html: String
    ) {

        send(
            exchange,
            200,
            "text/html; charset=UTF-8",
            html
        )
    }

    private fun send(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        content: String
    ) {

        val bytes =
            content.toByteArray(
                StandardCharsets.UTF_8
            )

        exchange.responseHeaders.set(
            "Content-Type",
            contentType
        )

        exchange.responseHeaders.set(
            "Cache-Control",
            "no-store"
        )

        exchange.sendResponseHeaders(
            status,
            bytes.size.toLong()
        )

        exchange.responseBody.use {
                output ->

            output.write(
                bytes
            )
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
    }

    override fun close() {

        if (
            !running.getAndSet(
                false
            )
        ) {
            return
        }

        log.info(
            "Arrêt de l'API caméras"
        )

        server
            ?.stop(
                1
            )

        server =
            null

        try {
            mqttFeature.close()
        } catch (_: Exception) {
        }

        executor.shutdownNow()

        log.info(
            "API caméras arrêtée"
        )
    }
}