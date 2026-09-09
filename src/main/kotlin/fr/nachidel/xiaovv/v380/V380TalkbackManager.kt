package fr.nachidel.xiaovv.v380

import fr.nachidel.xiaovv.CameraRuntimeManager
import fr.nachidel.xiaovv.logging.logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Gère le "push to talk" vers les caméras V380.
 *
 * Le protocole utilisé ici est distinct du flux audio entrant AAC déjà
 * exposé en RTSP : on ouvre une connexion TCP dédiée vers le port natif
 * de la caméra, on effectue un LOGIN afin de récupérer le handle, puis on
 * envoie le handshake et les paquets audio "speak" attendus par le firmware.
 *
 * Entrée attendue depuis l'API HTTP : PCM 16 bits little-endian, mono, 8 kHz.
 */
class V380TalkbackManager(
    private val cameraRuntime: CameraRuntimeManager
) : Closeable {

    private val log =
        logger<V380TalkbackManager>()

    private val sessions =
        ConcurrentHashMap<String, TalkbackSession>()

    private val lifecycleLock =
        Any()

    data class Status(
        val active: Boolean,
        val protocolVersion: Int?,
        val queuedChunks: Int
    )

    fun start(
        cameraId: String
    ): Status {

        synchronized(
            lifecycleLock
        ) {

            val existing =
                sessions[cameraId]

            if (
                existing != null &&
                existing.isActive()
            ) {
                return existing.status()
            }

            existing?.close()

            val supervisor =
                cameraRuntime.get(
                    cameraId
                )
                    ?: error(
                        "Caméra inconnue : $cameraId"
                    )

            val session =
                TalkbackSession(
                    cameraId = cameraId,
                    host = supervisor.config.host,
                    port = supervisor.config.port,
                    deviceId = supervisor.config.deviceId,
                    username = supervisor.config.username,
                    password = supervisor.config.password
                )

            try {

                session.start()

                sessions[cameraId] =
                    session

                log.info(
                    "[{}] interphone V380 démarré (protocole v{})",
                    cameraId,
                    session.protocolVersion()
                )

                return session.status()

            } catch (e: Exception) {

                try {
                    session.close()
                } catch (_: Exception) {
                }

                throw e
            }
        }
    }

    fun offerPcm(
        cameraId: String,
        pcm16Le: ByteArray
    ): Status {

        require(
            pcm16Le.isNotEmpty()
        ) {
            "Bloc audio vide"
        }

        require(
            pcm16Le.size <= MAX_HTTP_AUDIO_CHUNK
        ) {
            "Bloc audio trop grand : ${pcm16Le.size} octets"
        }

        require(
            pcm16Le.size % 2 == 0
        ) {
            "Le PCM 16 bits doit avoir une taille paire"
        }

        val session =
            sessions[cameraId]
                ?: error(
                    "Interphone non démarré pour '$cameraId'"
                )

        session.offer(
            pcm16Le
        )

        return session.status()
    }

    fun stop(
        cameraId: String
    ): Status {

        val session =
            synchronized(
                lifecycleLock
            ) {
                sessions.remove(
                    cameraId
                )
            }

        if (
            session != null
        ) {

            session.close()

            log.info(
                "[{}] interphone V380 arrêté",
                cameraId
            )
        }

        return Status(
            active = false,
            protocolVersion = null,
            queuedChunks = 0
        )
    }

    fun status(
        cameraId: String
    ): Status {

        val session =
            sessions[cameraId]

        if (
            session == null ||
            !session.isActive()
        ) {

            if (
                session != null
            ) {
                sessions.remove(
                    cameraId,
                    session
                )
            }

            return Status(
                active = false,
                protocolVersion = null,
                queuedChunks = 0
            )
        }

        return session.status()
    }

    override fun close() {

        val current =
            sessions.values
                .toList()

        sessions.clear()

        current.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
    }

    private class TalkbackSession(
        private val cameraId: String,
        private val host: String,
        private val port: Int,
        private val deviceId: String,
        private val username: String,
        private val password: String
    ) : Closeable {

        private val log =
            logger<TalkbackSession>()

        private val active =
            AtomicBoolean(false)

        private val queue =
            ArrayBlockingQueue<ByteArray>(
                AUDIO_QUEUE_CHUNKS
            )

        @Volatile
        private var protocolVersion:
                Int? =
            null

        @Volatile
        private var audioSocket:
                Socket? =
            null

        @Volatile
        private var audioInput:
                BufferedInputStream? =
            null

        @Volatile
        private var audioOutput:
                BufferedOutputStream? =
            null

        private var workerThread:
                Thread? =
            null

        private var readerThread:
                Thread? =
            null

        private val ima =
            ImaAdpcmEncoder()

        @Volatile
        private var lastAudioActivityNanos =
            System.nanoTime()

        private var cipher:
                Cipher? =
            null

        fun start() {

            check(
                active.compareAndSet(
                    false,
                    true
                )
            ) {
                "Interphone déjà actif"
            }

            try {

                val login =
                    authenticateAndFetchHandle()

                protocolVersion =
                    login.protocolVersion

                cipher =
                    if (
                        login.protocolVersion > 30
                    ) {
                        createAudioCipher(
                            login.authTicket
                        )
                    } else {
                        null
                    }

                openAudioConnection(
                    login.authTicket
                )

                lastAudioActivityNanos =
                    System.nanoTime()

                workerThread =
                    Thread(
                        {
                            runSender()
                        },
                        "xiaovv-talkback-$cameraId"
                    ).apply {
                        isDaemon = true
                        start()
                    }

            } catch (e: Exception) {

                active.set(
                    false
                )

                closeResources()

                throw e
            }
        }

        fun isActive(): Boolean {
            return active.get()
        }

        fun protocolVersion(): Int? {
            return protocolVersion
        }

        fun status(): Status {
            return Status(
                active = active.get(),
                protocolVersion = protocolVersion,
                queuedChunks = queue.size
            )
        }

        fun offer(
            bytes: ByteArray
        ) {

            check(
                active.get()
            ) {
                "Interphone arrêté"
            }

            lastAudioActivityNanos =
                System.nanoTime()

            val copy =
                bytes.copyOf()

            if (
                !queue.offer(
                    copy
                )
            ) {

                /*
                 * Pour un interphone, la faible latence est plus importante
                 * que la conservation de vieux audio. Si le navigateur ou le
                 * réseau prend du retard, on jette donc le plus ancien bloc.
                 */
                queue.poll()

                if (
                    !queue.offer(
                        copy
                    )
                ) {
                    error(
                        "File audio saturée"
                    )
                }
            }
        }

        private fun authenticateAndFetchHandle():
                V380Auth.LoginResponse {

            val socket =
                Socket()

            try {

                socket.tcpNoDelay =
                    true

                socket.soTimeout =
                    HANDSHAKE_TIMEOUT_MS

                socket.connect(
                    InetSocketAddress(
                        host,
                        port
                    ),
                    CONNECT_TIMEOUT_MS
                )

                val output =
                    BufferedOutputStream(
                        socket.getOutputStream()
                    )

                val input =
                    BufferedInputStream(
                        socket.getInputStream()
                    )

                val request =
                    V380Auth.buildLoginRequest(
                        deviceId = deviceId,
                        username = username,
                        password = password
                    )

                output.write(
                    request
                )

                output.flush()

                val responseBytes =
                    readExact(
                        input,
                        V380Protocol.LOGIN_RESPONSE_SIZE
                    )

                val response =
                    V380Auth.parseLoginResponse(
                        responseBytes
                    )

                check(
                    response.success
                ) {
                    "Authentification interphone refusée : ${response.result}"
                }

                return response

            } finally {

                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }

        private fun openAudioConnection(
            authTicket: Int
        ) {

            val socket =
                Socket()

            socket.tcpNoDelay =
                true

            socket.keepAlive =
                true

            socket.connect(
                InetSocketAddress(
                    host,
                    port
                ),
                CONNECT_TIMEOUT_MS
            )

            val input =
                BufferedInputStream(
                    socket.getInputStream()
                )

            val output =
                BufferedOutputStream(
                    socket.getOutputStream()
                )

            audioSocket =
                socket

            audioInput =
                input

            audioOutput =
                output

            readerThread =
                Thread(
                    {
                        drainCameraResponses(
                            input
                        )
                    },
                    "xiaovv-talkback-$cameraId-reader"
                ).apply {
                    isDaemon = true
                    start()
                }

            val handshake =
                buildAudioHandshake(
                    authTicket
                )

            output.write(
                handshake
            )

            output.flush()

            /*
             * La caméra ne renvoie pas forcément un ACK explicite. Le code
             * V380 observé laisse un court délai avant de commencer l'audio.
             */
            Thread.sleep(
                AUDIO_HANDSHAKE_DELAY_MS
            )

            check(
                active.get() &&
                !socket.isClosed
            ) {
                "La caméra a rejeté la connexion interphone"
            }
        }

        private fun drainCameraResponses(
            input: BufferedInputStream
        ) {

            val buffer =
                ByteArray(
                    1024
                )

            try {

                while (
                    active.get()
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
                }

            } catch (_: SocketException) {
            } catch (e: Exception) {

                if (
                    active.get()
                ) {
                    log.debug(
                        "[{}] lecture interphone interrompue : {}",
                        cameraId,
                        e.message
                    )
                }

            } finally {

                if (
                    active.get()
                ) {
                    active.set(
                        false
                    )
                }
            }
        }

        private fun runSender() {

            var pending =
                ByteArray(0)

            var packetIndex =
                0L

            var paceOriginNs =
                0L

            var lastPacketSentNs =
                0L

            try {

                while (
                    active.get()
                ) {

                    val chunk =
                        queue.poll(
                            500,
                            TimeUnit.MILLISECONDS
                        )

                    if (
                        chunk == null
                    ) {

                        /*
                         * Pas d'audio à envoyer : le prochain paquet repart
                         * avec une horloge de pacing fraîche, sinon plusieurs
                         * paquets pourraient être envoyés en rafale.
                         */
                        paceOriginNs =
                            0L

                        /*
                         * Si le navigateur disparaît brutalement sans pouvoir
                         * envoyer /stop (fermeture d'onglet, perte réseau...),
                         * on ne laisse jamais la caméra bloquée indéfiniment
                         * en mode interphone.
                         */
                        val idleNanos =
                            System.nanoTime() -
                            lastAudioActivityNanos

                        if (
                            idleNanos >=
                            TimeUnit.MILLISECONDS.toNanos(
                                IDLE_SESSION_TIMEOUT_MS
                            )
                        ) {
                            log.info(
                                "[{}] interphone arrêté automatiquement après inactivité",
                                cameraId
                            )

                            break
                        }

                        continue
                    }

                    pending =
                        appendBytes(
                            pending,
                            chunk
                        )

                    while (
                        pending.size >=
                        PCM_BLOCK_BYTES &&
                        active.get()
                    ) {

                        val pcmBlock =
                            pending.copyOfRange(
                                0,
                                PCM_BLOCK_BYTES
                            )

                        pending =
                            pending.copyOfRange(
                                PCM_BLOCK_BYTES,
                                pending.size
                            )

                        val encoded =
                            ima.encodeBlock(
                                pcmBlock
                            )

                        val payload =
                            encryptIfNeeded(
                                encoded
                            )

                        val header =
                            buildAudioPayloadHeader(
                                packetIndex
                            )

                        val output =
                            audioOutput
                                ?: error(
                                    "Socket audio fermée"
                                )

                        val nowNs =
                            System.nanoTime()

                        if (
                            paceOriginNs == 0L ||
                            (
                                lastPacketSentNs > 0L &&
                                nowNs - lastPacketSentNs >
                                TimeUnit.MILLISECONDS.toNanos(
                                    400
                                )
                            )
                        ) {

                            paceOriginNs =
                                nowNs

                            packetIndex =
                                0L
                        }

                        val targetNs =
                            paceOriginNs +
                            packetIndex *
                            AUDIO_PACKET_DURATION_NS

                        val waitNs =
                            targetNs -
                            System.nanoTime()

                        if (
                            waitNs > 0L
                        ) {

                            TimeUnit.NANOSECONDS.sleep(
                                waitNs
                            )
                        }

                        output.write(
                            header
                        )

                        output.write(
                            payload
                        )

                        output.flush()

                        lastPacketSentNs =
                            System.nanoTime()

                        packetIndex++
                    }
                }

            } catch (e: Exception) {

                if (
                    active.get()
                ) {
                    log.warn(
                        "[{}] émission interphone interrompue : {}",
                        cameraId,
                        e.message
                    )
                }

            } finally {

                active.set(
                    false
                )

                closeResources()
            }
        }

        private fun encryptIfNeeded(
            encoded: ByteArray
        ): ByteArray {

            val localCipher =
                cipher
                    ?: return encoded

            synchronized(
                localCipher
            ) {
                return localCipher.doFinal(
                    encoded
                )
            }
        }

        private fun createAudioCipher(
            authTicket: Int
        ): Cipher {

            val key =
                ByteArray(
                    16
                )

            writeInt32LE(
                key,
                0,
                authTicket
            )

            writeInt64LE(
                key,
                4,
                MAGIC_1
            )

            writeInt32LE(
                key,
                12,
                MAGIC_2
            )

            return Cipher.getInstance(
                "AES/ECB/NoPadding"
            ).apply {

                init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(
                        key,
                        "AES"
                    )
                )
            }
        }

        private fun buildAudioHandshake(
            authTicket: Int
        ): ByteArray {

            val packet =
                ByteArray(
                    85
                )

            packet[0] =
                0x79

            packet[1] =
                0x01

            val numericDeviceId =
                deviceId.toLongOrNull()
                    ?: error(
                        "Device ID invalide : $deviceId"
                    )

            require(
                numericDeviceId in
                0..0xFFFFFFFFL
            ) {
                "Device ID hors plage uint32"
            }

            writeUInt32LE(
                packet,
                4,
                numericDeviceId
            )

            writeInt32LE(
                packet,
                8,
                authTicket
            )

            return packet
        }

        private fun buildAudioPayloadHeader(
            packetIndex: Long
        ): ByteArray {

            val header =
                byteArrayOf(
                    0xB4.toByte(),
                    0x00,
                    0x00,
                    0x00,
                    0x01,
                    0x00,
                    0x16,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x00,
                    0x01,
                    0x00
                )

            header[15] =
                (
                    (
                        packetIndex +
                        1L
                    ) %
                    256L
                )
                    .toInt()
                    .toByte()

            return header
        }

        override fun close() {

            if (
                !active.getAndSet(
                    false
                )
            ) {

                closeResources()

                return
            }

            queue.clear()

            closeResources()

            try {
                workerThread?.interrupt()
            } catch (_: Exception) {
            }

            workerThread =
                null

            readerThread =
                null
        }

        private fun closeResources() {

            try {
                audioInput?.close()
            } catch (_: Exception) {
            }

            try {
                audioOutput?.close()
            } catch (_: Exception) {
            }

            try {
                audioSocket?.close()
            } catch (_: Exception) {
            }

            audioInput =
                null

            audioOutput =
                null

            audioSocket =
                null
        }
    }

    /**
     * Encodeur IMA ADPCM compatible avec le bloc audio V380 :
     * 505 échantillons PCM16LE -> 256 octets.
     */
    private class ImaAdpcmEncoder {

        private var predicted =
            0

        private var index =
            0

        fun encodeBlock(
            pcm: ByteArray
        ): ByteArray {

            require(
                pcm.size ==
                PCM_BLOCK_BYTES
            ) {
                "Bloc PCM invalide : ${pcm.size} octets"
            }

            val output =
                ByteArray(
                    ENCODED_BLOCK_BYTES
                )

            val firstSample =
                readInt16LE(
                    pcm,
                    0
                )

            /*
             * Le premier sample est stocké non compressé dans le header,
             * après avoir mis à jour l'état IMA comme l'implémentation V380
             * observée.
             */
            encodeSample(
                firstSample
            )

            output[0] =
                (firstSample and 0xFF)
                    .toByte()

            output[1] =
                (
                    firstSample ushr
                    8
                )
                    .toByte()

            output[2] =
                index.toByte()

            output[3] =
                0x00

            var inputOffset =
                2

            var outputOffset =
                4

            while (
                outputOffset <
                output.size
            ) {

                val sample1 =
                    readInt16LE(
                        pcm,
                        inputOffset
                    )

                inputOffset +=
                    2

                val sample2 =
                    readInt16LE(
                        pcm,
                        inputOffset
                    )

                inputOffset +=
                    2

                val nibble1 =
                    encodeSample(
                        sample1
                    )

                val nibble2 =
                    encodeSample(
                        sample2
                    )

                output[outputOffset] =
                    (
                        (
                            nibble2 shl
                            4
                        ) or
                        nibble1
                    )
                        .toByte()

                outputOffset++
            }

            return output
        }

        private fun encodeSample(
            sample: Int
        ): Int {

            var delta =
                sample -
                predicted

            var value =
                0

            if (
                delta < 0
            ) {
                value =
                    8

                delta =
                    -delta
            }

            var step =
                STEP_TABLE[index]

            var difference =
                step shr
                3

            if (
                delta >
                step
            ) {

                value =
                    value or
                    4

                delta -=
                    step

                difference +=
                    step
            }

            step =
                step shr
                1

            if (
                delta >
                step
            ) {

                value =
                    value or
                    2

                delta -=
                    step

                difference +=
                    step
            }

            step =
                step shr
                1

            if (
                delta >
                step
            ) {

                value =
                    value or
                    1

                difference +=
                    step
            }

            predicted =
                if (
                    value and
                    8 != 0
                ) {
                    predicted -
                    difference
                } else {
                    predicted +
                    difference
                }

            predicted =
                predicted.coerceIn(
                    Short.MIN_VALUE.toInt(),
                    Short.MAX_VALUE.toInt()
                )

            index +=
                INDEX_TABLE[
                    value and
                    7
                ]

            index =
                index.coerceIn(
                    0,
                    STEP_TABLE.lastIndex
                )

            return value and
                    0x0F
        }
    }

    companion object {

        private const val CONNECT_TIMEOUT_MS =
            5_000

        private const val HANDSHAKE_TIMEOUT_MS =
            5_000

        private const val AUDIO_HANDSHAKE_DELAY_MS =
            500L

        /*
         * Garde-fou serveur : un navigateur disparu ne peut pas laisser
         * une session "speak" ouverte durablement sur la caméra.
         */
        private const val IDLE_SESSION_TIMEOUT_MS =
            5_000L

        private const val PCM_SAMPLE_RATE =
            8_000

        private const val PCM_SAMPLES_PER_BLOCK =
            505

        private const val PCM_BLOCK_BYTES =
            PCM_SAMPLES_PER_BLOCK *
            2

        private const val ENCODED_BLOCK_BYTES =
            256

        private const val MAX_HTTP_AUDIO_CHUNK =
            64 *
            1024

        private const val AUDIO_QUEUE_CHUNKS =
            12

        private val AUDIO_PACKET_DURATION_NS =
            TimeUnit.SECONDS.toNanos(
                1
            ) *
            PCM_SAMPLES_PER_BLOCK /
            PCM_SAMPLE_RATE

        private const val MAGIC_1 =
            0x618123462C14795CL

        private const val MAGIC_2 =
            0x82800DF0.toInt()

        private val INDEX_TABLE =
            intArrayOf(
                -1, -1, -1, -1,
                2, 4, 6, 8,
                -1, -1, -1, -1,
                2, 4, 6, 8
            )

        private val STEP_TABLE =
            intArrayOf(
                7, 8, 9, 10, 11, 12, 13, 14,
                16, 17, 19, 21, 23, 25, 28, 31,
                34, 37, 41, 45, 50, 55, 60, 66,
                73, 80, 88, 97, 107, 118, 130, 143,
                157, 173, 190, 209, 230, 253, 279, 307,
                337, 371, 408, 449, 494, 544, 598, 658,
                724, 796, 876, 963, 1060, 1166, 1282, 1411,
                1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
                3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484,
                7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
                15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794,
                32767
            )

        private fun readExact(
            input: BufferedInputStream,
            size: Int
        ): ByteArray {

            val result =
                ByteArray(
                    size
                )

            var offset =
                0

            while (
                offset <
                result.size
            ) {

                val read =
                    input.read(
                        result,
                        offset,
                        result.size -
                        offset
                    )

                if (
                    read < 0
                ) {
                    error(
                        "Connexion fermée pendant le handshake interphone"
                    )
                }

                offset +=
                    read
            }

            return result
        }

        private fun appendBytes(
            first: ByteArray,
            second: ByteArray
        ): ByteArray {

            val result =
                ByteArray(
                    first.size +
                    second.size
                )

            first.copyInto(
                result,
                0
            )

            second.copyInto(
                result,
                first.size
            )

            return result
        }

        private fun readInt16LE(
            bytes: ByteArray,
            offset: Int
        ): Int {

            val value =
                (
                    bytes[offset]
                        .toInt() and
                    0xFF
                ) or
                (
                    (
                        bytes[offset + 1]
                            .toInt() and
                        0xFF
                    ) shl
                    8
                )

            return if (
                value and
                0x8000 != 0
            ) {
                value -
                0x10000
            } else {
                value
            }
        }

        private fun writeUInt32LE(
            bytes: ByteArray,
            offset: Int,
            value: Long
        ) {

            for (
            i in
            0 until
            4
            ) {
                bytes[offset + i] =
                    (
                        value ushr
                        (
                            i *
                            8
                        )
                    )
                        .toByte()
            }
        }

        private fun writeInt32LE(
            bytes: ByteArray,
            offset: Int,
            value: Int
        ) {

            for (
            i in
            0 until
            4
            ) {
                bytes[offset + i] =
                    (
                        value ushr
                        (
                            i *
                            8
                        )
                    )
                        .toByte()
            }
        }

        private fun writeInt64LE(
            bytes: ByteArray,
            offset: Int,
            value: Long
        ) {

            for (
            i in
            0 until
            8
            ) {
                bytes[offset + i] =
                    (
                        value ushr
                        (
                            i *
                            8
                        )
                    )
                        .toByte()
            }
        }
    }
}
