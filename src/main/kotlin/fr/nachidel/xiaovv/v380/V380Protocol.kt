package fr.nachidel.xiaovv.v380

import fr.nachidel.xiaovv.logging.logger
import java.nio.charset.StandardCharsets

object V380Protocol {

    private val log = logger<V380Protocol>()

    /*
     * ============================================================
     * COMMANDES
     * ============================================================
     */

    const val CMD_LOGIN = 31167
    const val CMD_LOGIN_RESPONSE = 31168

    const val CMD_VIDEO_LOGIN = 301
    const val CMD_VIDEO_LOGIN_RESPONSE = 401

    const val CMD_START_VIDEO = 303

    const val CMD_STREAM_INIT = 8449


    /*
     * ============================================================
     * TAILLES
     * ============================================================
     */

    const val LOGIN_REQUEST_SIZE = 520
    const val LOGIN_RESPONSE_SIZE = 256

    const val VIDEO_LOGIN_REQUEST_SIZE = 256
    const val VIDEO_LOGIN_RESPONSE_SIZE = 32

    const val START_VIDEO_REQUEST_SIZE = 256

    const val STREAM_INIT_REQUEST_SIZE = 16


    /*
     * ============================================================
     * VALEURS OBSERVÉES DANS NOTRE PCAP
     * ============================================================
     */

    /**
     * FPS demandé par l'application officielle.
     */
    const val DEFAULT_REQUEST_FPS = 20

    /**
     * Offset 22 du paquet 301.
     *
     * Notre capture contient :
     *
     * 00 10 00 00
     *
     * soit 0x1000.
     *
     * La signification exacte n'est pas encore certaine.
     */
    private const val STREAM_FLAG = 0x1000

    /**
     * Offset 30 du paquet 301.
     *
     * Notre capture contient :
     *
     * 01 01 00 00
     *
     * soit 0x0101.
     *
     * Signification exacte inconnue pour le moment.
     */
    private const val STREAM_TRAILING_FLAG = 0x0101

    /**
     * Valeur envoyée par l'application dans la commande 303.
     *
     * Capture :
     *
     * 2F 01 00 00
     * 01 30 00 00
     *
     * donc :
     *
     * command = 303
     * value   = 0x3001
     */
    private const val START_VIDEO_VALUE = 0x3001

    /**
     * Valeur observée dans la commande 8449.
     */
    private const val STREAM_INIT_FLAG = 0x1000


    /*
     * ============================================================
     * RÉSOLUTIONS
     * ============================================================
     */

    /**
     * Dans le protocole historique :
     *
     * 0 = flux basse résolution
     * 1 = flux haute résolution
     *
     * Notre capture utilise 0 et obtient :
     *
     * 640 x 360
     */
    const val RESOLUTION_LOW = 0

    const val RESOLUTION_HIGH = 1


    /*
     * ============================================================
     * LITTLE ENDIAN
     * ============================================================
     */

    fun readInt32LE(
        data: ByteArray,
        offset: Int = 0
    ): Int {

        requireRange(
            data,
            offset,
            4
        )

        return (
                (data[offset].toInt() and 0xFF) or
                        ((data[offset + 1].toInt() and 0xFF) shl 8) or
                        ((data[offset + 2].toInt() and 0xFF) shl 16) or
                        ((data[offset + 3].toInt() and 0xFF) shl 24)
                )
    }


    fun readUInt32LE(
        data: ByteArray,
        offset: Int = 0
    ): Long {

        return readInt32LE(
            data,
            offset
        ).toLong() and 0xFFFFFFFFL
    }


    fun readUInt16LE(
        data: ByteArray,
        offset: Int = 0
    ): Int {

        requireRange(
            data,
            offset,
            2
        )

        return (
                (data[offset].toInt() and 0xFF) or
                        ((data[offset + 1].toInt() and 0xFF) shl 8)
                )
    }


    fun writeInt32LE(
        data: ByteArray,
        offset: Int,
        value: Int
    ) {

        requireRange(
            data,
            offset,
            4
        )

        data[offset] =
            (value and 0xFF).toByte()

        data[offset + 1] =
            ((value ushr 8) and 0xFF).toByte()

        data[offset + 2] =
            ((value ushr 16) and 0xFF).toByte()

        data[offset + 3] =
            ((value ushr 24) and 0xFF).toByte()
    }


    fun writeUInt32LE(
        data: ByteArray,
        offset: Int,
        value: Long
    ) {

        require(
            value in 0..0xFFFFFFFFL
        ) {
            "Valeur uint32 invalide : $value"
        }

        writeInt32LE(
            data,
            offset,
            value.toInt()
        )
    }


    fun writeUInt16LE(
        data: ByteArray,
        offset: Int,
        value: Int
    ) {

        require(
            value in 0..0xFFFF
        ) {
            "Valeur uint16 invalide : $value"
        }

        requireRange(
            data,
            offset,
            2
        )

        data[offset] =
            (value and 0xFF).toByte()

        data[offset + 1] =
            ((value ushr 8) and 0xFF).toByte()
    }


    /*
     * ============================================================
     * VIDEO LOGIN 301
     * ============================================================
     *
     * Paquet observé :
     *
     * OFFSET  TYPE     VALEUR
     *
     * 0       int32    301
     * 4       uint32   deviceId
     * 8       uint32   0
     * 12      uint16   fps = 20
     * 14      uint32   authTicket
     * 18      uint32   0
     * 22      uint32   0x1000
     * 26      uint32   résolution
     * 30      uint32   0x0101
     *
     * Total : 256 octets
     */

    fun buildVideoLoginRequest(
        deviceId: Long,
        authTicket: Int,
        requestFps: Int = DEFAULT_REQUEST_FPS,
        resolution: Int = RESOLUTION_LOW
    ): ByteArray {

        require(
            deviceId in 0..0xFFFFFFFFL
        ) {
            "Device ID invalide : $deviceId"
        }

        require(
            requestFps in 10..20
        ) {
            "FPS demandé invalide : $requestFps"
        }

        require(
            resolution == RESOLUTION_LOW ||
                    resolution == RESOLUTION_HIGH
        ) {
            "Résolution invalide : $resolution"
        }

        val packet =
            ByteArray(
                VIDEO_LOGIN_REQUEST_SIZE
            )

        /*
         * Commande
         */
        writeInt32LE(
            packet,
            0,
            CMD_VIDEO_LOGIN
        )

        /*
         * Octet supplémentaire observé dans
         * le paquet 301 de l'application officielle.
         */
        packet[32] = 0x01

        /*
         * Device ID
         */
        writeUInt32LE(
            packet,
            4,
            deviceId
        )

        /*
         * unknown1 = 0
         */
        writeInt32LE(
            packet,
            8,
            0
        )

        /*
         * FPS
         */
        writeUInt16LE(
            packet,
            12,
            requestFps
        )

        /*
         * Ticket obtenu pendant l'authentification.
         */
        writeInt32LE(
            packet,
            14,
            authTicket
        )

        /*
         * unknown3 = 0
         */
        writeInt32LE(
            packet,
            18,
            0
        )

        /*
         * Valeur exacte observée dans notre PCAP.
         */
        writeInt32LE(
            packet,
            22,
            STREAM_FLAG
        )

        /*
         * Résolution.
         */
        writeInt32LE(
            packet,
            26,
            resolution
        )

        /*
         * Valeur exacte observée dans notre PCAP.
         */
        writeInt32LE(
            packet,
            30,
            STREAM_TRAILING_FLAG
        )

        log.debug(
            "Paquet VIDEO_LOGIN construit : " +
                    "deviceId={}, ticket={}, fps={}, resolution={}",
            deviceId,
            authTicket,
            requestFps,
            resolution
        )

        return packet
    }


    /*
     * ============================================================
     * VIDEO LOGIN RESPONSE 401
     * ============================================================
     *
     * Notre capture :
     *
     * 91 01 00 00
     * E9 03 00 00
     * 14 00
     * 80 02 00 00
     * 68 01 00 00
     * ...
     *
     * donne :
     *
     * command = 401
     * result  = 1001
     * fps     = 20
     * width   = 640
     * height  = 360
     */

    data class VideoLoginResponse(
        val command: Int,
        val result: Int,
        val fps: Int,
        val width: Int,
        val height: Int
    ) {

        val success: Boolean
            get() =
                command ==
                        CMD_VIDEO_LOGIN_RESPONSE &&
                        (
                                result == 1001 ||
                                        result == 402
                                )
    }


    fun parseVideoLoginResponse(
        packet: ByteArray
    ): VideoLoginResponse {

        require(
            packet.size >= 18
        ) {
            "Réponse VIDEO_LOGIN trop courte : " +
                    "${packet.size} octets"
        }

        val response =
            VideoLoginResponse(
                command =
                    readInt32LE(
                        packet,
                        0
                    ),

                result =
                    readInt32LE(
                        packet,
                        4
                    ),

                fps =
                    readUInt16LE(
                        packet,
                        8
                    ),

                width =
                    readInt32LE(
                        packet,
                        10
                    ),

                height =
                    readInt32LE(
                        packet,
                        14
                    )
            )

        log.debug(
            "Réponse VIDEO_LOGIN : " +
                    "command={}, result={}, fps={}, résolution={}x{}",
            commandDescription(
                response.command
            ),
            response.result,
            response.fps,
            response.width,
            response.height
        )

        if (response.success) {

            log.info(
                "Connexion flux acceptée : {}x{} @ {} fps",
                response.width,
                response.height,
                response.fps
            )

        } else {

            log.error(
                "Connexion flux refusée : code {}",
                response.result
            )
        }

        return response
    }


    /*
     * ============================================================
     * START VIDEO 303
     * ============================================================
     */

    fun buildStartVideoRequest(): ByteArray {

        val packet =
            ByteArray(
                START_VIDEO_REQUEST_SIZE
            )

        writeInt32LE(
            packet,
            0,
            CMD_START_VIDEO
        )

        /*
         * Valeur EXACTE observée dans notre capture.
         *
         * Je ne lui donne volontairement pas encore
         * de signification fonctionnelle.
         */
        writeInt32LE(
            packet,
            4,
            START_VIDEO_VALUE
        )

        log.debug(
            "Paquet START_VIDEO construit : value=0x{}",
            START_VIDEO_VALUE
                .toString(16)
                .uppercase()
        )

        return packet
    }


    /*
     * ============================================================
     * STREAM INIT 8449
     * ============================================================
     *
     * Capture :
     *
     * 01 21 00 00
     * 00 00 00 00
     * 00 10 00 00
     * 00 00 00 00
     */

    fun buildStreamInitRequest(): ByteArray {

        val packet =
            ByteArray(
                STREAM_INIT_REQUEST_SIZE
            )

        writeInt32LE(
            packet,
            0,
            CMD_STREAM_INIT
        )

        writeInt32LE(
            packet,
            4,
            0
        )

        writeInt32LE(
            packet,
            8,
            STREAM_INIT_FLAG
        )

        writeInt32LE(
            packet,
            12,
            0
        )

        return packet
    }


    /*
     * ============================================================
     * COMMANDES
     * ============================================================
     */

    fun getCommand(
        data: ByteArray
    ): Int {

        require(
            data.size >= 4
        ) {
            "Paquet trop court pour contenir une commande"
        }

        return readInt32LE(
            data,
            0
        )
    }


    fun commandName(
        command: Int
    ): String {

        return when (command) {

            CMD_LOGIN ->
                "LOGIN"

            CMD_LOGIN_RESPONSE ->
                "LOGIN_RESPONSE"

            CMD_VIDEO_LOGIN ->
                "VIDEO_LOGIN"

            CMD_VIDEO_LOGIN_RESPONSE ->
                "VIDEO_LOGIN_RESPONSE"

            CMD_START_VIDEO ->
                "START_VIDEO"

            CMD_STREAM_INIT ->
                "STREAM_INIT"

            else ->
                "UNKNOWN"
        }
    }


    fun commandDescription(
        command: Int
    ): String {

        return buildString {

            append(
                commandName(command)
            )

            append(" [")

            append(command)

            append(" / 0x")

            append(
                command
                    .toString(16)
                    .uppercase()
            )

            append("]")
        }
    }


    /*
     * ============================================================
     * ASCII
     * ============================================================
     */

    fun writeAscii(
        data: ByteArray,
        offset: Int,
        maxLength: Int,
        value: String
    ) {

        require(
            maxLength > 0
        ) {
            "maxLength doit être supérieur à zéro"
        }

        requireRange(
            data,
            offset,
            maxLength
        )

        val bytes =
            value.toByteArray(
                StandardCharsets.US_ASCII
            )

        val length =
            minOf(
                bytes.size,
                maxLength - 1
            )

        bytes.copyInto(
            destination = data,
            destinationOffset = offset,
            startIndex = 0,
            endIndex = length
        )
    }


    fun readAscii(
        data: ByteArray,
        offset: Int,
        maxLength: Int
    ): String {

        requireRange(
            data,
            offset,
            maxLength
        )

        var end = offset

        val limit =
            offset + maxLength

        while (
            end < limit &&
            data[end].toInt() != 0
        ) {
            end++
        }

        return String(
            data,
            offset,
            end - offset,
            StandardCharsets.US_ASCII
        )
    }


    /*
     * ============================================================
     * HEX
     * ============================================================
     */

    fun hex(
        data: ByteArray,
        maxBytes: Int = 64
    ): String {

        if (data.isEmpty()) {
            return ""
        }

        val length =
            minOf(
                data.size,
                maxBytes
            )

        val builder =
            StringBuilder(
                length * 3
            )

        for (i in 0 until length) {

            if (i > 0) {
                builder.append(' ')
            }

            builder.append(
                "%02X".format(
                    data[i].toInt() and 0xFF
                )
            )
        }

        if (data.size > maxBytes) {

            builder.append(
                " ... (${data.size} octets)"
            )
        }

        return builder.toString()
    }


    fun debugPacket(
        direction: String,
        data: ByteArray
    ) {

        if (!log.isDebugEnabled) {
            return
        }

        if (data.size < 4) {

            log.debug(
                "{} paquet de {} octets : {}",
                direction,
                data.size,
                hex(data)
            )

            return
        }

        val command =
            getCommand(data)

        log.debug(
            "{} {} - {} octets",
            direction,
            commandDescription(
                command
            ),
            data.size
        )

        log.debug(
            "{} HEX : {}",
            direction,
            hex(data)
        )
    }


    /*
     * ============================================================
     * RANGE
     * ============================================================
     */

    private fun requireRange(
        data: ByteArray,
        offset: Int,
        length: Int
    ) {

        require(offset >= 0) {
            "Offset négatif : $offset"
        }

        require(length >= 0) {
            "Longueur négative : $length"
        }

        require(
            offset + length <=
                    data.size
        ) {
            "Accès hors limites : " +
                    "offset=$offset, " +
                    "length=$length, " +
                    "buffer=${data.size}"
        }
    }
}