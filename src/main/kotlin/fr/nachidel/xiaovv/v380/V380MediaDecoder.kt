package fr.nachidel.xiaovv.v380

import fr.nachidel.xiaovv.logging.logger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class V380MediaDecoder(
    authTicket: Int
) {

    private val log = logger<V380MediaDecoder>()

    companion object {
        const val INNER_HEADER_SIZE = 16

        private const val VIDEO_CRYPTO_BLOCK_SIZE = 80
        private const val VIDEO_ENCRYPTED_SIZE = 64
        private const val AES_BLOCK_SIZE = 16

        private val AAC_SAMPLE_RATES = intArrayOf(
            96_000,
            88_200,
            64_000,
            48_000,
            44_100,
            32_000,
            24_000,
            22_050,
            16_000,
            12_000,
            11_025,
            8_000,
            7_350
        )
    }

    private val mediaKey: ByteArray =
        generateMediaKey(authTicket)

    init {
        log.info(
            "Décodeur média V380 initialisé"
        )

        /*
         * La clé média n'est volontairement jamais écrite dans les logs.
         */
        log.debug(
            "Clé média générée depuis le ticket {}",
            authTicket
        )
    }

    data class DecodedVideoFrame(
        val outerType: Int,
        val frameId: Long,
        val frameType: Int,
        val frameRate: Int,
        val timestamp: Long,
        val keyFrame: Boolean,
        val payload: ByteArray,
        val nalType: Int?
    )

    data class DecodedAudioFrame(
        val outerType: Int,
        val frameId: Long,
        val frameType: Int,
        val frameRate: Int,
        val timestamp: Long,
        val audioObjectType: Int,
        val sampleRate: Int,
        val samplingFrequencyIndex: Int,
        val channels: Int,
        val samplesPerAccessUnit: Int,
        /**
         * Charge utile AAC brute, sans header ADTS.
         */
        val payload: ByteArray,
        /**
         * AudioSpecificConfig MPEG-4, utilisé dans le SDP RTSP.
         */
        val audioSpecificConfig: ByteArray
    )

    private data class InnerHeader(
        val frameId: Long,
        val frameType: Int,
        val frameRate: Int,
        val timestamp: Long
    )

    private data class AdtsHeader(
        val audioObjectType: Int,
        val samplingFrequencyIndex: Int,
        val sampleRate: Int,
        val channelConfiguration: Int,
        val frameLength: Int,
        val headerLength: Int,
        val rawDataBlocks: Int
    )

    /*
     * ============================================================
     * VIDEO
     * ============================================================
     */

    fun decode(
        frame: V380MediaParser.MediaFrame
    ): DecodedVideoFrame {

        require(frame.isVideo) {
            "La frame reçue n'est pas une frame vidéo"
        }

        require(
            frame.data.size > INNER_HEADER_SIZE
        ) {
            "Frame vidéo trop courte : ${frame.data.size} octets"
        }

        val header =
            parseInnerHeader(
                frame.data
            )

        var payload =
            frame.data.copyOfRange(
                INNER_HEADER_SIZE,
                frame.data.size
            )

        log.debug(
            "VIDEO interne : id={}, type=0x{}, rate={}, timestamp={}, payload={} octets",
            header.frameId,
            header.frameType
                .toString(16)
                .uppercase()
                .padStart(2, '0'),
            header.frameRate,
            header.timestamp,
            payload.size
        )

        decryptVideo(
            payload
        )

        val startCodeOffset =
            findAnnexBStartCode(
                payload,
                maxSearch = 16
            )

        if (startCodeOffset < 0) {

            log.warn(
                "Aucun start code H.265 trouvé après déchiffrement"
            )

            log.debug(
                "Début payload déchiffré : {}",
                V380Protocol.hex(
                    payload,
                    48
                )
            )

        } else if (startCodeOffset > 0) {

            payload =
                payload.copyOfRange(
                    startCodeOffset,
                    payload.size
                )
        }

        val keyFrame =
            frame.type ==
                    V380MediaParser.TYPE_VIDEO_28

        val nalType =
            detectH265NalType(
                payload
            )

        log.trace(
            "VIDEO décodée : type=0x{}, keyframe={}, NAL={}, {} octets",
            frame.type
                .toString(16)
                .uppercase()
                .padStart(2, '0'),
            keyFrame,
            nalType?.toString() ?: "?",
            payload.size
        )

        return DecodedVideoFrame(
            outerType = frame.type,
            frameId = header.frameId,
            frameType = header.frameType,
            frameRate = header.frameRate,
            timestamp = header.timestamp,
            keyFrame = keyFrame,
            payload = payload,
            nalType = nalType
        )
    }

    /*
     * ============================================================
     * AUDIO AAC
     * ============================================================
     *
     * Capture Xiaovv du 02/09/2026 :
     *
     * - type média extérieur : 0x18
     * - header interne : 16 octets, identique au principe vidéo
     * - chiffrement : AES-128 ECB sur tous les blocs complets de 16 octets
     * - résultat : AAC LC encapsulé ADTS
     * - fréquence : 16 kHz
     * - mono
     */

    fun decodeAudio(
        frame: V380MediaParser.MediaFrame
    ): DecodedAudioFrame {

        require(frame.isAudio) {
            "La frame reçue n'est pas une frame audio"
        }

        require(
            frame.data.size > INNER_HEADER_SIZE
        ) {
            "Frame audio trop courte : ${frame.data.size} octets"
        }

        val header =
            parseInnerHeader(
                frame.data
            )

        val adtsFrame =
            frame.data.copyOfRange(
                INNER_HEADER_SIZE,
                frame.data.size
            )

        decryptAudio(
            adtsFrame
        )

        val adts =
            parseAdtsHeader(
                adtsFrame
            )

        require(
            adts.frameLength <= adtsFrame.size
        ) {
            "Frame ADTS annoncée trop grande : " +
                    "${adts.frameLength}/${adtsFrame.size} octets"
        }

        require(
            adts.frameLength > adts.headerLength
        ) {
            "Frame AAC vide : ${adts.frameLength} octets"
        }

        val aacPayload =
            adtsFrame.copyOfRange(
                adts.headerLength,
                adts.frameLength
            )

        val audioSpecificConfig =
            buildAudioSpecificConfig(
                audioObjectType =
                    adts.audioObjectType,
                samplingFrequencyIndex =
                    adts.samplingFrequencyIndex,
                channelConfiguration =
                    adts.channelConfiguration
            )

        val samplesPerAccessUnit =
            1024 *
                    (adts.rawDataBlocks + 1)

        log.trace(
            "AUDIO décodé : type=0x{}, id={}, timestamp={}, AAC-LC {} Hz {} canal(aux), AU={} octets",
            frame.type
                .toString(16)
                .uppercase()
                .padStart(2, '0'),
            header.frameId,
            header.timestamp,
            adts.sampleRate,
            adts.channelConfiguration,
            aacPayload.size
        )

        return DecodedAudioFrame(
            outerType = frame.type,
            frameId = header.frameId,
            frameType = header.frameType,
            frameRate = header.frameRate,
            timestamp = header.timestamp,
            audioObjectType = adts.audioObjectType,
            sampleRate = adts.sampleRate,
            samplingFrequencyIndex = adts.samplingFrequencyIndex,
            channels = adts.channelConfiguration,
            samplesPerAccessUnit = samplesPerAccessUnit,
            payload = aacPayload,
            audioSpecificConfig = audioSpecificConfig
        )
    }

    private fun parseInnerHeader(
        data: ByteArray
    ): InnerHeader {

        require(
            data.size >= INNER_HEADER_SIZE
        ) {
            "Header média interne incomplet"
        }

        return InnerHeader(
            frameId =
                V380Protocol.readUInt32LE(
                    data,
                    0
                ),
            frameType =
                V380Protocol.readUInt16LE(
                    data,
                    4
                ),
            frameRate =
                V380Protocol.readUInt16LE(
                    data,
                    6
                ),
            timestamp =
                readUInt64LE(
                    data,
                    8
                )
        )
    }

    private fun parseAdtsHeader(
        data: ByteArray
    ): AdtsHeader {

        require(
            data.size >= 7
        ) {
            "Frame ADTS trop courte : ${data.size} octets"
        }

        val b0 =
            data[0].toInt() and 0xFF

        val b1 =
            data[1].toInt() and 0xFF

        require(
            b0 == 0xFF &&
                    (b1 and 0xF0) == 0xF0
        ) {
            "Synchronisation ADTS absente : ${V380Protocol.hex(data, 16)}"
        }

        val layer =
            (b1 ushr 1) and 0x03

        require(layer == 0) {
            "Layer ADTS invalide : $layer"
        }

        val protectionAbsent =
            b1 and 0x01

        val b2 =
            data[2].toInt() and 0xFF

        val b3 =
            data[3].toInt() and 0xFF

        val b4 =
            data[4].toInt() and 0xFF

        val b5 =
            data[5].toInt() and 0xFF

        val b6 =
            data[6].toInt() and 0xFF

        val profile =
            (b2 ushr 6) and 0x03

        val audioObjectType =
            profile + 1

        val samplingFrequencyIndex =
            (b2 ushr 2) and 0x0F

        require(
            samplingFrequencyIndex in AAC_SAMPLE_RATES.indices
        ) {
            "Fréquence AAC ADTS non supportée : index=$samplingFrequencyIndex"
        }

        val sampleRate =
            AAC_SAMPLE_RATES[
                samplingFrequencyIndex
            ]

        val channelConfiguration =
            ((b2 and 0x01) shl 2) or
                    ((b3 ushr 6) and 0x03)

        require(
            channelConfiguration in 1..7
        ) {
            "Configuration canaux AAC non supportée : $channelConfiguration"
        }

        val frameLength =
            ((b3 and 0x03) shl 11) or
                    (b4 shl 3) or
                    ((b5 ushr 5) and 0x07)

        val headerLength =
            if (protectionAbsent == 1) {
                7
            } else {
                9
            }

        require(
            data.size >= headerLength
        ) {
            "Header ADTS CRC incomplet"
        }

        val rawDataBlocks =
            b6 and 0x03

        return AdtsHeader(
            audioObjectType = audioObjectType,
            samplingFrequencyIndex = samplingFrequencyIndex,
            sampleRate = sampleRate,
            channelConfiguration = channelConfiguration,
            frameLength = frameLength,
            headerLength = headerLength,
            rawDataBlocks = rawDataBlocks
        )
    }

    private fun buildAudioSpecificConfig(
        audioObjectType: Int,
        samplingFrequencyIndex: Int,
        channelConfiguration: Int
    ): ByteArray {

        require(
            audioObjectType in 1..31
        ) {
            "Audio Object Type AAC invalide : $audioObjectType"
        }

        require(
            samplingFrequencyIndex in 0..15
        ) {
            "Index de fréquence AAC invalide : $samplingFrequencyIndex"
        }

        require(
            channelConfiguration in 0..15
        ) {
            "Configuration canaux AAC invalide : $channelConfiguration"
        }

        return byteArrayOf(
            (
                    (audioObjectType shl 3) or
                            (samplingFrequencyIndex ushr 1)
                    )
                .toByte(),
            (
                    ((samplingFrequencyIndex and 0x01) shl 7) or
                            (channelConfiguration shl 3)
                    )
                .toByte()
        )
    }

    /*
     * ============================================================
     * DÉCHIFFREMENT
     * ============================================================
     */

    private fun decryptVideo(
        data: ByteArray
    ) {

        if (data.size < AES_BLOCK_SIZE) {
            return
        }

        val cipher =
            createMediaCipher()

        var offset = 0

        while (
            offset + VIDEO_ENCRYPTED_SIZE <=
            data.size
        ) {

            for (block in 0 until 4) {

                val blockOffset =
                    offset +
                            (block * AES_BLOCK_SIZE)

                val decrypted =
                    cipher.doFinal(
                        data,
                        blockOffset,
                        AES_BLOCK_SIZE
                    )

                decrypted.copyInto(
                    destination = data,
                    destinationOffset = blockOffset
                )
            }

            offset +=
                VIDEO_CRYPTO_BLOCK_SIZE
        }
    }

    private fun decryptAudio(
        data: ByteArray
    ) {

        val alignedSize =
            (data.size / AES_BLOCK_SIZE) *
                    AES_BLOCK_SIZE

        if (alignedSize == 0) {
            return
        }

        val cipher =
            createMediaCipher()

        var offset = 0

        while (offset < alignedSize) {

            val decrypted =
                cipher.doFinal(
                    data,
                    offset,
                    AES_BLOCK_SIZE
                )

            decrypted.copyInto(
                destination = data,
                destinationOffset = offset
            )

            offset +=
                AES_BLOCK_SIZE
        }
    }

    private fun createMediaCipher(): Cipher {

        return Cipher.getInstance(
            "AES/ECB/NoPadding"
        ).apply {

            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(
                    mediaKey,
                    "AES"
                )
            )
        }
    }

    private fun generateMediaKey(
        ticket: Int
    ): ByteArray {

        val key =
            ByteArray(16)

        V380Protocol.writeInt32LE(
            key,
            0,
            ticket
        )

        writeUInt64LE(
            key,
            4,
            0x618123462c14795cL
        )

        V380Protocol.writeInt32LE(
            key,
            12,
            0x82800df0.toInt()
        )

        return key
    }

    /*
     * ============================================================
     * H.265
     * ============================================================
     */

    private fun findAnnexBStartCode(
        data: ByteArray,
        maxSearch: Int
    ): Int {

        if (data.size < 4) {
            return -1
        }

        val end =
            minOf(
                maxSearch,
                data.size - 2
            )

        for (i in 0 until end) {

            if (
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {

                if (
                    i > 0 &&
                    data[i - 1] == 0.toByte()
                ) {
                    return i - 1
                }

                return i
            }
        }

        return -1
    }

    private fun detectH265NalType(
        payload: ByteArray
    ): Int? {

        if (payload.size < 4) {
            return null
        }

        val offset =
            when {

                payload.size >= 5 &&
                        payload[0] == 0.toByte() &&
                        payload[1] == 0.toByte() &&
                        payload[2] == 0.toByte() &&
                        payload[3] == 1.toByte() ->
                    4

                payload[0] == 0.toByte() &&
                        payload[1] == 0.toByte() &&
                        payload[2] == 1.toByte() ->
                    3

                else ->
                    return null
            }

        if (offset >= payload.size) {
            return null
        }

        return (
                payload[offset].toInt() ushr 1
                ) and 0x3F
    }

    /*
     * ============================================================
     * UINT64 LE
     * ============================================================
     */

    private fun readUInt64LE(
        data: ByteArray,
        offset: Int
    ): Long {

        require(
            offset >= 0 &&
                    offset + 8 <= data.size
        ) {
            "Lecture uint64 hors limites"
        }

        var result = 0L

        for (i in 0 until 8) {

            result =
                result or
                        (
                                (data[offset + i]
                                    .toLong() and 0xFFL)
                                        shl (8 * i)
                                )
        }

        return result
    }

    private fun writeUInt64LE(
        data: ByteArray,
        offset: Int,
        value: Long
    ) {

        require(
            offset >= 0 &&
                    offset + 8 <= data.size
        ) {
            "Écriture uint64 hors limites"
        }

        for (i in 0 until 8) {

            data[offset + i] =
                (
                        (value ushr (8 * i)) and
                                0xFF
                        )
                    .toByte()
        }
    }
}
