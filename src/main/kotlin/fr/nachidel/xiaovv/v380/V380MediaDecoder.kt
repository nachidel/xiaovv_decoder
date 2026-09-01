package fr.nachidel.xiaovv.v380

import fr.nachidel.xiaovv.logging.logger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class V380MediaDecoder(
    authTicket: Int
) {

    private val log = logger<V380MediaDecoder>()

    companion object {

        /**
         * Header interne d'une frame média V380.
         */
        const val INNER_HEADER_SIZE = 16

        /**
         * Le chiffrement vidéo V380 récent fonctionne
         * par groupes de 80 octets :
         *
         * 64 octets chiffrés
         * 16 octets laissés en clair
         */
        private const val CRYPTO_BLOCK_SIZE = 80
        private const val ENCRYPTED_SIZE = 64

        private const val AES_BLOCK_SIZE = 16
    }

    /**
     * Clé AES média dérivée du ticket de session.
     */
    private val mediaKey: ByteArray =
        generateMediaKey(authTicket)

    init {

        log.info(
            "Décodeur média V380 initialisé"
        )

        /*
         * Volontairement, on ne log pas la clé AES.
         */
        log.debug(
            "Clé média générée depuis le ticket {}",
            authTicket
        )
    }

    /*
     * ============================================================
     * FRAME DÉCODÉE
     * ============================================================
     */

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

    /*
     * ============================================================
     * DÉCODAGE FRAME
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

        /*
         * ========================================================
         * HEADER INTERNE 16 OCTETS
         * ========================================================
         */

        val frameId =
            V380Protocol.readUInt32LE(
                frame.data,
                0
            )

        val frameType =
            V380Protocol.readUInt16LE(
                frame.data,
                4
            )

        val frameRate =
            V380Protocol.readUInt16LE(
                frame.data,
                6
            )

        val timestamp =
            readUInt64LE(
                frame.data,
                8
            )

        /*
         * ========================================================
         * PAYLOAD VIDÉO
         * ========================================================
         */

        var payload =
            frame.data.copyOfRange(
                INNER_HEADER_SIZE,
                frame.data.size
            )

        log.debug(
            "VIDEO interne : " +
                    "id={}, type=0x{}, rate={}, timestamp={}, payload={} octets",
            frameId,
            frameType
                .toString(16)
                .uppercase()
                .padStart(2, '0'),
            frameRate,
            timestamp,
            payload.size
        )

        /*
         * Déchiffrement en place.
         */
        decryptVideo(
            payload
        )

        /*
         * Le bitstream doit être en format Annex-B :
         *
         * 00 00 00 01
         *
         * ou :
         *
         * 00 00 01
         *
         * Certains firmwares laissent quelques octets
         * avant le premier start code.
         */
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

            log.debug(
                "Start code Annex-B trouvé à l'offset {}",
                startCodeOffset
            )

            payload =
                payload.copyOfRange(
                    startCodeOffset,
                    payload.size
                )
        }

        /*
         * 0x28 est l'I-frame observée sur ces firmwares.
         */
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
            frameId = frameId,
            frameType = frameType,
            frameRate = frameRate,
            timestamp = timestamp,
            keyFrame = keyFrame,
            payload = payload,
            nalType = nalType
        )
    }

    /*
     * ============================================================
     * DÉCHIFFREMENT VIDÉO
     * ============================================================
     *
     * Firmware récent :
     *
     * [64 octets AES][16 octets clair]
     * [64 octets AES][16 octets clair]
     * ...
     *
     * AES-128 ECB / NoPadding
     */

    private fun decryptVideo(
        data: ByteArray
    ) {

        if (data.size < AES_BLOCK_SIZE) {
            return
        }

        val cipher =
            Cipher.getInstance(
                "AES/ECB/NoPadding"
            )

        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(
                mediaKey,
                "AES"
            )
        )

        var offset = 0

        while (
            offset + ENCRYPTED_SIZE <=
            data.size
        ) {

            /*
             * 64 octets =
             * 4 blocs AES de 16 octets.
             */
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

            /*
             * Les 16 octets suivants restent intacts.
             */
            offset +=
                CRYPTO_BLOCK_SIZE
        }
    }

    /*
     * ============================================================
     * CLÉ MÉDIA
     * ============================================================
     *
     * Construction observée dans l'implémentation récente :
     *
     * offset 0  : ticket uint32 LE
     *
     * offset 4  :
     * 0x618123462c14795c uint64 LE
     *
     * offset 12 :
     * 0x82800df0 uint32 LE
     */

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
     * ANNEX-B
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
                data.size - 3
            )

        for (i in 0 until end) {

            /*
             * 00 00 01
             */
            if (
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {

                /*
                 * Si on a :
                 *
                 * 00 00 00 01
                 *
                 * on retourne le zéro précédent.
                 */
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

    /*
     * ============================================================
     * H.265 NAL TYPE
     * ============================================================
     */

    private fun detectH265NalType(
        payload: ByteArray
    ): Int? {

        if (payload.size < 5) {
            return null
        }

        var offset = 0

        /*
         * 00 00 00 01
         */
        if (
            payload.size >= 5 &&
            payload[0] == 0.toByte() &&
            payload[1] == 0.toByte() &&
            payload[2] == 0.toByte() &&
            payload[3] == 1.toByte()
        ) {

            offset = 4

            /*
             * 00 00 01
             */
        } else if (
            payload[0] == 0.toByte() &&
            payload[1] == 0.toByte() &&
            payload[2] == 1.toByte()
        ) {

            offset = 3

        } else {

            return null
        }

        if (offset >= payload.size) {
            return null
        }

        /*
         * HEVC :
         *
         * forbidden_zero_bit : 1
         * nal_unit_type       : 6
         * nuh_layer_id...
         *
         * nal_unit_type =
         *
         * (byte >> 1) & 0x3F
         */
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