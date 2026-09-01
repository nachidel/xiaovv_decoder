package fr.nachidel.xiaovv.rtsp

import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.v380.V380MediaDecoder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import fr.nachidel.xiaovv.h265.H265AnnexB
import fr.nachidel.xiaovv.h265.H265RtpPacketizer
import kotlin.collections.iterator

class RtspServer(
    private val bindAddress: String = "0.0.0.0",
    private val port: Int = 8555
) : Closeable {

    private val log =
        logger<RtspServer>()

    private val running =
        AtomicBoolean(false)

    private var serverSocket:
            ServerSocket? =
        null

    private var acceptThread:
            Thread? =
        null

    /*
     * ============================================================
     * STREAMS
     * ============================================================
     *
     * /entree
     * /garage
     * /jardin
     * ...
     *
     * Aucune limite.
     */

    private val streams =
        ConcurrentHashMap<String, RtspStream>()

    private val clients =
        CopyOnWriteArrayList<ClientSession>()

    /*
     * ============================================================
     * STREAM MANAGEMENT
     * ============================================================
     */

    fun createStream(
        name: String,
        fps: Int = 20
    ): RtspStream {

        require(
            name.matches(
                Regex(
                    """[A-Za-z0-9_-]+"""
                )
            )
        ) {
            "Nom de stream RTSP invalide : '$name'"
        }

        val stream =
            RtspStream(
                name = name,
                fps = fps
            )

        val existing =
            streams.putIfAbsent(
                name,
                stream
            )

        check(
            existing == null
        ) {
            "Le stream RTSP '$name' existe déjà"
        }

        log.info(
            "Stream RTSP enregistré : /{}",
            name
        )

        return stream
    }

    fun getStream(
        name: String
    ): RtspStream? {

        return streams[name]
    }

    fun removeStream(
        name: String
    ) {

        val stream =
            streams.remove(
                name
            )
                ?: return

        stream.close()

        log.info(
            "Stream RTSP supprimé : /{}",
            name
        )
    }

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

            log.warn(
                "Le serveur RTSP est déjà démarré"
            )

            return
        }

        try {

            val socket =
                ServerSocket()

            socket.reuseAddress =
                true

            socket.bind(
                InetSocketAddress(
                    bindAddress,
                    port
                )
            )

            serverSocket =
                socket

            log.info(
                "Serveur RTSP démarré sur {}:{}",
                bindAddress,
                port
            )

            for (stream in streams.values) {

                log.info(
                    "RTSP : rtsp://<IP>:{}/{}",
                    port,
                    stream.name
                )
            }

            acceptThread =
                Thread(
                    {
                        acceptLoop()
                    },
                    "xiaovv-rtsp-accept"
                ).apply {

                    isDaemon =
                        false

                    start()
                }

        } catch (e: Exception) {

            running.set(
                false
            )

            throw e
        }
    }

    /*
     * ============================================================
     * ACCEPT
     * ============================================================
     */

    private fun acceptLoop() {

        while (
            running.get()
        ) {

            try {

                val socket =
                    serverSocket
                        ?.accept()
                        ?: break

                socket.tcpNoDelay =
                    true

                socket.keepAlive =
                    true

                val client =
                    ClientSession(
                        socket
                    )

                clients.add(
                    client
                )

                log.info(
                    "Client RTSP connecté : {}:{}",
                    socket.inetAddress.hostAddress,
                    socket.port
                )

                client.start()

            } catch (e: SocketException) {

                if (running.get()) {

                    log.warn(
                        "Erreur socket RTSP : {}",
                        e.message
                    )
                }

                break

            } catch (e: Exception) {

                if (running.get()) {

                    log.error(
                        "Erreur boucle RTSP",
                        e
                    )
                }
            }
        }
    }

    /*
     * ============================================================
     * REQUEST
     * ============================================================
     */

    private data class RtspRequest(
        val method: String,
        val uri: String,
        val version: String,
        val headers: Map<String, String>
    )

    private data class QueuedAccessUnit(
        val frame: V380MediaDecoder.DecodedVideoFrame,
        val nals: List<H265AnnexB.NalUnit>
    )

    /*
     * ============================================================
     * CLIENT
     * ============================================================
     */

    private inner class ClientSession(
        private val socket: Socket
    ) : Closeable {

        private val active =
            AtomicBoolean(true)

        private val input =
            BufferedInputStream(
                socket.getInputStream()
            )

        private val output =
            BufferedOutputStream(
                socket.getOutputStream()
            )

        private val outputLock =
            Any()

        private val sessionId =
            generateSessionId()

        @Volatile
        private var selectedStream:
                RtspStream? =
            null

        @Volatile
        private var setupDone =
            false

        @Volatile
        private var playing =
            false

        @Volatile
        private var waitingForKeyFrame =
            true

        @Volatile
        private var rtpChannel =
            0

        @Volatile
        private var rtcpChannel =
            1

        @Volatile
        private var packetizer:
                H265RtpPacketizer? =
            null

        private val rtpQueue =
            ArrayBlockingQueue<QueuedAccessUnit>(
                50
            )

        private var controlThread:
                Thread? =
            null

        private var senderThread:
                Thread? =
            null

        private val streamListener =
            RtspStream.AccessUnitListener {
                    frame,
                    nals ->

                enqueueAccessUnit(
                    frame,
                    nals
                )
            }

        /*
         * ========================================================
         * START
         * ========================================================
         */

        fun start() {

            senderThread =
                Thread(
                    {
                        senderLoop()
                    },
                    "xiaovv-rtp-${socket.port}"
                ).apply {

                    isDaemon =
                        true

                    start()
                }

            controlThread =
                Thread(
                    {
                        clientLoop()
                    },
                    "xiaovv-rtsp-${socket.port}"
                ).apply {

                    isDaemon =
                        true

                    start()
                }
        }

        /*
         * ========================================================
         * CONTROL LOOP
         * ========================================================
         */

        private fun clientLoop() {

            try {

                while (
                    active.get() &&
                    running.get()
                ) {

                    val request =
                        readRequest()
                            ?: break

                    log.debug(
                        "RTSP {} {}",
                        request.method,
                        request.uri
                    )

                    if (
                        !handleRequest(
                            request
                        )
                    ) {
                        break
                    }
                }

            } catch (e: Exception) {

                if (
                    active.get() &&
                    running.get()
                ) {

                    log.debug(
                        "Fin RTSP {}:{} : {}",
                        socket.inetAddress.hostAddress,
                        socket.port,
                        e.message
                    )
                }

            } finally {

                close()
            }
        }

        /*
         * ========================================================
         * HANDLER
         * ========================================================
         */

        private fun handleRequest(
            request: RtspRequest
        ): Boolean {

            val cseq =
                request.headers["CSeq"]
                    ?: "0"

            return when (
                request.method.uppercase()
            ) {

                "OPTIONS" -> {

                    sendResponse(
                        cseq,
                        200,
                        "OK",
                        mapOf(
                            "Public" to
                                    "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER"
                        )
                    )

                    true
                }

                "DESCRIBE" -> {

                    val stream =
                        resolveStream(
                            request.uri
                        )

                    if (stream == null) {

                        sendResponse(
                            cseq,
                            404,
                            "Not Found"
                        )

                        return true
                    }

                    bindStream(
                        stream
                    )

                    val serverIp =
                        socket.localAddress
                            .hostAddress

                    val sdp =
                        stream.buildSdp(
                            serverIp
                        )

                    val base =
                        request.uri
                            .removeSuffix("/") +
                                "/"

                    sendResponse(
                        cseq,
                        200,
                        "OK",
                        mapOf(
                            "Content-Type" to
                                    "application/sdp",

                            "Content-Base" to
                                    base
                        ),
                        sdp
                    )

                    log.info(
                        "RTSP DESCRIBE /{} -> {}",
                        stream.name,
                        socket.inetAddress.hostAddress
                    )

                    true
                }

                "SETUP" -> {

                    handleSetup(
                        request,
                        cseq
                    )

                    true
                }

                "PLAY" -> {

                    handlePlay(
                        cseq
                    )

                    true
                }

                "PAUSE" -> {

                    pauseStream()

                    sendResponse(
                        cseq,
                        200,
                        "OK",
                        mapOf(
                            "Session" to
                                    sessionId
                        )
                    )

                    true
                }

                "GET_PARAMETER" -> {

                    sendResponse(
                        cseq,
                        200,
                        "OK",
                        mapOf(
                            "Session" to
                                    sessionId
                        )
                    )

                    true
                }

                "TEARDOWN" -> {

                    pauseStream()

                    sendResponse(
                        cseq,
                        200,
                        "OK",
                        mapOf(
                            "Session" to
                                    sessionId
                        )
                    )

                    false
                }

                else -> {

                    sendResponse(
                        cseq,
                        405,
                        "Method Not Allowed"
                    )

                    true
                }
            }
        }

        /*
         * ========================================================
         * SETUP
         * ========================================================
         */

        private fun handleSetup(
            request: RtspRequest,
            cseq: String
        ) {

            val stream =
                resolveStream(
                    request.uri
                )
                    ?: selectedStream

            if (stream == null) {

                sendResponse(
                    cseq,
                    404,
                    "Not Found"
                )

                return
            }

            bindStream(
                stream
            )

            val transport =
                request.headers["Transport"]

            if (transport == null) {

                sendResponse(
                    cseq,
                    400,
                    "Bad Request"
                )

                return
            }

            if (
                !transport.contains(
                    "RTP/AVP/TCP",
                    ignoreCase = true
                )
            ) {

                sendResponse(
                    cseq,
                    461,
                    "Unsupported Transport"
                )

                return
            }

            val match =
                Regex(
                    """interleaved=(\d+)-(\d+)""",
                    RegexOption.IGNORE_CASE
                )
                    .find(
                        transport
                    )

            if (match != null) {

                rtpChannel =
                    match.groupValues[1]
                        .toInt()

                rtcpChannel =
                    match.groupValues[2]
                        .toInt()
            }

            val newPacketizer =
                H265RtpPacketizer(
                    fps = stream.fps,
                    payloadType = 96,
                    mtu = 1200
                )

            packetizer =
                newPacketizer

            setupDone =
                true

            val ssrc =
                newPacketizer
                    .getSsrc()
                    .toString(16)
                    .uppercase()
                    .padStart(
                        8,
                        '0'
                    )

            sendResponse(
                cseq,
                200,
                "OK",
                mapOf(
                    "Transport" to
                            "RTP/AVP/TCP;" +
                            "unicast;" +
                            "interleaved=$rtpChannel-$rtcpChannel;" +
                            "ssrc=$ssrc",

                    "Session" to
                            "$sessionId;timeout=60"
                )
            )

            log.info(
                "RTSP SETUP /{} : {}",
                stream.name,
                socket.inetAddress.hostAddress
            )
        }

        /*
         * ========================================================
         * PLAY
         * ========================================================
         */

        private fun handlePlay(
            cseq: String
        ) {

            val stream =
                selectedStream

            if (
                !setupDone ||
                stream == null ||
                packetizer == null
            ) {

                sendResponse(
                    cseq,
                    455,
                    "Method Not Valid in This State"
                )

                return
            }

            /*
             * Réponse RTSP AVANT l'arrivée des paquets RTP.
             */
            sendResponse(
                cseq,
                200,
                "OK",
                mapOf(
                    "Session" to
                            sessionId,

                    "Range" to
                            "npt=0.000-"
                )
            )

            rtpQueue.clear()

            waitingForKeyFrame =
                true

            playing =
                true

            stream.addListener(
                streamListener
            )

            log.info(
                "RTSP PLAY /{} : {}",
                stream.name,
                socket.inetAddress.hostAddress
            )
        }

        /*
         * ========================================================
         * BIND STREAM
         * ========================================================
         */

        private fun bindStream(
            stream: RtspStream
        ) {

            val previous =
                selectedStream

            if (
                previous === stream
            ) {
                return
            }

            previous?.removeListener(
                streamListener
            )

            playing =
                false

            setupDone =
                false

            rtpQueue.clear()

            packetizer =
                null

            selectedStream =
                stream
        }

        /*
         * ========================================================
         * PAUSE
         * ========================================================
         */

        private fun pauseStream() {

            playing =
                false

            waitingForKeyFrame =
                true

            rtpQueue.clear()

            selectedStream
                ?.removeListener(
                    streamListener
                )
        }

        /*
         * ========================================================
         * ACCESS UNIT
         * ========================================================
         */

        private fun enqueueAccessUnit(
            frame: V380MediaDecoder.DecodedVideoFrame,
            nals: List<H265AnnexB.NalUnit>
        ) {

            if (
                !active.get() ||
                !playing
            ) {
                return
            }

            val keyFrame =
                frame.keyFrame ||
                        nals.any {
                            it.isKeyFrameNal
                        }

            if (
                waitingForKeyFrame
            ) {

                if (!keyFrame) {
                    return
                }

                waitingForKeyFrame =
                    false

                log.info(
                    "RTSP /{} : keyframe trouvée",
                    selectedStream?.name
                )
            }

            val accessUnit =
                QueuedAccessUnit(
                    frame,
                    nals
                )

            if (
                !rtpQueue.offer(
                    accessUnit
                )
            ) {

                /*
                 * Client trop lent :
                 * on abandonne son retard.
                 */
                rtpQueue.clear()

                waitingForKeyFrame =
                    true

                log.warn(
                    "RTSP /{} client trop lent : resynchronisation",
                    selectedStream?.name
                )

                if (keyFrame) {

                    waitingForKeyFrame =
                        false

                    rtpQueue.offer(
                        accessUnit
                    )
                }
            }
        }

        /*
         * ========================================================
         * RTP SENDER
         * ========================================================
         */

        private fun senderLoop() {

            try {

                while (
                    active.get() &&
                    running.get()
                ) {

                    val accessUnit =
                        rtpQueue.poll(
                            1,
                            TimeUnit.SECONDS
                        )
                            ?: continue

                    if (!playing) {
                        continue
                    }

                    val p =
                        packetizer
                            ?: continue

                    val packets =
                        p.packetizeAccessUnit(
                            nals =
                                accessUnit.nals,

                            cameraTimestampMs =
                                accessUnit.frame.timestamp
                        )

                    for (packet in packets) {

                        if (
                            !active.get() ||
                            !playing
                        ) {
                            break
                        }

                        sendInterleavedRtp(
                            packet
                        )
                    }
                }

            } catch (_: InterruptedException) {

                Thread.currentThread()
                    .interrupt()

            } catch (e: Exception) {

                if (
                    active.get() &&
                    running.get()
                ) {

                    log.warn(
                        "Erreur RTP vers {} : {}",
                        socket.inetAddress.hostAddress,
                        e.message
                    )
                }
            }
        }

        /*
         * ========================================================
         * RTP INTERLEAVED
         * ========================================================
         */

        private fun sendInterleavedRtp(
            packet: H265RtpPacketizer.RtpPacket
        ) {

            val data =
                packet.toByteArray()

            val header =
                byteArrayOf(
                    0x24,

                    rtpChannel.toByte(),

                    (
                            (data.size ushr 8)
                                    and 0xFF
                            ).toByte(),

                    (
                            data.size
                                    and 0xFF
                            ).toByte()
                )

            synchronized(outputLock) {

                output.write(
                    header
                )

                output.write(
                    data
                )

                output.flush()
            }
        }

        /*
         * ========================================================
         * RESOLVE STREAM
         * ========================================================
         */

        private fun resolveStream(
            uri: String
        ): RtspStream? {

            if (
                uri == "*"
            ) {
                return null
            }

            val path =
                try {

                    URI(uri).path

                } catch (_: Exception) {

                    uri
                        .substringBefore('?')
                }

            val segments =
                path
                    .trim('/')
                    .split('/')
                    .filter {
                        it.isNotBlank()
                    }

            if (segments.isEmpty()) {
                return null
            }

            /*
             * /camera1
             *
             * ou :
             *
             * /camera1/trackID=0
             */

            return streams[
                segments.first()
            ]
        }

        /*
         * ========================================================
         * RESPONSE
         * ========================================================
         */

        private fun sendResponse(
            cseq: String,
            status: Int,
            reason: String,
            headers: Map<String, String> =
                emptyMap(),
            body: String? = null
        ) {

            val bodyBytes =
                body?.toByteArray(
                    StandardCharsets.UTF_8
                )

            val response =
                buildString {

                    append(
                        "RTSP/1.0 $status $reason\r\n"
                    )

                    append(
                        "CSeq: $cseq\r\n"
                    )

                    append(
                        "Server: Xiaovv-Kotlin/1.0\r\n"
                    )

                    for (
                    (name, value)
                    in headers
                    ) {

                        append(
                            "$name: $value\r\n"
                        )
                    }

                    if (
                        bodyBytes != null
                    ) {

                        append(
                            "Content-Length: ${bodyBytes.size}\r\n"
                        )
                    }

                    append(
                        "\r\n"
                    )
                }

            synchronized(outputLock) {

                output.write(
                    response.toByteArray(
                        StandardCharsets.US_ASCII
                    )
                )

                if (
                    bodyBytes != null
                ) {

                    output.write(
                        bodyBytes
                    )
                }

                output.flush()
            }
        }

        /*
         * ========================================================
         * REQUEST PARSER
         * ========================================================
         */

        private fun readRequest():
                RtspRequest? {

            while (
                active.get()
            ) {

                val first =
                    input.read()

                if (
                    first < 0
                ) {
                    return null
                }

                /*
                 * RTCP interleaved entrant.
                 */
                if (
                    first == 0x24
                ) {

                    readIncomingInterleaved()

                    continue
                }

                val requestLine =
                    readLine(
                        first
                    )

                if (
                    requestLine.isBlank()
                ) {
                    continue
                }

                val parts =
                    requestLine.split(
                        ' ',
                        limit = 3
                    )

                if (
                    parts.size != 3
                ) {

                    throw SocketException(
                        "Ligne RTSP invalide : $requestLine"
                    )
                }

                val headers =
                    TreeMap<String, String>(
                        String.CASE_INSENSITIVE_ORDER
                    )

                while (true) {

                    val line =
                        readLine()

                    if (
                        line.isEmpty()
                    ) {
                        break
                    }

                    val index =
                        line.indexOf(
                            ':'
                        )

                    if (
                        index > 0
                    ) {

                        headers[
                            line.substring(
                                0,
                                index
                            ).trim()
                        ] =
                            line.substring(
                                index + 1
                            ).trim()
                    }
                }

                val contentLength =
                    headers[
                        "Content-Length"
                    ]
                        ?.toIntOrNull()
                        ?: 0

                if (
                    contentLength > 0
                ) {

                    skipExact(
                        contentLength
                    )
                }

                return RtspRequest(
                    method =
                        parts[0],

                    uri =
                        parts[1],

                    version =
                        parts[2],

                    headers =
                        headers
                )
            }

            return null
        }

        /*
         * ========================================================
         * INTERLEAVED CLIENT -> SERVEUR
         * ========================================================
         */

        private fun readIncomingInterleaved() {

            val channel =
                input.read()

            val high =
                input.read()

            val low =
                input.read()

            if (
                channel < 0 ||
                high < 0 ||
                low < 0
            ) {

                throw SocketException(
                    "Paquet RTCP incomplet"
                )
            }

            val length =
                (high shl 8) or
                        low

            skipExact(
                length
            )
        }

        /*
         * ========================================================
         * READ LINE
         * ========================================================
         */

        private fun readLine(
            firstByte: Int? = null
        ): String {

            val buffer =
                ByteArrayOutputStream()

            if (
                firstByte != null
            ) {

                buffer.write(
                    firstByte
                )
            }

            while (true) {

                val value =
                    input.read()

                if (
                    value < 0
                ) {
                    break
                }

                if (
                    value ==
                    '\n'.code
                ) {
                    break
                }

                if (
                    value !=
                    '\r'.code
                ) {

                    buffer.write(
                        value
                    )
                }
            }

            return buffer.toString(
                StandardCharsets.US_ASCII
            )
        }

        private fun skipExact(
            size: Int
        ) {

            var remaining =
                size

            val buffer =
                ByteArray(
                    4096
                )

            while (
                remaining > 0
            ) {

                val count =
                    input.read(
                        buffer,
                        0,
                        minOf(
                            buffer.size,
                            remaining
                        )
                    )

                if (
                    count < 0
                ) {

                    throw SocketException(
                        "Connexion fermée"
                    )
                }

                remaining -=
                    count
            }
        }

        /*
         * ========================================================
         * CLOSE
         * ========================================================
         */

        override fun close() {

            if (
                !active.getAndSet(false)
            ) {
                return
            }

            pauseStream()

            senderThread
                ?.interrupt()

            clients.remove(
                this
            )

            try {

                socket.close()

            } catch (_: Exception) {
            }

            log.info(
                "Client RTSP déconnecté : {}:{}",
                socket.inetAddress.hostAddress,
                socket.port
            )
        }
    }

    /*
     * ============================================================
     * SESSION
     * ============================================================
     */

    private fun generateSessionId():
            String {

        val bytes =
            ByteArray(
                8
            )

        SecureRandom()
            .nextBytes(
                bytes
            )

        return bytes.joinToString(
            ""
        ) {

            "%02x".format(
                it.toInt()
                        and 0xFF
            )
        }
    }

    /*
     * ============================================================
     * CLOSE
     * ============================================================
     */

    override fun close() {

        if (
            !running.getAndSet(false)
        ) {
            return
        }

        log.info(
            "Arrêt du serveur RTSP"
        )

        try {

            serverSocket
                ?.close()

        } catch (_: Exception) {
        }

        for (
        client in
        clients.toList()
        ) {

            client.close()
        }

        clients.clear()

        for (
        stream in
        streams.values
        ) {

            stream.close()
        }

        streams.clear()

        try {

            acceptThread
                ?.join(
                    2_000
                )

        } catch (_: InterruptedException) {

            Thread.currentThread()
                .interrupt()
        }

        serverSocket =
            null

        acceptThread =
            null

        log.info(
            "Serveur RTSP arrêté"
        )
    }
}