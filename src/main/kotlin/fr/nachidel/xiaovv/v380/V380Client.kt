package fr.nachidel.xiaovv.v380

import fr.nachidel.xiaovv.logging.logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class V380Client(
    private val host: String,
    private val port: Int = 8800,
    private val deviceId: String,
    private val username: String,
    private val password: String,
    private val videoResolution: Int =
        V380Protocol.RESOLUTION_HIGH
) : Closeable {

    private val log =
        logger<V380Client>()

    init {

        require(host.isNotBlank()) {
            "L'adresse de la caméra ne peut pas être vide"
        }

        require(port in 1..65535) {
            "Port caméra invalide : $port"
        }

        require(deviceId.isNotBlank()) {
            "Le Device ID ne peut pas être vide"
        }

        require(username.isNotBlank()) {
            "Le nom d'utilisateur ne peut pas être vide"
        }

        require(
            videoResolution ==
                    V380Protocol.RESOLUTION_LOW ||
                    videoResolution ==
                    V380Protocol.RESOLUTION_HIGH
        ) {
            "Résolution vidéo V380 invalide : $videoResolution"
        }
    }

    /*
     * ============================================================
     * AUTH
     * ============================================================
     */

    private var authSocket: Socket? =
        null

    private var authInput: BufferedInputStream? =
        null

    private var authOutput: BufferedOutputStream? =
        null

    /*
     * ============================================================
     * STREAM
     * ============================================================
     */

    private var streamSocket: Socket? =
        null

    private var streamInput: BufferedInputStream? =
        null

    private var streamOutput: BufferedOutputStream? =
        null

    @Volatile
    private var mediaStream: V380Stream? =
        null

    private val streamWriteLock =
        Any()

    private val videoListeners =
        CopyOnWriteArrayList<V380Stream.FrameListener>()

    /*
     * ============================================================
     * ÉTAT
     * ============================================================
     */

    private val running =
        AtomicBoolean(false)

    private val authenticated =
        AtomicBoolean(false)

    private val streamConnected =
        AtomicBoolean(false)

    private var loginResponse:
            V380Auth.LoginResponse? =
        null

    private var videoLoginResponse:
            V380Protocol.VideoLoginResponse? =
        null

    /*
     * ============================================================
     * TIMEOUTS
     * ============================================================
     */

    private val connectTimeoutMs =
        5_000

    private val handshakeTimeoutMs =
        5_000

    /*
     * ============================================================
     * COMMANDES
     * ============================================================
     */

    enum class PtzDirection {
        UP,
        DOWN,
        LEFT,
        RIGHT,
        STOP
    }

    enum class LightMode {
        ON,
        OFF,
        AUTO
    }

    enum class ImageMode {
        COLOR,
        BW,
        AUTO
    }

    companion object {

        /*
         * ========================================================
         * PTZ
         * ========================================================
         */

        private val PTZ_STOP =
            byteArrayOf(
                0xAA.toByte(), 0x00, 0x00, 0x00,
                0xE8.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0x00, 0x00, 0x01, 0x00
            )

        private val PTZ_RIGHT =
            byteArrayOf(
                0xAA.toByte(), 0x00, 0x00, 0x00,
                0xE8.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0xE9.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0x00, 0x00, 0x01, 0x00
            )

        private val PTZ_LEFT =
            byteArrayOf(
                0xAA.toByte(), 0x00, 0x00, 0x00,
                0xE8.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0xEA.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0x00, 0x00, 0x01, 0x00
            )

        private val PTZ_UP =
            byteArrayOf(
                0xAA.toByte(), 0x00, 0x00, 0x00,
                0xE8.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0xEB.toByte(), 0x03,
                0x00, 0x00, 0x01, 0x00
            )

        private val PTZ_DOWN =
            byteArrayOf(
                0xAA.toByte(), 0x00, 0x00, 0x00,
                0xE8.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0xE8.toByte(), 0x03,
                0xEC.toByte(), 0x03,
                0x00, 0x00, 0x01, 0x00
            )

        /*
         * ========================================================
         * LUMIÈRE
         * ========================================================
         */

        private val LIGHT_ON =
            byteArrayOf(
                0xC4.toByte(), 0x00, 0x00, 0x00,
                0xE9.toByte(), 0x03,
                0x00, 0x00,
                0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
            )

        private val LIGHT_OFF =
            byteArrayOf(
                0xC4.toByte(), 0x00, 0x00, 0x00,
                0xEA.toByte(), 0x03,
                0x00, 0x00,
                0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
            )

        private val LIGHT_AUTO =
            byteArrayOf(
                0xC4.toByte(), 0x00, 0x00, 0x00,
                0xEB.toByte(), 0x03,
                0x00, 0x00,
                0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
            )

        /*
         * ========================================================
         * MODE IMAGE
         * ========================================================
         */

        private val IMAGE_COLOR =
            byteArrayOf(
                0xC5.toByte(), 0x00, 0x00, 0x00,
                0xE9.toByte(), 0x03,
                0x00, 0x00,
                0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
            )

        private val IMAGE_BW =
            byteArrayOf(
                0xC5.toByte(), 0x00, 0x00, 0x00,
                0xEA.toByte(), 0x03,
                0x00, 0x00,
                0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
            )

        private val IMAGE_AUTO =
            byteArrayOf(
                0xC5.toByte(), 0x00, 0x00, 0x00,
                0xEB.toByte(), 0x03,
                0x00, 0x00,
                0x00, 0x01, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
            )

        /*
         * Cette commande est un basculement :
         * chaque appel retourne l'image de 180°.
         */
        private val IMAGE_FLIP =
            byteArrayOf(
                0xBE.toByte(), 0x00, 0x00, 0x00,
                0xE8.toByte(), 0x03,
                0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00
            )
    }

    /*
     * ============================================================
     * CONNECT
     * ============================================================
     */

    fun connect() {

        val existingSocket =
            authSocket

        if (
            existingSocket != null &&
            existingSocket.isConnected &&
            !existingSocket.isClosed
        ) {

            log.warn(
                "La connexion d'authentification est déjà ouverte"
            )

            return
        }

        closeAuthConnection()

        log.info(
            "Connexion TCP d'authentification à {}:{}",
            host,
            port
        )

        val connection =
            openConnection(
                name = "AUTH"
            )

        authSocket =
            connection.socket

        authInput =
            connection.input

        authOutput =
            connection.output

        log.info(
            "Connexion d'authentification établie"
        )
    }

    /*
     * ============================================================
     * START
     * ============================================================
     */

    fun start() {

        ensureAuthConnected()

        if (
            !running.compareAndSet(
                false,
                true
            )
        ) {

            log.warn(
                "Le client Xiaovv est déjà démarré"
            )

            return
        }

        log.info(
            "Client Xiaovv démarré"
        )

        try {

            authenticate()

            closeAuthConnection()

            openStreamConnection()

            loginVideo()

            startVideoStream()

            startContinuousStream()

            log.info(
                "Flux vidéo V380 démarré"
            )

        } catch (e: Exception) {

            log.error(
                "Erreur pendant l'initialisation V380",
                e
            )

            close()

            throw e
        }
    }

    /*
     * ============================================================
     * AUTHENTIFICATION
     * ============================================================
     */

    private fun authenticate() {

        log.info(
            "Authentification auprès de la caméra..."
        )

        val request =
            V380Auth.buildLoginRequest(
                deviceId = deviceId,
                username = username,
                password = password
            )

        require(
            request.size ==
                    V380Protocol.LOGIN_REQUEST_SIZE
        ) {
            "Taille LOGIN incorrecte : ${request.size}"
        }

        log.debug(
            "AUTH TX {} - {} octets",
            V380Protocol.commandDescription(
                V380Protocol.getCommand(
                    request
                )
            ),
            request.size
        )

        log.debug(
            "AUTH TX Header : {}",
            V380Protocol.hex(
                request.copyOfRange(
                    0,
                    81
                ),
                81
            )
        )

        sendAuth(
            request
        )

        val responseBytes =
            readAuthExact(
                V380Protocol.LOGIN_RESPONSE_SIZE
            )

        V380Protocol.debugPacket(
            "AUTH RX",
            responseBytes
        )

        val response =
            V380Auth.parseLoginResponse(
                responseBytes
            )

        if (
            response.command !=
            V380Protocol.CMD_LOGIN_RESPONSE
        ) {

            throw IllegalStateException(
                "Réponse AUTH inattendue : " +
                        V380Protocol.commandDescription(
                            response.command
                        )
            )
        }

        if (!response.success) {

            throw IllegalStateException(
                "Authentification refusée par la caméra " +
                        "(code ${response.result})"
            )
        }

        loginResponse =
            response

        authenticated.set(
            true
        )

        log.info(
            "Authentification réussie"
        )

        log.info(
            "Version protocole caméra : {}",
            response.protocolVersion
        )
    }

    /*
     * ============================================================
     * CONNEXION STREAM
     * ============================================================
     */

    private fun openStreamConnection() {

        check(
            authenticated.get()
        ) {
            "La caméra doit être authentifiée avant d'ouvrir le flux"
        }

        val existingSocket =
            streamSocket

        if (
            existingSocket != null &&
            existingSocket.isConnected &&
            !existingSocket.isClosed
        ) {

            log.warn(
                "Connexion vidéo déjà ouverte"
            )

            return
        }

        closeStreamConnection()

        log.info(
            "Ouverture de la connexion vidéo à {}:{}",
            host,
            port
        )

        val connection =
            openConnection(
                name = "STREAM"
            )

        streamSocket =
            connection.socket

        streamInput =
            connection.input

        streamOutput =
            connection.output

        streamConnected.set(
            true
        )

        log.info(
            "Connexion vidéo TCP établie"
        )
    }

    /*
     * ============================================================
     * LOGIN VIDEO
     * ============================================================
     */

    private fun loginVideo() {

        ensureStreamConnected()

        val auth =
            loginResponse
                ?: throw IllegalStateException(
                    "Réponse d'authentification absente"
                )

        val numericDeviceId =
            deviceId.toLongOrNull()
                ?: throw IllegalArgumentException(
                    "Device ID invalide : $deviceId"
                )

        log.info(
            "Ouverture de la session vidéo..."
        )

        val request =
            V380Protocol.buildVideoLoginRequest(
                deviceId =
                    numericDeviceId,

                authTicket =
                    auth.authTicket,

                requestFps =
                    V380Protocol.DEFAULT_REQUEST_FPS,

                resolution =
                    videoResolution
            )

        sendStream(
            request
        )

        val responseBytes =
            readStreamExact(
                V380Protocol.VIDEO_LOGIN_RESPONSE_SIZE
            )

        V380Protocol.debugPacket(
            "STREAM RX",
            responseBytes
        )

        val response =
            V380Protocol.parseVideoLoginResponse(
                responseBytes
            )

        if (
            response.command !=
            V380Protocol.CMD_VIDEO_LOGIN_RESPONSE
        ) {

            throw IllegalStateException(
                "Réponse VIDEO_LOGIN inattendue : " +
                        V380Protocol.commandDescription(
                            response.command
                        )
            )
        }

        if (!response.success) {

            throw IllegalStateException(
                "Connexion vidéo refusée " +
                        "(code ${response.result})"
            )
        }

        videoLoginResponse =
            response

        log.info(
            "Session vidéo acceptée"
        )

        log.info(
            "Flux annoncé : {}x{} @ {} fps",
            response.width,
            response.height,
            response.fps
        )
    }

    /*
     * ============================================================
     * DÉMARRAGE VIDEO
     * ============================================================
     */

    private fun startVideoStream() {

        ensureStreamConnected()

        check(
            videoLoginResponse?.success ==
                    true
        ) {
            "La session vidéo doit être ouverte avant de démarrer le flux"
        }

        log.info(
            "Démarrage du flux vidéo..."
        )

        val startRequest =
            V380Protocol.buildStartVideoRequest()

        sendStream(
            startRequest
        )

        Thread.sleep(
            150
        )

        val initRequest =
            V380Protocol.buildStreamInitRequest()

        sendStream(
            initRequest
        )

        log.info(
            "Commandes de démarrage vidéo envoyées"
        )

        log.info(
            "Attente des premières trames..."
        )
    }

    /*
     * ============================================================
     * STREAM CONTINU
     * ============================================================
     */

    private fun startContinuousStream() {

        ensureStreamConnected()

        val input =
            streamInput
                ?: throw IllegalStateException(
                    "Flux STREAM d'entrée non initialisé"
                )

        val auth =
            loginResponse
                ?: throw IllegalStateException(
                    "Ticket de session absent"
                )

        check(
            mediaStream == null
        ) {
            "Le flux média est déjà initialisé"
        }

        log.info(
            "Initialisation du flux H.265 continu"
        )

        val stream =
            V380Stream(
                input =
                    input,

                authTicket =
                    auth.authTicket
            )

        for (
        listener in
        videoListeners
        ) {

            stream.addListener(
                listener
            )
        }

        mediaStream =
            stream

        stream.start()
    }

    /*
     * ============================================================
     * PTZ
     * ============================================================
     */

    fun ptz(
        direction: PtzDirection
    ) {

        val command =
            when (direction) {

                PtzDirection.UP ->
                    PTZ_UP

                PtzDirection.DOWN ->
                    PTZ_DOWN

                PtzDirection.LEFT ->
                    PTZ_LEFT

                PtzDirection.RIGHT ->
                    PTZ_RIGHT

                PtzDirection.STOP ->
                    PTZ_STOP
            }

        sendStream(
            command
        )

        log.info(
            "PTZ : {}",
            direction
        )
    }

    fun ptzUp() {
        ptz(PtzDirection.UP)
    }

    fun ptzDown() {
        ptz(PtzDirection.DOWN)
    }

    fun ptzLeft() {
        ptz(PtzDirection.LEFT)
    }

    fun ptzRight() {
        ptz(PtzDirection.RIGHT)
    }

    fun ptzStop() {
        ptz(PtzDirection.STOP)
    }

    /*
     * ============================================================
     * LUMIÈRE
     * ============================================================
     */

    fun light(
        mode: LightMode
    ) {

        val command =
            when (mode) {

                LightMode.ON ->
                    LIGHT_ON

                LightMode.OFF ->
                    LIGHT_OFF

                LightMode.AUTO ->
                    LIGHT_AUTO
            }

        sendStream(
            command
        )

        log.info(
            "LUMIÈRE : {}",
            mode
        )
    }

    fun lightOn() {
        light(LightMode.ON)
    }

    fun lightOff() {
        light(LightMode.OFF)
    }

    fun lightAuto() {
        light(LightMode.AUTO)
    }

    /*
     * ============================================================
     * MODE IMAGE
     * ============================================================
     */

    fun imageMode(
        mode: ImageMode
    ) {

        val command =
            when (mode) {

                ImageMode.COLOR ->
                    IMAGE_COLOR

                ImageMode.BW ->
                    IMAGE_BW

                ImageMode.AUTO ->
                    IMAGE_AUTO
            }

        sendStream(
            command
        )

        log.info(
            "IMAGE : {}",
            mode
        )
    }

    fun imageColor() {
        imageMode(ImageMode.COLOR)
    }

    fun imageBw() {
        imageMode(ImageMode.BW)
    }

    fun imageAuto() {
        imageMode(ImageMode.AUTO)
    }

    fun imageFlip() {

        sendStream(
            IMAGE_FLIP
        )

        log.info(
            "IMAGE : FLIP"
        )
    }

    /*
     * ============================================================
     * SOCKET
     * ============================================================
     */

    private data class Connection(
        val socket: Socket,
        val input: BufferedInputStream,
        val output: BufferedOutputStream
    )

    private fun openConnection(
        name: String
    ): Connection {

        val socket =
            Socket()

        try {

            socket.tcpNoDelay =
                true

            socket.keepAlive =
                true

            socket.soTimeout =
                handshakeTimeoutMs

            socket.connect(
                InetSocketAddress(
                    host,
                    port
                ),
                connectTimeoutMs
            )

            val input =
                BufferedInputStream(
                    socket.getInputStream()
                )

            val output =
                BufferedOutputStream(
                    socket.getOutputStream()
                )

            log.debug(
                "{} connexion ouverte {}:{}",
                name,
                socket.inetAddress.hostAddress,
                socket.port
            )

            return Connection(
                socket =
                    socket,

                input =
                    input,

                output =
                    output
            )

        } catch (e: Exception) {

            try {
                socket.close()
            } catch (_: Exception) {
            }

            throw e
        }
    }

    /*
     * ============================================================
     * AUTH IO
     * ============================================================
     */

    private fun sendAuth(
        data: ByteArray
    ) {

        val output =
            authOutput
                ?: throw IllegalStateException(
                    "Flux AUTH de sortie non initialisé"
                )

        output.write(
            data
        )

        output.flush()
    }

    private fun readAuthExact(
        size: Int
    ): ByteArray {

        val input =
            authInput
                ?: throw IllegalStateException(
                    "Flux AUTH d'entrée non initialisé"
                )

        return readExact(
            name =
                "AUTH",

            input =
                input,

            size =
                size
        )
    }

    /*
     * ============================================================
     * STREAM IO
     * ============================================================
     */

    private fun sendStream(
        data: ByteArray
    ) {

        ensureStreamConnected()

        val output =
            streamOutput
                ?: throw IllegalStateException(
                    "Flux STREAM de sortie non initialisé"
                )

        try {

            synchronized(
                streamWriteLock
            ) {

                output.write(
                    data
                )

                output.flush()
            }

            log.debug(
                "STREAM TX {} octets",
                data.size
            )

        } catch (e: SocketException) {

            streamConnected.set(
                false
            )

            log.error(
                "Connexion vidéo perdue pendant l'envoi",
                e
            )

            throw e
        }
    }

    private fun readStreamExact(
        size: Int
    ): ByteArray {

        ensureStreamConnected()

        val input =
            streamInput
                ?: throw IllegalStateException(
                    "Flux STREAM d'entrée non initialisé"
                )

        return readExact(
            name =
                "STREAM",

            input =
                input,

            size =
                size
        )
    }

    private fun readExact(
        name: String,
        input: BufferedInputStream,
        size: Int
    ): ByteArray {

        require(
            size > 0
        )

        val buffer =
            ByteArray(
                size
            )

        var offset =
            0

        while (
            offset <
            size
        ) {

            val count =
                input.read(
                    buffer,
                    offset,
                    size - offset
                )

            if (
                count == -1
            ) {

                throw SocketException(
                    "$name : connexion fermée par la caméra"
                )
            }

            offset +=
                count
        }

        return buffer
    }

    /*
     * ============================================================
     * ÉTAT
     * ============================================================
     */

    private fun ensureAuthConnected() {

        val socket =
            authSocket

        if (
            socket == null ||
            socket.isClosed ||
            !socket.isConnected
        ) {

            throw IllegalStateException(
                "Connexion AUTH non disponible"
            )
        }
    }

    private fun ensureStreamConnected() {

        val socket =
            streamSocket

        if (
            !streamConnected.get() ||
            socket == null ||
            socket.isClosed ||
            !socket.isConnected
        ) {

            throw IllegalStateException(
                "Connexion STREAM non disponible"
            )
        }
    }

    fun isRunning(): Boolean {
        return running.get()
    }

    fun isAuthenticated(): Boolean {
        return authenticated.get()
    }

    fun isStreamConnected(): Boolean {
        return streamConnected.get()
    }

    fun getAuthTicket(): Int? {
        return loginResponse?.authTicket
    }

    fun getProtocolVersion(): Int? {
        return loginResponse?.protocolVersion
    }

    fun getVideoWidth(): Int? {
        return videoLoginResponse?.width
    }

    fun getVideoHeight(): Int? {
        return videoLoginResponse?.height
    }

    fun getVideoFps(): Int? {
        return videoLoginResponse?.fps
    }

    /*
     * ============================================================
     * LISTENERS
     * ============================================================
     */

    fun addVideoListener(
        listener: V380Stream.FrameListener
    ) {

        videoListeners.addIfAbsent(
            listener
        )

        mediaStream
            ?.addListener(
                listener
            )
    }

    fun removeVideoListener(
        listener: V380Stream.FrameListener
    ) {

        videoListeners.remove(
            listener
        )

        mediaStream
            ?.removeListener(
                listener
            )
    }

    /*
     * ============================================================
     * CLOSE AUTH
     * ============================================================
     */

    private fun closeAuthConnection() {

        try {
            authInput?.close()
        } catch (_: Exception) {
        }

        try {
            authOutput?.close()
        } catch (_: Exception) {
        }

        try {
            authSocket?.close()
        } catch (_: Exception) {
        }

        authInput =
            null

        authOutput =
            null

        authSocket =
            null
    }

    /*
     * ============================================================
     * CLOSE STREAM
     * ============================================================
     */

    private fun closeStreamConnection() {

        streamConnected.set(
            false
        )

        try {
            streamInput?.close()
        } catch (_: Exception) {
        }

        try {
            streamOutput?.close()
        } catch (_: Exception) {
        }

        try {
            streamSocket?.close()
        } catch (_: Exception) {
        }

        streamInput =
            null

        streamOutput =
            null

        streamSocket =
            null
    }

    /*
     * ============================================================
     * AWAIT
     * ============================================================
     */

    fun await() {

        val stream =
            mediaStream
                ?: throw IllegalStateException(
                    "Le flux vidéo n'est pas démarré"
                )

        log.info(
            "Client en fonctionnement continu"
        )

        stream.await()
    }

    /*
     * ============================================================
     * CLOSE
     * ============================================================
     */

    override fun close() {

        val hadActiveState =
            running.get() ||
                    authenticated.get() ||
                    authSocket != null ||
                    streamSocket != null ||
                    mediaStream != null

        if (hadActiveState) {

            log.info(
                "Fermeture des connexions caméra"
            )
        }

        running.set(
            false
        )

        try {
            mediaStream?.stop()
        } catch (_: Exception) {
        }

        closeStreamConnection()

        closeAuthConnection()

        try {

            mediaStream
                ?.await(
                    2_000
                )

        } catch (_: InterruptedException) {

            Thread.currentThread()
                .interrupt()
        }

        mediaStream =
            null

        authenticated.set(
            false
        )

        streamConnected.set(
            false
        )

        loginResponse =
            null

        videoLoginResponse =
            null

        if (hadActiveState) {

            log.info(
                "Connexions caméra fermées"
            )
        }
    }
}