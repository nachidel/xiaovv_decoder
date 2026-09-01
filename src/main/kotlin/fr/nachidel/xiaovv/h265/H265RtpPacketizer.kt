package fr.nachidel.xiaovv.h265

import fr.nachidel.xiaovv.logging.logger
import java.security.SecureRandom

class H265RtpPacketizer(
    private val fps: Int = 20,
    private val payloadType: Int = 96,
    private val mtu: Int = 1200
) {

    private val log = logger<H265RtpPacketizer>()

    companion object {

        const val RTP_CLOCK_RATE = 90_000L
        const val RTP_HEADER_SIZE = 12

        const val NAL_TYPE_FU = 49
        const val FU_HEADER_SIZE = 3

        /**
         * Horloge de la caméra observée :
         * timestamp en millisecondes.
         *
         * 90 000 / 1 000 = 90 ticks RTP / ms.
         */
        private const val RTP_TICKS_PER_MS = 90L

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

    /*
     * ============================================================
     * RTP STATE
     * ============================================================
     */

    private var sequenceNumber =
        random.nextInt(
            0x10000
        )

    /*
     * Timestamp RTP initial aléatoire.
     */
    private var rtpTimestamp =
        random.nextInt()
            .toLong() and 0xFFFFFFFFL

    private val ssrc =
        random.nextInt()
            .toLong() and 0xFFFFFFFFL

    /*
     * Fallback si aucun timestamp caméra n'est disponible.
     */
    private val fallbackTimestampStep =
        RTP_CLOCK_RATE / fps

    /*
     * Dernier timestamp caméra utilisé.
     */
    private var lastCameraTimestamp: Long? =
        null

    /*
     * ============================================================
     * INIT
     * ============================================================
     */

    init {

        require(
            fps > 0
        ) {
            "fps doit être supérieur à zéro"
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
            "Packetizer H.265/RTP initialisé : PT={}, MTU={}, SSRC=0x{}",
            payloadType,
            mtu,
            ssrc
                .toString(16)
                .uppercase()
                .padStart(8, '0')
        )

        log.debug(
            "RTP sequence initiale={} timestamp initial={}",
            sequenceNumber,
            rtpTimestamp
        )
    }

    /*
     * ============================================================
     * RTP PACKET
     * ============================================================
     */

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

            /*
             * RTP version 2.
             */
            packet[0] =
                0x80.toByte()

            /*
             * Marker + Payload Type.
             */
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

    /*
     * ============================================================
     * ACCESS UNIT
     * ============================================================
     */

    fun packetizeAccessUnit(
        nals: List<H265AnnexB.NalUnit>,
        cameraTimestampMs: Long? = null
    ): List<RtpPacket> {

        if (nals.isEmpty()) {

            log.warn(
                "Access Unit H.265 vide"
            )

            return emptyList()
        }

        /*
         * Met à jour l'horloge RTP AVANT de créer
         * les paquets de cette image.
         */
        updateTimestamp(
            cameraTimestampMs
        )

        val currentTimestamp =
            rtpTimestamp

        val packets =
            mutableListOf<RtpPacket>()

        nals.forEachIndexed {
                nalIndex,
                nal ->

            val isLastNal =
                nalIndex ==
                        nals.lastIndex

            val nalPackets =
                packetizeNal(
                    nal = nal,
                    timestamp =
                        currentTimestamp,
                    lastNalOfAccessUnit =
                        isLastNal
                )

            packets.addAll(
                nalPackets
            )
        }

        log.trace(
            "RTP Access Unit : {} NAL -> {} paquet(s), cameraTs={}, rtpTs={}",
            nals.size,
            packets.size,
            cameraTimestampMs,
            currentTimestamp
        )

        return packets
    }

    /*
     * ============================================================
     * TIMESTAMP CAMERA -> RTP
     * ============================================================
     */

    private fun updateTimestamp(
        cameraTimestampMs: Long?
    ) {

        /*
         * Aucun timestamp caméra :
         *
         * on garde l'ancien comportement comme fallback.
         */
        if (cameraTimestampMs == null) {

            rtpTimestamp =
                (
                        rtpTimestamp +
                                fallbackTimestampStep
                        ) and 0xFFFFFFFFL

            return
        }

        val previous =
            lastCameraTimestamp

        /*
         * Première frame :
         *
         * on conserve le timestamp RTP aléatoire initial
         * comme origine.
         */
        if (previous == null) {

            lastCameraTimestamp =
                cameraTimestampMs

            log.debug(
                "Synchronisation horloge RTP sur timestamp caméra {}",
                cameraTimestampMs
            )

            return
        }

        val deltaMs =
            cameraTimestampMs -
                    previous

        /*
         * Une réinitialisation caméra ou une grosse anomalie
         * ne doit pas provoquer un saut RTP gigantesque.
         *
         * 0..2 secondes est largement suffisant entre
         * deux images normales.
         */
        if (
            deltaMs <= 0 ||
            deltaMs > 2_000
        ) {

            log.warn(
                "Timestamp caméra discontinu : {} -> {} (delta={} ms), resynchronisation",
                previous,
                cameraTimestampMs,
                deltaMs
            )

            lastCameraTimestamp =
                cameraTimestampMs

            /*
             * On avance simplement d'une image estimée
             * pour conserver un RTP monotone.
             */
            rtpTimestamp =
                (
                        rtpTimestamp +
                                fallbackTimestampStep
                        ) and 0xFFFFFFFFL

            return
        }

        /*
         * Exemple :
         *
         * delta caméra = 60 ms
         *
         * 60 × 90 = 5400 ticks RTP
         */
        val deltaRtp =
            deltaMs *
                    RTP_TICKS_PER_MS

        rtpTimestamp =
            (
                    rtpTimestamp +
                            deltaRtp
                    ) and 0xFFFFFFFFL

        lastCameraTimestamp =
            cameraTimestampMs
    }

    /*
     * ============================================================
     * NAL
     * ============================================================
     */

    private fun packetizeNal(
        nal: H265AnnexB.NalUnit,
        timestamp: Long,
        lastNalOfAccessUnit: Boolean
    ): List<RtpPacket> {

        require(
            nal.data.size >= 2
        ) {
            "NAL H.265 trop courte"
        }

        val maxRtpPayload =
            mtu -
                    RTP_HEADER_SIZE

        /*
         * Petite NAL :
         * un seul paquet RTP.
         */
        if (
            nal.data.size <=
            maxRtpPayload
        ) {

            return listOf(
                createPacket(
                    timestamp = timestamp,
                    marker =
                        lastNalOfAccessUnit,
                    payload =
                        nal.data
                )
            )
        }

        /*
         * Grosse NAL :
         * fragmentation FU.
         */
        return fragmentNal(
            nal = nal,
            timestamp = timestamp,
            lastNalOfAccessUnit =
                lastNalOfAccessUnit
        )
    }

    /*
     * ============================================================
     * FU HEVC TYPE 49
     * ============================================================
     */

    private fun fragmentNal(
        nal: H265AnnexB.NalUnit,
        timestamp: Long,
        lastNalOfAccessUnit: Boolean
    ): List<RtpPacket> {

        val original =
            nal.data

        val originalHeader0 =
            original[0].toInt() and 0xFF

        val originalHeader1 =
            original[1].toInt() and 0xFF

        /*
         * Les deux octets du header NAL original
         * sont remplacés par le header FU.
         */
        val nalPayloadOffset =
            2

        val maxFragmentPayload =
            mtu -
                    RTP_HEADER_SIZE -
                    FU_HEADER_SIZE

        require(
            maxFragmentPayload > 0
        ) {
            "MTU insuffisant pour une FU HEVC"
        }

        /*
         * F + LayerId conservés,
         * Type remplacé par 49.
         */
        val fuIndicator0 =
            (
                    (originalHeader0 and 0x81) or
                            (NAL_TYPE_FU shl 1)
                    ) and 0xFF

        val fuIndicator1 =
            originalHeader1

        val originalNalType =
            nal.type and 0x3F

        val packets =
            mutableListOf<RtpPacket>()

        var offset =
            nalPayloadOffset

        while (
            offset <
            original.size
        ) {

            val remaining =
                original.size -
                        offset

            val fragmentSize =
                minOf(
                    remaining,
                    maxFragmentPayload
                )

            val start =
                offset ==
                        nalPayloadOffset

            val end =
                offset +
                        fragmentSize >=
                        original.size

            val payload =
                ByteArray(
                    FU_HEADER_SIZE +
                            fragmentSize
                )

            /*
             * FU Payload Header.
             */
            payload[0] =
                fuIndicator0.toByte()

            payload[1] =
                fuIndicator1.toByte()

            /*
             * FU Header :
             *
             * S | E | FuType
             */
            payload[2] =
                (
                        (if (start) 0x80 else 0x00) or
                                (if (end) 0x40 else 0x00) or
                                originalNalType
                        )
                    .toByte()

            original.copyInto(
                destination =
                    payload,

                destinationOffset =
                    FU_HEADER_SIZE,

                startIndex =
                    offset,

                endIndex =
                    offset +
                            fragmentSize
            )

            /*
             * Marker uniquement sur :
             *
             * - le dernier fragment
             * - de la dernière NAL
             * - de l'Access Unit.
             */
            val marker =
                end &&
                        lastNalOfAccessUnit

            packets.add(
                createPacket(
                    timestamp =
                        timestamp,
                    marker =
                        marker,
                    payload =
                        payload
                )
            )

            offset +=
                fragmentSize
        }

        log.trace(
            "RTP NAL fragmentée : type={} ({}) {} octets -> {} paquets",
            nal.type,
            nal.name,
            nal.size,
            packets.size
        )

        return packets
    }

    /*
     * ============================================================
     * CREATE RTP PACKET
     * ============================================================
     */

    private fun createPacket(
        timestamp: Long,
        marker: Boolean,
        payload: ByteArray
    ): RtpPacket {

        val currentSequence =
            sequenceNumber

        sequenceNumber =
            (
                    sequenceNumber + 1
                    ) and 0xFFFF

        return RtpPacket(
            sequenceNumber =
                currentSequence,

            timestamp =
                timestamp and
                        0xFFFFFFFFL,

            marker =
                marker,

            payloadType =
                payloadType,

            ssrc =
                ssrc,

            payload =
                payload
        )
    }

    /*
     * ============================================================
     * GETTERS
     * ============================================================
     */

    fun getCurrentTimestamp(): Long {
        return rtpTimestamp
    }

    fun getSsrc(): Long {
        return ssrc
    }

    fun getPayloadType(): Int {
        return payloadType
    }
}