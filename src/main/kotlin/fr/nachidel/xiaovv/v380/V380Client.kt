package fr.nachidel.xiaovv.v380

import fr.nachidel.xiaovv.logging.logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

class V380Client(
    private val host: String,
    private val port: Int = 8800,
    private val deviceId: String,
    private val username: String,
    private val password: String,
    private val videoResolution: Int = V380Protocol.RESOLUTION_HIGH
) : Closeable {

    private val log = logger<V380Client>()

    /*
     * ============================================================
     * CONNEXION AUTHENTIFICATION
     * ============================================================
     */

    private var authSocket: Socket? = null
    private var authInput: BufferedInputStream? = null
    private var authOutput: BufferedOutputStream? = null

    /*
     * ============================================================
     * CONNEXION STREAM
     * ============================================================
     */

    private var streamSocket: Socket? = null
    private var streamInput: BufferedInputStream? = null
    private var streamOutput: BufferedOutputStream? = null
    private var mediaStream: V380Stream? = null
    private val videoListeners =
        CopyOnWriteArrayList<V380Stream.FrameListener>()

    private val audioListeners =
        CopyOnWriteArrayList<V380Stream.AudioFrameListener>()

    /**
     * Toutes les commandes de contrôle (PTZ, lumière, audio...)
     * partagent la même socket STREAM que les commandes d'initialisation.
     */
    private val streamWriteLock =
        Any()

    /*
     * ============================================================
     * ÉTAT
     * ============================================================
     */

    private val running = AtomicBoolean(false)
    private val authenticated = AtomicBoolean(false)
    private val streamConnected = AtomicBoolean(false)

    private var loginResponse: V380Auth.LoginResponse? = null

    private var videoLoginResponse:
            V380Protocol.VideoLoginResponse? = null


    /*
     * ============================================================
     * TIMEOUTS
     * ============================================================
     */

    private val connectTimeoutMs = 5_000

    /**
     * Pendant les handshakes, on ne veut pas rester bloqué
     * éternellement si la caméra ne répond pas.
     */
    private val handshakeTimeoutMs = 5_000

    /*
     * ============================================================
     * COMMANDES DE CONTRÔLE
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

    /**
     * Ouvre uniquement la connexion d'authentification.
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

        authSocket = connection.socket
        authInput = connection.input
        authOutput = connection.output

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

        if (!running.compareAndSet(false, true)) {

            log.warn(
                "Le client Xiaovv est déjà démarré"
            )

            return
        }

        log.info(
            "Client Xiaovv démarré"
        )

        log.debug(
            "Device ID : {}",
            deviceId
        )

        log.debug(
            "Utilisateur : {}",
            username
        )

        try {

            /*
             * Étape 1 :
             *
             * authentification principale.
             */
            authenticate()

            /*
             * La socket AUTH n'est plus nécessaire.
             */
            closeAuthConnection()

            /*
             * Étape 2 :
             *
             * ouverture de la socket vidéo.
             */
            openStreamConnection()

            /*
             * Étape 3 :
             *
             * login vidéo 301.
             */
            loginVideo()

            startVideoStream()

            startContinuousStream()

            log.info(
                "Flux vidéo V380 démarré"
            )

        } catch (e: Exception) {

            running.set(false)

            log.error(
                "Erreur pendant l'initialisation V380",
                e
            )

            throw e
        }
    }

    /*
     * ============================================================
     * AUTHENTIFICATION 31167
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

        /*
         * On arrête volontairement le dump avant
         * le bloc cryptographique.
         */
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

        log.debug(
            "AUTH attente LOGIN_RESPONSE..."
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

        loginResponse = response
        authenticated.set(true)

        log.info(
            "Authentification réussie"
        )

        log.info(
            "Version protocole caméra : {}",
            response.protocolVersion
        )

        log.debug(
            "Ticket de session : {}",
            response.authTicket
        )
    }

    /*
     * ============================================================
     * CONNEXION STREAM
     * ============================================================
     */

    private fun openStreamConnection() {

        check(authenticated.get()) {
            "La caméra doit être authentifiée avant d'ouvrir le flux"
        }

        if (streamSocket != null) {

            log.warn(
                "Connexion vidéo déjà ouverte"
            )

            return
        }

        log.info(
            "Ouverture de la connexion vidéo à {}:{}",
            host,
            port
        )

        val connection =
            openConnection(
                name = "STREAM"
            )

        streamSocket = connection.socket
        streamInput = connection.input
        streamOutput = connection.output

        streamConnected.set(true)

        log.info(
            "Connexion vidéo TCP établie"
        )
    }

    /*
     * ============================================================
     * VIDEO LOGIN 301
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

        log.debug(
            "Utilisation du ticket de session {}",
            auth.authTicket
        )

        /*
         * Construction du 301.
         *
         * IMPORTANT :
         * authTicket vient de LA SESSION ACTUELLE.
         */
        val request =
            V380Protocol.buildVideoLoginRequest(
                deviceId = numericDeviceId,
                authTicket = auth.authTicket,
                requestFps =
                    V380Protocol.DEFAULT_REQUEST_FPS,
                resolution = videoResolution
            )

        log.debug(
            "STREAM TX {} - {} octets",
            V380Protocol.commandDescription(
                V380Protocol.getCommand(
                    request
                )
            ),
            request.size
        )

        /*
         * Ici le paquet ne contient pas le mot de passe.
         * On peut donc afficher son début intégralement.
         */
        log.debug(
            "STREAM TX Header : {}",
            V380Protocol.hex(
                request.copyOfRange(
                    0,
                    40
                ),
                40
            )
        )

        sendStream(
            request
        )

        log.debug(
            "STREAM attente VIDEO_LOGIN_RESPONSE..."
        )

        /*
         * Dans ton PCAP :
         *
         * réponse 401 = 32 octets.
         */
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
 * DÉMARRAGE DU FLUX VIDÉO
 * ============================================================
 */

    private fun startVideoStream() {

        ensureStreamConnected()

        check(videoLoginResponse?.success == true) {
            "La session vidéo doit être ouverte avant de démarrer le flux"
        }

        log.info(
            "Démarrage du flux vidéo..."
        )

        /*
         * Commande 303.
         *
         * Dans notre PCAP :
         *
         * 2F 01 00 00
         * 01 30 00 00
         * ...
         */
        val startRequest =
            V380Protocol.buildStartVideoRequest()

        log.debug(
            "STREAM TX {} - {} octets",
            V380Protocol.commandDescription(
                V380Protocol.getCommand(
                    startRequest
                )
            ),
            startRequest.size
        )

        log.debug(
            "STREAM TX HEX : {}",
            V380Protocol.hex(
                startRequest,
                32
            )
        )

        sendStream(
            startRequest
        )

        /*
         * L'application officielle attend environ
         * 150 ms dans notre capture avant d'envoyer 8449.
         */
        Thread.sleep(150)

        /*
         * Commande 8449.
         */
        val initRequest =
            V380Protocol.buildStreamInitRequest()

        log.debug(
            "STREAM TX {} - {} octets",
            V380Protocol.commandDescription(
                V380Protocol.getCommand(
                    initRequest
                )
            ),
            initRequest.size
        )

        log.debug(
            "STREAM TX HEX : {}",
            V380Protocol.hex(
                initRequest,
                initRequest.size
            )
        )

        sendStream(
            initRequest
        )

        /*
         * Activation audio observée dans la capture officielle :
         * commande 8449 avec le flag 0x1001.
         */
        Thread.sleep(100)

        val audioRequest =
            V380Protocol.buildAudioControlRequest(
                enabled = true
            )

        log.debug(
            "STREAM TX AUDIO ON : {}",
            V380Protocol.hex(
                audioRequest,
                audioRequest.size
            )
        )

        sendStream(
            audioRequest
        )

        log.info(
            "Commandes de démarrage vidéo + audio envoyées"
        )

        log.info(
            "Attente des premières trames..."
        )
    }


    /*
     * ============================================================
     * TEST DE RÉCEPTION VIDÉO
     * ============================================================
     */

    private fun readFirstVideoFrames(
        frameCount: Int
    ) {

        require(frameCount > 0) {
            "frameCount doit être supérieur à zéro"
        }

        ensureStreamConnected()

        val input =
            streamInput
                ?: throw IllegalStateException(
                    "Flux STREAM d'entrée non initialisé"
                )

        val auth =
            loginResponse
                ?: throw IllegalStateException(
                    "Ticket d'authentification absent"
                )

        /*
         * Parser du protocole V380.
         */
        val parser =
            V380MediaParser()

        /*
         * Décodeur AES / H.265.
         */
        val decoder =
            V380MediaDecoder(
                authTicket = auth.authTicket
            )

        /*
         * Fichier HEVC brut.
         *
         * Il sera créé dans le répertoire
         * depuis lequel le programme est lancé.
         */
        val outputFile =
            "xiaovv-test.h265"

        log.info(
            "Enregistrement du flux H.265 dans {}",
            outputFile
        )

        var receivedVideoFrames = 0
        var writtenBytes = 0L
        var decodedFrames = 0

        BufferedOutputStream(
            FileOutputStream(outputFile)
        ).use { videoOutput ->

            while (
                receivedVideoFrames <
                frameCount
            ) {

                /*
                 * Lecture et assemblage d'une frame complète.
                 */
                val frame =
                    parser.readNextFrame(
                        input
                    )

                /*
                 * On ignore les types non vidéo.
                 */
                if (!frame.isVideo) {

                    log.debug(
                        "MEDIA frame ignorée : type=0x{}, {} octets",
                        frame.type
                            .toString(16)
                            .uppercase()
                            .padStart(2, '0'),
                        frame.data.size
                    )

                    continue
                }

                receivedVideoFrames++

                try {

                    /*
                     * Déchiffrement + retrait du header interne.
                     */
                    val decoded =
                        decoder.decode(
                            frame
                        )

                    decodedFrames++

                    /*
                     * Écriture du bitstream HEVC brut.
                     */
                    videoOutput.write(
                        decoded.payload
                    )

                    writtenBytes +=
                        decoded.payload.size

                    log.info(
                        "VIDEO [{}/{}] type=0x{}, NAL={}, keyframe={}, {} octets",
                        receivedVideoFrames,
                        frameCount,
                        frame.type
                            .toString(16)
                            .uppercase()
                            .padStart(2, '0'),
                        decoded.nalType
                            ?.toString()
                            ?: "?",
                        decoded.keyFrame,
                        decoded.payload.size
                    )

                    /*
                     * Pour les premières frames seulement,
                     * on affiche le début du flux déchiffré.
                     */
                    if (
                        receivedVideoFrames <= 5
                    ) {

                        log.debug(
                            "H265 début frame : {}",
                            V380Protocol.hex(
                                decoded.payload,
                                48
                            )
                        )
                    }

                } catch (e: Exception) {

                    log.error(
                        "Erreur pendant le décodage de la frame {} " +
                                "(type=0x{})",
                        receivedVideoFrames,
                        frame.type
                            .toString(16)
                            .uppercase(),
                        e
                    )

                    throw e
                }
            }

            /*
             * Force l'écriture disque avant fermeture.
             */
            videoOutput.flush()
        }

        log.info(
            "{} frames vidéo reçues",
            receivedVideoFrames
        )

        log.info(
            "{} frames vidéo décodées",
            decodedFrames
        )

        log.info(
            "{} octets H.265 écrits dans {}",
            writtenBytes,
            outputFile
        )

        log.info(
            "Capture H.265 terminée"
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

        check(mediaStream == null) {
            "Le flux média est déjà initialisé"
        }

        log.info(
            "Initialisation du flux H.265 continu"
        )

        val stream =
            V380Stream(
                input = input,
                authTicket = auth.authTicket
            )

        for (listener in videoListeners) {

            stream.addListener(
                listener
            )
        }

        for (listener in audioListeners) {

            stream.addAudioListener(
                listener
            )
        }

        mediaStream =
            stream

        stream.start()
    }

    /*
     * ============================================================
     * AUDIO
     * ============================================================
     */

    fun audioOn() {

        sendStream(
            V380Protocol.buildAudioControlRequest(
                enabled = true
            )
        )

        log.info(
            "AUDIO : ON"
        )
    }

    fun audioOff() {

        sendStream(
            V380Protocol.buildAudioControlRequest(
                enabled = false
            )
        )

        log.info(
            "AUDIO : OFF"
        )
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
                PtzDirection.UP -> PTZ_UP
                PtzDirection.DOWN -> PTZ_DOWN
                PtzDirection.LEFT -> PTZ_LEFT
                PtzDirection.RIGHT -> PTZ_RIGHT
                PtzDirection.STOP -> PTZ_STOP
            }

        sendStream(command)

        log.info(
            "PTZ : {}",
            direction
        )
    }

    fun ptzUp() = ptz(PtzDirection.UP)
    fun ptzDown() = ptz(PtzDirection.DOWN)
    fun ptzLeft() = ptz(PtzDirection.LEFT)
    fun ptzRight() = ptz(PtzDirection.RIGHT)
    fun ptzStop() = ptz(PtzDirection.STOP)

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
                LightMode.ON -> LIGHT_ON
                LightMode.OFF -> LIGHT_OFF
                LightMode.AUTO -> LIGHT_AUTO
            }

        sendStream(command)

        log.info(
            "LUMIÈRE : {}",
            mode
        )
    }

    fun lightOn() = light(LightMode.ON)
    fun lightOff() = light(LightMode.OFF)
    fun lightAuto() = light(LightMode.AUTO)

    /*
     * ============================================================
     * IMAGE
     * ============================================================
     */

    fun imageMode(
        mode: ImageMode
    ) {

        val command =
            when (mode) {
                ImageMode.COLOR -> IMAGE_COLOR
                ImageMode.BW -> IMAGE_BW
                ImageMode.AUTO -> IMAGE_AUTO
            }

        sendStream(command)

        log.info(
            "IMAGE : {}",
            mode
        )
    }

    fun imageColor() = imageMode(ImageMode.COLOR)
    fun imageBw() = imageMode(ImageMode.BW)
    fun imageAuto() = imageMode(ImageMode.AUTO)

    fun imageFlip() {
        sendStream(IMAGE_FLIP)
        log.info("IMAGE : FLIP")
    }

    /*
     * ============================================================
     * OUVERTURE SOCKET
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

            socket.tcpNoDelay = true
            socket.keepAlive = true

            socket.soTimeout =
                handshakeTimeoutMs

            log.debug(
                "{} ouverture socket TCP - timeout={} ms",
                name,
                connectTimeoutMs
            )

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
                "{} adresse locale : {}:{}",
                name,
                socket.localAddress.hostAddress,
                socket.localPort
            )

            log.debug(
                "{} adresse distante : {}:{}",
                name,
                socket.inetAddress.hostAddress,
                socket.port
            )

            return Connection(
                socket = socket,
                input = input,
                output = output
            )

        } catch (e: Exception) {

            try {
                socket.close()
            } catch (_: Exception) {
            }

            log.error(
                "{} impossible d'ouvrir la connexion à {}:{}",
                name,
                host,
                port,
                e
            )

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

        log.debug(
            "AUTH TX {} octets",
            data.size
        )
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
            name = "AUTH",
            input = input,
            size = size
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

            streamConnected.set(false)

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
            name = "STREAM",
            input = input,
            size = size
        )
    }

    /*
     * ============================================================
     * LECTURE EXACTE TCP
     * ============================================================
     */

    private fun readExact(
        name: String,
        input: BufferedInputStream,
        size: Int
    ): ByteArray {

        require(size > 0) {
            "La taille doit être supérieure à zéro"
        }

        val buffer =
            ByteArray(size)

        var offset = 0

        while (offset < size) {

            val count =
                input.read(
                    buffer,
                    offset,
                    size - offset
                )

            if (count == -1) {

                throw SocketException(
                    "$name : connexion fermée par la caméra"
                )
            }

            offset += count

            log.debug(
                "{} RX fragment : {} octets ({}/{})",
                name,
                count,
                offset,
                size
            )
        }

        log.debug(
            "{} RX paquet complet : {} octets",
            name,
            size
        )

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

    fun addVideoListener(
        listener: V380Stream.FrameListener
    ) {

        videoListeners.addIfAbsent(
            listener
        )

        /*
         * Si le stream existe déjà, branche immédiatement.
         *
         * Sinon il sera branché automatiquement
         * lors du prochain démarrage.
         */
        mediaStream?.addListener(
            listener
        )
    }

    fun removeVideoListener(
        listener: V380Stream.FrameListener
    ) {

        videoListeners.remove(
            listener
        )

        mediaStream?.removeListener(
            listener
        )
    }

    fun addAudioListener(
        listener: V380Stream.AudioFrameListener
    ) {

        audioListeners.addIfAbsent(
            listener
        )

        mediaStream?.addAudioListener(
            listener
        )
    }

    fun removeAudioListener(
        listener: V380Stream.AudioFrameListener
    ) {

        audioListeners.remove(
            listener
        )

        mediaStream?.removeAudioListener(
            listener
        )
    }

    /*
     * ============================================================
     * FERMETURE AUTH
     * ============================================================
     */

    private fun closeAuthConnection() {

        if (
            authSocket == null &&
            authInput == null &&
            authOutput == null
        ) {
            return
        }

        log.debug(
            "Fermeture de la connexion AUTH"
        )

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

        authInput = null
        authOutput = null
        authSocket = null

        log.debug(
            "Connexion AUTH fermée"
        )
    }

    /*
     * ============================================================
     * FERMETURE STREAM
     * ============================================================
     */

    private fun closeStreamConnection() {

        streamConnected.set(false)

        if (
            streamSocket == null &&
            streamInput == null &&
            streamOutput == null
        ) {
            return
        }

        log.debug(
            "Fermeture de la connexion STREAM"
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

        streamInput = null
        streamOutput = null
        streamSocket = null

        log.debug(
            "Connexion STREAM fermée"
        )
    }

    fun await() {

        val stream =
            mediaStream
                ?: throw IllegalStateException(
                    "Le flux vidéo n'est pas démarré"
                )

        log.info("Client en fonctionnement continu")

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

        running.set(false)

        try {
            mediaStream?.stop()
        } catch (_: Exception) {
        }

        closeStreamConnection()
        closeAuthConnection()

        try {
            mediaStream?.await(
                2_000
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        mediaStream = null
        authenticated.set(false)
        streamConnected.set(false)
        loginResponse = null
        videoLoginResponse = null

        if (hadActiveState) {
            log.info(
                "Connexions caméra fermées"
            )
        }
    }

}