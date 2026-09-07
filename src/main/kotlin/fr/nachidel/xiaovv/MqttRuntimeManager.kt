package fr.nachidel.xiaovv

import fr.nachidel.xiaovv.logging.logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Client MQTT 3.1.1 minimal intégré à Xiaovv.
 *
 * Aucune dépendance externe n'est nécessaire : le module ne fait que ce qui
 * est utile au dashboard (CONNECT, SUBSCRIBE, PUBLISH reçu, keepalive et
 * reconnexion). Les topics utilisés par les bulles sont exacts, sans wildcard.
 */
class MqttRuntimeManager(
    private val configFileManager: MqttConfigFileManager
) : Closeable {

    data class ValueSnapshot(
        val connected: Boolean,
        val payload: String?,
        val receivedAt: Long?
    )

    private data class TopicKey(
        val brokerId: String,
        val topic: String
    )

    private data class CachedValue(
        val payload: String,
        val receivedAt: Long
    )

    private data class Packet(
        val header: Int,
        val body: ByteArray
    )

    companion object {
        private const val MQTT_KEEP_ALIVE_SECONDS = 30
        private const val SOCKET_CONNECT_TIMEOUT_MS = 5_000
        private const val SOCKET_READ_TIMEOUT_MS = 1_000
        private const val PING_AFTER_IDLE_MS = 15_000L
        private const val PING_RESPONSE_TIMEOUT_MS = 12_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_PACKET_BYTES = 4 * 1024 * 1024
    }

    private val log =
        logger<MqttRuntimeManager>()

    private val executor =
        Executors.newCachedThreadPool()

    private val generation =
        AtomicLong(
            0
        )

    private val configs =
        ConcurrentHashMap<String, MqttConfigFileManager.BrokerConfig>()

    private val workers =
        ConcurrentHashMap<String, BrokerWorker>()

    private val subscriptions =
        ConcurrentHashMap<String, MutableSet<String>>()

    private val values =
        ConcurrentHashMap<TopicKey, CachedValue>()

    @Volatile
    private var started =
        false

    @Synchronized
    fun start() {
        if (
            started
        ) {
            return
        }

        started =
            true

        reload()
    }

    @Synchronized
    fun reload() {
        val currentGeneration =
            generation.incrementAndGet()

        stopWorkers()
        configs.clear()

        val enabled =
            try {
                configFileManager.loadEnabled()
            } catch (e: Exception) {
                log.warn(
                    "Configuration MQTT illisible : {}",
                    e.message
                )

                emptyList()
            }

        enabled.forEach { config ->
            configs[
                config.id
            ] =
                config

            val worker =
                BrokerWorker(
                    config = config,
                    workerGeneration = currentGeneration
                )

            workers[
                config.id
            ] =
                worker

            executor.execute(
                worker
            )
        }
    }

    fun connected(
        brokerId: String
    ): Boolean {
        return workers[
            brokerId
        ]
            ?.isConnected()
            ?: false
    }

    fun ensureSubscription(
        brokerId: String,
        topic: String
    ) {
        val normalizedBroker =
            brokerId.trim()

        val normalizedTopic =
            topic.trim()

        require(
            normalizedBroker.isNotBlank()
        ) {
            "Serveur MQTT absent."
        }

        require(
            normalizedTopic.isNotBlank()
        ) {
            "Topic MQTT absent."
        }

        require(
            normalizedTopic.length <= 1024
        ) {
            "Topic MQTT trop long."
        }

        require(
            !normalizedTopic.contains(
                '#'
            ) &&
            !normalizedTopic.contains(
                '+'
            )
        ) {
            "Les wildcards MQTT # et + ne sont pas autorisés pour une bulle d'information."
        }

        require(
            configs.containsKey(
                normalizedBroker
            )
        ) {
            "Serveur MQTT inconnu ou désactivé : $normalizedBroker"
        }

        val topics =
            subscriptions.computeIfAbsent(
                normalizedBroker
            ) {
                ConcurrentHashMap.newKeySet<String>()
            }

        if (
            topics.add(
                normalizedTopic
            )
        ) {
            workers[
                normalizedBroker
            ]
                ?.subscribe(
                    normalizedTopic
                )
        }
    }

    fun value(
        brokerId: String,
        topic: String
    ): ValueSnapshot {
        ensureSubscription(
            brokerId,
            topic
        )

        val normalizedBroker =
            brokerId.trim()

        val normalizedTopic =
            topic.trim()

        val cached =
            values[
                TopicKey(
                    normalizedBroker,
                    normalizedTopic
                )
            ]

        return ValueSnapshot(
            connected = connected(
                normalizedBroker
            ),
            payload = cached?.payload,
            receivedAt = cached?.receivedAt
        )
    }

    private fun stopWorkers() {
        val snapshot =
            workers.values
                .toList()

        workers.clear()

        snapshot.forEach {
            it.stop()
        }
    }

    private inner class BrokerWorker(
        private val config: MqttConfigFileManager.BrokerConfig,
        private val workerGeneration: Long
    ) : Runnable {

        private val active =
            AtomicBoolean(
                true
            )

        private val connected =
            AtomicBoolean(
                false
            )

        private val outputLock =
            Any()

        private val packetId =
            AtomicInteger(
                1
            )

        @Volatile
        private var socket:
                Socket? =
            null

        @Volatile
        private var output:
                BufferedOutputStream? =
            null

        @Volatile
        private var lastWriteAt =
            0L

        fun isConnected(): Boolean =
            connected.get()

        override fun run() {
            while (
                started &&
                active.get() &&
                workerGeneration ==
                generation.get()
            ) {
                try {
                    connectAndRead()
                } catch (e: InterruptedException) {
                    Thread.currentThread()
                        .interrupt()

                    break
                } catch (e: Exception) {
                    if (
                        active.get() &&
                        workerGeneration ==
                        generation.get()
                    ) {
                        log.warn(
                            "MQTT [{}] connexion interrompue : {}",
                            config.id,
                            e.message
                                ?: e.javaClass.simpleName
                        )
                    }
                } finally {
                    connected.set(
                        false
                    )

                    closeSocket()
                }

                if (
                    !started ||
                    !active.get() ||
                    workerGeneration !=
                    generation.get()
                ) {
                    break
                }

                try {
                    Thread.sleep(
                        RECONNECT_DELAY_MS
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread()
                        .interrupt()

                    break
                }
            }
        }

        fun subscribe(
            topic: String
        ) {
            if (
                !connected.get()
            ) {
                return
            }

            try {
                sendSubscribe(
                    topic
                )
            } catch (e: Exception) {
                log.warn(
                    "MQTT [{}] abonnement impossible à {} : {}",
                    config.id,
                    topic,
                    e.message
                )

                closeSocket()
            }
        }

        fun stop() {
            active.set(
                false
            )

            closeSocket()
        }

        private fun connectAndRead() {
            val localSocket =
                createSocket()

            socket =
                localSocket

            localSocket.connect(
                InetSocketAddress(
                    config.host,
                    config.port
                ),
                SOCKET_CONNECT_TIMEOUT_MS
            )

            if (
                localSocket is SSLSocket
            ) {
                localSocket.startHandshake()
            }

            localSocket.tcpNoDelay =
                true

            localSocket.soTimeout =
                SOCKET_CONNECT_TIMEOUT_MS

            val input =
                BufferedInputStream(
                    localSocket.getInputStream()
                )

            val localOutput =
                BufferedOutputStream(
                    localSocket.getOutputStream()
                )

            synchronized(
                outputLock
            ) {
                output =
                    localOutput
            }

            sendConnect()

            val connAck =
                readPacket(
                    input
                )

            validateConnAck(
                connAck
            )

            localSocket.soTimeout =
                SOCKET_READ_TIMEOUT_MS

            connected.set(
                true
            )

            log.info(
                "MQTT [{}] connecté à {}:{}{}",
                config.id,
                config.host,
                config.port,
                if (
                    config.tls
                ) {
                    " (TLS)"
                } else {
                    ""
                }
            )

            subscriptions[
                config.id
            ]
                ?.toList()
                ?.forEach { topic ->
                    sendSubscribe(
                        topic
                    )
                }

            readLoop(
                input
            )
        }

        private fun createSocket(): Socket {
            return if (
                config.tls
            ) {
                SSLSocketFactory.getDefault()
                    .createSocket()
            } else {
                Socket()
            }
        }

        private fun readLoop(
            input: InputStream
        ) {
            var pingSentAt =
                0L

            var waitingPingResponse =
                false

            while (
                started &&
                active.get() &&
                connected.get() &&
                workerGeneration ==
                generation.get()
            ) {
                val now =
                    System.currentTimeMillis()

                if (
                    waitingPingResponse &&
                    now - pingSentAt >
                    PING_RESPONSE_TIMEOUT_MS
                ) {
                    error(
                        "PINGRESP MQTT non reçu"
                    )
                }

                if (
                    !waitingPingResponse &&
                    now - lastWriteAt >=
                    PING_AFTER_IDLE_MS
                ) {
                    sendPacket(
                        0xC0,
                        byteArrayOf()
                    )

                    pingSentAt =
                        now

                    waitingPingResponse =
                        true
                }

                val packet =
                    try {
                        readPacket(
                            input
                        )
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                val type =
                    packet.header ushr 4

                when (
                    type
                ) {
                    3 ->
                        handlePublish(
                            packet
                        )

                    6 ->
                        handlePubRel(
                            packet
                        )

                    13 -> {
                        waitingPingResponse =
                            false
                    }

                    else -> {
                        // CONNACK/SUBACK/PUBACK/etc. : rien à faire.
                    }
                }
            }
        }

        private fun handlePublish(
            packet: Packet
        ) {
            val body =
                packet.body

            if (
                body.size < 2
            ) {
                error(
                    "PUBLISH MQTT invalide"
                )
            }

            val topicLength =
                unsignedShort(
                    body,
                    0
                )

            if (
                topicLength <= 0 ||
                2 + topicLength >
                body.size
            ) {
                error(
                    "Topic PUBLISH MQTT invalide"
                )
            }

            val topic =
                String(
                    body,
                    2,
                    topicLength,
                    StandardCharsets.UTF_8
                )

            val qos =
                (
                    packet.header ushr 1
                ) and
                0x03

            var offset =
                2 +
                topicLength

            var publishPacketId:
                    Int? =
                null

            if (
                qos > 0
            ) {
                if (
                    offset + 2 >
                    body.size
                ) {
                    error(
                        "Packet ID PUBLISH absent"
                    )
                }

                publishPacketId =
                    unsignedShort(
                        body,
                        offset
                    )

                offset +=
                    2
            }

            val payload =
                String(
                    body,
                    offset,
                    body.size - offset,
                    StandardCharsets.UTF_8
                )

            values[
                TopicKey(
                    config.id,
                    topic
                )
            ] =
                CachedValue(
                    payload = payload,
                    receivedAt = System.currentTimeMillis()
                )

            when (
                qos
            ) {
                1 ->
                    sendPacketIdOnly(
                        0x40,
                        publishPacketId!!
                    )

                2 ->
                    sendPacketIdOnly(
                        0x50,
                        publishPacketId!!
                    )
            }
        }

        private fun handlePubRel(
            packet: Packet
        ) {
            if (
                packet.body.size < 2
            ) {
                return
            }

            sendPacketIdOnly(
                0x70,
                unsignedShort(
                    packet.body,
                    0
                )
            )
        }

        private fun sendConnect() {
            val body =
                ByteArrayOutputStream()

            writeUtf8(
                body,
                "MQTT"
            )

            body.write(
                4
            )

            var flags =
                0x02

            if (
                !config.username.isNullOrBlank()
            ) {
                flags =
                    flags or
                    0x80
            }

            if (
                !config.password.isNullOrEmpty()
            ) {
                flags =
                    flags or
                    0x40
            }

            body.write(
                flags
            )

            writeUnsignedShort(
                body,
                MQTT_KEEP_ALIVE_SECONDS
            )

            writeUtf8(
                body,
                clientId()
            )

            if (
                !config.username.isNullOrBlank()
            ) {
                writeUtf8(
                    body,
                    config.username
                )
            }

            if (
                !config.password.isNullOrEmpty()
            ) {
                writeUtf8(
                    body,
                    config.password
                )
            }

            sendPacket(
                0x10,
                body.toByteArray()
            )
        }

        private fun validateConnAck(
            packet: Packet
        ) {
            val type =
                packet.header ushr 4

            require(
                type == 2 &&
                packet.body.size == 2
            ) {
                "CONNACK MQTT invalide"
            }

            val returnCode =
                packet.body[
                    1
                ]
                    .toInt() and
                    0xFF

            if (
                returnCode != 0
            ) {
                val reason =
                    when (
                        returnCode
                    ) {
                        1 ->
                            "version MQTT refusée"

                        2 ->
                            "identifiant client refusé"

                        3 ->
                            "serveur indisponible"

                        4 ->
                            "utilisateur/mot de passe incorrect"

                        5 ->
                            "non autorisé"

                        else ->
                            "code $returnCode"
                    }

                error(
                    "Connexion MQTT refusée : $reason"
                )
            }
        }

        private fun sendSubscribe(
            topic: String
        ) {
            val body =
                ByteArrayOutputStream()

            val id =
                nextPacketId()

            writeUnsignedShort(
                body,
                id
            )

            writeUtf8(
                body,
                topic
            )

            body.write(
                0
            )

            sendPacket(
                0x82,
                body.toByteArray()
            )
        }

        private fun nextPacketId(): Int {
            while (
                true
            ) {
                val current =
                    packetId.getAndIncrement()

                val normalized =
                    when {
                        current <= 0 ->
                            1

                        current > 65_535 -> {
                            packetId.compareAndSet(
                                current + 1,
                                2
                            )

                            1
                        }

                        else ->
                            current
                    }

                if (
                    normalized in 1..65_535
                ) {
                    return normalized
                }
            }
        }

        private fun clientId(): String {
            val safeId =
                config.id
                    .replace(
                        Regex(
                            """[^A-Za-z0-9_-]"""
                        ),
                        "_"
                    )

            val suffix =
                Integer.toHexString(
                    System.identityHashCode(
                        this@MqttRuntimeManager
                    )
                )

            return (
                "xiaovv-" +
                safeId +
                "-" +
                suffix
            )
                .take(
                    23
                )
        }

        private fun sendPacketIdOnly(
            header: Int,
            id: Int
        ) {
            sendPacket(
                header,
                byteArrayOf(
                    (
                        id ushr 8
                    )
                        .toByte(),
                    id.toByte()
                )
            )
        }

        private fun sendPacket(
            header: Int,
            body: ByteArray
        ) {
            synchronized(
                outputLock
            ) {
                val currentOutput =
                    output
                        ?: error(
                            "Socket MQTT non connectée"
                        )

                currentOutput.write(
                    header
                )

                writeRemainingLength(
                    currentOutput,
                    body.size
                )

                if (
                    body.isNotEmpty()
                ) {
                    currentOutput.write(
                        body
                    )
                }

                currentOutput.flush()

                lastWriteAt =
                    System.currentTimeMillis()
            }
        }

        private fun closeSocket() {
            connected.set(
                false
            )

            synchronized(
                outputLock
            ) {
                output =
                    null
            }

            val current =
                socket

            socket =
                null

            try {
                current?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readPacket(
        input: InputStream
    ): Packet {
        val header =
            input.read()

        if (
            header < 0
        ) {
            throw EOFException(
                "Connexion MQTT fermée"
            )
        }

        val remaining =
            readRemainingLength(
                input
            )

        require(
            remaining in 0..MAX_PACKET_BYTES
        ) {
            "Paquet MQTT trop volumineux : $remaining octets"
        }

        val body =
            ByteArray(
                remaining
            )

        var offset =
            0

        while (
            offset < remaining
        ) {
            val count =
                input.read(
                    body,
                    offset,
                    remaining - offset
                )

            if (
                count < 0
            ) {
                throw EOFException(
                    "Paquet MQTT tronqué"
                )
            }

            offset +=
                count
        }

        return Packet(
            header = header,
            body = body
        )
    }

    private fun readRemainingLength(
        input: InputStream
    ): Int {
        var multiplier =
            1

        var value =
            0

        var count =
            0

        while (
            true
        ) {
            val encoded =
                input.read()

            if (
                encoded < 0
            ) {
                throw EOFException(
                    "Remaining Length MQTT tronqué"
                )
            }

            value +=
                (
                    encoded and
                    127
                ) *
                multiplier

            count++

            if (
                encoded and
                128 ==
                0
            ) {
                return value
            }

            require(
                count < 4
            ) {
                "Remaining Length MQTT invalide"
            }

            multiplier *=
                128
        }
    }

    private fun writeRemainingLength(
        output: OutputStream,
        length: Int
    ) {
        require(
            length >= 0
        )

        var value =
            length

        do {
            var encoded =
                value %
                128

            value /=
                128

            if (
                value > 0
            ) {
                encoded =
                    encoded or
                    128
            }

            output.write(
                encoded
            )
        } while (
            value > 0
        )
    }

    private fun writeUtf8(
        output: ByteArrayOutputStream,
        value: String
    ) {
        val bytes =
            value.toByteArray(
                StandardCharsets.UTF_8
            )

        require(
            bytes.size <= 65_535
        ) {
            "Chaîne MQTT trop longue"
        }

        writeUnsignedShort(
            output,
            bytes.size
        )

        output.write(
            bytes
        )
    }

    private fun writeUnsignedShort(
        output: ByteArrayOutputStream,
        value: Int
    ) {
        require(
            value in 0..65_535
        )

        output.write(
            value ushr 8
        )

        output.write(
            value and
            0xFF
        )
    }

    private fun unsignedShort(
        bytes: ByteArray,
        offset: Int
    ): Int {
        require(
            offset >= 0 &&
            offset + 1 <
            bytes.size
        )

        return (
            (
                bytes[
                    offset
                ]
                    .toInt() and
                0xFF
            ) shl
            8
        ) or
        (
            bytes[
                offset + 1
            ]
                .toInt() and
            0xFF
        )
    }

    override fun close() {
        started =
            false

        generation.incrementAndGet()

        stopWorkers()

        executor.shutdownNow()

        try {
            executor.awaitTermination(
                2,
                TimeUnit.SECONDS
            )
        } catch (_: InterruptedException) {
            Thread.currentThread()
                .interrupt()
        }
    }
}
