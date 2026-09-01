package fr.nachidel.xiaovv

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fr.nachidel.xiaovv.camera.CameraSupervisor
import fr.nachidel.xiaovv.logging.logger
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraApiServer(
    supervisors: Collection<CameraSupervisor>,
    private val bindAddress: String,
    private val port: Int,
    private val apiToken: String
) : Closeable {

    private val log =
        logger<CameraApiServer>()

    private val running =
        AtomicBoolean(false)

    private val cameras =
        supervisors.associateBy {
            it.config.id
        }

    private var server:
            HttpServer? =
        null

    private val executor =
        Executors.newCachedThreadPool()

    /*
     * ============================================================
     * START
     * ============================================================
     */

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

            running.set(
                false
            )

            throw e
        }
    }

    /*
     * ============================================================
     * ROUTAGE
     * ============================================================
     */

    private fun handle(
        exchange: HttpExchange
    ) {

        try {

            exchange.responseHeaders.add(
                "Access-Control-Allow-Origin",
                "*"
            )

            exchange.responseHeaders.add(
                "Access-Control-Allow-Methods",
                "GET, POST, OPTIONS"
            )

            exchange.responseHeaders.add(
                "Access-Control-Allow-Headers",
                "Content-Type, X-API-Token, Authorization"
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

            /*
             * ====================================================
             * PAGE WEB
             * ====================================================
             *
             * La page ne contient aucune donnée sensible.
             *
             * Elle demandera le token lorsqu'elle appellera
             * ensuite /api/cameras.
             */

            if (
                path.isEmpty()
            ) {

                sendHtml(
                    exchange,
                    buildControlPage()
                )

                return
            }

            /*
             * ====================================================
             * AUTHENTIFICATION API
             * ====================================================
             */

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
             * ====================================================
             * LISTE
             * ====================================================
             */

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
                cameras[cameraId]

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

            /*
             * ====================================================
             * STATUS
             * ====================================================
             */

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

            /*
             * ====================================================
             * PTZ
             * ====================================================
             */

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

            /*
             * ====================================================
             * LUMIÈRE
             * ====================================================
             */

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

            /*
             * ====================================================
             * IMAGE
             * ====================================================
             */

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

    /*
     * ============================================================
     * AUTHENTIFICATION
     * ============================================================
     */

    private fun isAuthorized(
        exchange: HttpExchange
    ): Boolean {

        /*
         * Méthode 1 :
         *
         * X-API-Token: ...
         */
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

        /*
         * Méthode 2 :
         *
         * Authorization: Bearer ...
         */
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

        return false
    }

    /*
     * Comparaison en temps constant.
     */
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
     * PTZ
     * ============================================================
     */

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

    /*
     * ============================================================
     * LUMIÈRE
     * ============================================================
     */

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

    /*
     * ============================================================
     * IMAGE
     * ============================================================
     */

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

    /*
     * ============================================================
     * RÉSULTAT COMMANDE
     * ============================================================
     */

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

        } else {

            sendJson(
                exchange,
                503,
                """
                {
                  "success": false,
                  "camera": "${jsonEscape(camera.config.id)}",
                  "error": "camera offline"
                }
                """.trimIndent()
            )
        }
    }

    /*
     * ============================================================
     * LISTE CAMÉRAS
     * ============================================================
     */

    private fun buildCameraListJson():
            String {

        return cameras
            .values
            .sortedBy {
                it.config.id
            }
            .joinToString(
                prefix =
                    "[",

                postfix =
                    "]",

                separator =
                    ","
            ) { camera ->

                """
                {
                  "id": "${jsonEscape(camera.config.id)}",
                  "stream": "${jsonEscape(camera.config.streamName)}",
                  "running": ${camera.isRunning()},
                  "connected": ${camera.isConnected()}
                }
                """.trimIndent()
            }
    }

    /*
     * ============================================================
     * STATUS
     * ============================================================
     */

    private fun cameraStatusJson(
        camera: CameraSupervisor
    ): String {

        return """
        {
          "id": "${jsonEscape(camera.config.id)}",
          "stream": "${jsonEscape(camera.config.streamName)}",
          "host": "${jsonEscape(camera.config.host)}",
          "running": ${camera.isRunning()},
          "connected": ${camera.isConnected()}
        }
        """.trimIndent()
    }

    /*
     * ============================================================
     * PAGE WEB
     * ============================================================
     */

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

.token-button {
    padding: 10px 14px;

    border: 0;
    border-radius: 8px;

    background: #41464d;
    color: white;

    cursor: pointer;
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
}

.online {
    color: #70d88b;
}

.offline {
    color: #ef7777;
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

/*
 * GROUPES
 */

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

<div class="header">

    <div>

        <h1>
            Xiaovv
        </h1>

        <div class="subtitle">
            Pilotage des caméras
        </div>

    </div>

    <button
        class="token-button"
        onclick="changeToken()"
    >
        Jeton API
    </button>

</div>

<div id="cameras">
    Chargement...
</div>

<script>

let activeCamera = null;

/*
 * ============================================================
 * TOKEN
 * ============================================================
 */

function getToken() {

    let token =
        sessionStorage.getItem(
            "xiaovvApiToken"
        );

    if (!token) {

        token =
            window.prompt(
                "Jeton API Xiaovv :"
            ) || "";

        if (token) {

            sessionStorage.setItem(
                "xiaovvApiToken",
                token
            );
        }
    }

    return token;
}

function changeToken() {

    sessionStorage.removeItem(
        "xiaovvApiToken"
    );

    getToken();

    refresh();
}

/*
 * ============================================================
 * FETCH AUTHENTIFIÉ
 * ============================================================
 */

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

        sessionStorage.removeItem(
            "xiaovvApiToken"
        );

        throw new Error(
            "Jeton API invalide"
        );
    }

    return response;
}

/*
 * ============================================================
 * COMMANDES
 * ============================================================
 */

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

        if (!response.ok) {

            console.error(
                await response.text()
            );
        }

    } catch (e) {

        console.error(
            e
        );
    }
}

/*
 * ============================================================
 * PTZ
 * ============================================================
 */

function startMove(
    camera,
    direction
) {

    activeCamera =
        camera;

    command(
        camera,
        "ptz",
        direction
    );
}

function stopMove() {

    if (
        activeCamera == null
    ) {
        return;
    }

    command(
        activeCamera,
        "ptz",
        "stop"
    );

    activeCamera =
        null;
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

/*
 * ============================================================
 * BOUTON SIMPLE
 * ============================================================
 */

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

/*
 * ============================================================
 * CARTE
 * ============================================================
 */

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

    status.className =
        "status " +
        (
            camera.connected
                ? "online"
                : "offline"
        );

    status.textContent =
        camera.connected
            ? "● Connectée"
            : "● Hors ligne";

    /*
     * PTZ
     */

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

    /*
     * LUMIÈRE
     */

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

    /*
     * IMAGE
     */

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

/*
 * ============================================================
 * REFRESH
 * ============================================================
 */

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

    /*
     * ============================================================
     * HTTP HELPERS
     * ============================================================
     */

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

    /*
     * ============================================================
     * CLOSE
     * ============================================================
     */

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

        executor.shutdownNow()

        log.info(
            "API caméras arrêtée"
        )
    }
}