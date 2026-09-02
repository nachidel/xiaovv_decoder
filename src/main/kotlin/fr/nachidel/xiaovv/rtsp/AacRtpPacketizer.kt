package fr.nachidel.xiaovv.rtsp

import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.v380.V380MediaDecoder
import java.security.SecureRandom

/**
 * Packetizer RTP AAC au format MPEG4-GENERIC / AAC-hbr (RFC 3640).
 *
 * Chaque frame V380 audio décodée correspond à un Access Unit AAC.
 * L'en-tête ADTS a déjà été retiré par V380MediaDecoder.
 */
class AacRtpPacketizer(
    private val sampleRate: Int = 16_000,
    private val payloadType: Int = RtspStream.AUDIO_PAYLOAD_TYPE,
    private val mtu: Int = 1200
) {

    private val log =
        logger<AacRtpPacketizer>()

    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val AU_HEADERS_LENGTH_SIZE = 2
        private const val AU_HEADER_SIZE = 2
        private const val RTP_PAYLOAD_OVERHEAD =
            AU_HEADERS_LENGTH_SIZE +
                    AU_HEADER_SIZE

        private fun writeUInt16BE(
            data: ByteArray,
            offset: Int,
            value: Int
        ) {

            data[offset] =
                ((value ushr 8) and 0xFF)
                    .toByte()

            data[offset + 1] =
                (value and 0xFF)
                    .toByte()
        }

        private fun writeUInt32BE(
            data: ByteArray,
            offset: Int,
            value: Long
        ) {

            data[offset] =
                ((value ushr 24) and 0xFF)
                    .toByte()

            data[offset + 1] =
                ((value ushr 16) and 0xFF)
                    .toByte()

            data[offset + 2] =
                ((value ushr 8) and 0xFF)
                    .toByte()

            data[offset + 3] =
                (value and 0xFF)
                    .toByte()
        }
    }

    private val random =
        SecureRandom()

    private var sequenceNumber =
        random.nextInt(
            0x10000
        )

    private var rtpTimestamp =
        random.nextInt()
            .toLong() and
                0xFFFFFFFFL

    private val ssrc =
        random.nextInt()
            .toLong() and
                0xFFFFFFFFL

    private var lastCameraTimestamp: Long? =
        null

    init {

        require(
            sampleRate > 0
        ) {
            "Fréquence AAC invalide : $sampleRate"
        }

        require(
            payloadType in 0..127
        ) {
            "Payload Type RTP invalide : $payloadType"
        }

        require(
            mtu >= 256
        ) {
            "MTU RTP trop petit : $mtu"
        }

        log.info(
            "Packetizer AAC/RTP initialisé : PT={}, {} Hz, MTU={}, SSRC=0x{}",
            payloadType,
            sampleRate,
            mtu,
            ssrc
                .toString(16)
                .uppercase()
                .padStart(8, '0')
        )
    }

    data class RtpPacket(
        val sequenceNumber: Int,
        val timestamp: Long,
        val marker: Boolean,
        val payloadType: Int,
        val ssrc: Long,
        val payload: ByteArray
    ) {

        fun toByteArray(): ByteArray {

            val packet =
                ByteArray(
                    RTP_HEADER_SIZE +
                            payload.size
                )

            packet[0] =
                0x80.toByte()

            packet[1] =
                (
                        (if (marker) 0x80 else 0x00) or
                                (payloadType and 0x7F)
                        )
                    .toByte()

            writeUInt16BE(
                packet,
                2,
                sequenceNumber
            )

            writeUInt32BE(
                packet,
                4,
                timestamp
            )

            writeUInt32BE(
                packet,
                8,
                ssrc
            )

            payload.copyInto(
                destination = packet,
                destinationOffset = RTP_HEADER_SIZE
            )

            return packet
        }
    }

    fun packetize(
        frame: V380MediaDecoder.DecodedAudioFrame
    ): RtpPacket {

        require(
            frame.sampleRate == sampleRate
        ) {
            "Fréquence AAC inattendue : ${frame.sampleRate} Hz, attendu $sampleRate Hz"
        }

        val accessUnit =
            frame.payload

        require(
            accessUnit.isNotEmpty()
        ) {
            "Access Unit AAC vide"
        }

        /*
         * SizeLength=13 dans le SDP.
         * Le champ AU-size doit donc tenir sur 13 bits.
         */
        require(
            accessUnit.size < (1 shl 13)
        ) {
            "Access Unit AAC trop grand : ${accessUnit.size} octets"
        }

        val maxAccessUnitSize =
            mtu -
                    RTP_HEADER_SIZE -
                    RTP_PAYLOAD_OVERHEAD

        require(
            accessUnit.size <= maxAccessUnitSize
        ) {
            "Access Unit AAC trop grand pour un paquet RTP : " +
                    "${accessUnit.size} octets (max=$maxAccessUnitSize)"
        }

        updateTimestamp(
            frame
        )

        val payload =
            ByteArray(
                RTP_PAYLOAD_OVERHEAD +
                        accessUnit.size
            )

        /*
         * AU-headers-length = 16 bits.
         */
        payload[0] =
            0x00

        payload[1] =
            0x10

        /*
         * AU-header :
         *
         * 13 bits AU-size + 3 bits AU-Index.
         * AU-Index vaut 0 car un seul AU est transporté par paquet.
         */
        val auHeader =
            accessUnit.size shl 3

        payload[2] =
            ((auHeader ushr 8) and 0xFF)
                .toByte()

        payload[3] =
            (auHeader and 0xFF)
                .toByte()

        accessUnit.copyInto(
            destination = payload,
            destinationOffset =
                RTP_PAYLOAD_OVERHEAD
        )

        val currentSequence =
            sequenceNumber

        sequenceNumber =
            (
                    sequenceNumber + 1
                    ) and 0xFFFF

        return RtpPacket(
            sequenceNumber = currentSequence,
            timestamp = rtpTimestamp,
            marker = true,
            payloadType = payloadType,
            ssrc = ssrc,
            payload = payload
        )
    }

    private fun updateTimestamp(
        frame: V380MediaDecoder.DecodedAudioFrame
    ) {

        val previous =
            lastCameraTimestamp

        if (previous == null) {

            lastCameraTimestamp =
                frame.timestamp

            return
        }

        val deltaMs =
            frame.timestamp -
                    previous

        val fallbackStep =
            frame.samplesPerAccessUnit
                .toLong()

        val deltaRtp =
            if (
                deltaMs > 0 &&
                deltaMs <= 2_000
            ) {

                /*
                 * La caméra utilise des timestamps en millisecondes.
                 * 16 kHz => 16 ticks RTP par milliseconde.
                 */
                (
                        deltaMs *
                                sampleRate
                        ) / 1_000L

            } else {

                log.warn(
                    "Timestamp audio caméra discontinu : {} -> {} (delta={} ms)",
                    previous,
                    frame.timestamp,
                    deltaMs
                )

                fallbackStep
            }

        rtpTimestamp =
            (
                    rtpTimestamp +
                            deltaRtp.coerceAtLeast(1L)
                    ) and 0xFFFFFFFFL

        lastCameraTimestamp =
            frame.timestamp
    }

    fun getSsrc(): Long {
        return ssrc
    }

    fun getPayloadType(): Int {
        return payloadType
    }

    fun getCurrentTimestamp(): Long {
        return rtpTimestamp
    }
}
