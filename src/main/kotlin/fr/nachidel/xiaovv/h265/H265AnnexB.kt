package fr.nachidel.xiaovv.h265

import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.v380.V380Protocol

object H265AnnexB {

    private val log = logger<H265AnnexB>()

    /*
     * ============================================================
     * TYPES NAL HEVC IMPORTANTS
     * ============================================================
     *
     * H.265 / HEVC :
     *
     * 19 = IDR_W_RADL
     * 20 = IDR_N_LP
     * 21 = CRA_NUT
     *
     * 32 = VPS
     * 33 = SPS
     * 34 = PPS
     *
     * 39 = PREFIX_SEI
     * 40 = SUFFIX_SEI
     */

    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34

    const val NAL_PREFIX_SEI = 39
    const val NAL_SUFFIX_SEI = 40

    /*
     * ============================================================
     * NAL UNIT
     * ============================================================
     */

    data class NalUnit(

        /**
         * Type HEVC compris entre 0 et 63.
         */
        val type: Int,

        /**
         * NAL complète, SANS le start code Annex-B.
         *
         * Elle commence donc directement par
         * les 2 octets du header HEVC.
         */
        val data: ByteArray
    ) {

        val size: Int
            get() = data.size

        /**
         * VPS / SPS / PPS
         */
        val isParameterSet: Boolean
            get() =
                type == NAL_VPS ||
                        type == NAL_SPS ||
                        type == NAL_PPS

        /**
         * Une image pouvant servir de point d'entrée
         * au décodeur.
         */
        val isKeyFrameNal: Boolean
            get() =
                type in 16..21

        val name: String
            get() =
                nalTypeName(type)
    }

    /*
     * ============================================================
     * SPLIT ANNEX-B
     * ============================================================
     *
     * Entrée possible :
     *
     * 00 00 00 01 VPS...
     * 00 00 00 01 SPS...
     * 00 00 00 01 PPS...
     * 00 00 01    IDR...
     *
     * Sortie :
     *
     * [
     *   VPS sans 00 00 00 01,
     *   SPS sans 00 00 00 01,
     *   PPS sans 00 00 00 01,
     *   IDR sans 00 00 01
     * ]
     */

    fun split(
        data: ByteArray
    ): List<NalUnit> {

        if (data.isEmpty()) {
            return emptyList()
        }

        val result =
            mutableListOf<NalUnit>()

        var current =
            findStartCode(
                data = data,
                from = 0
            )

        /*
         * Aucun start code.
         *
         * Ce n'est donc pas un payload Annex-B valide.
         */
        if (current == null) {

            log.warn(
                "Payload H.265 sans start code Annex-B : {} octets",
                data.size
            )

            log.debug(
                "Début payload : {}",
                V380Protocol.hex(
                    data,
                    32
                )
            )

            return emptyList()
        }

        while (current != null) {

            /*
             * Début réel de la NAL :
             *
             * juste après :
             *
             * 00 00 01
             *
             * ou :
             *
             * 00 00 00 01
             */
            val nalStart =
                current.offset +
                        current.length

            /*
             * Cherche la NAL suivante.
             */
            val next =
                findStartCode(
                    data = data,
                    from = nalStart
                )

            var nalEnd =
                next?.offset
                    ?: data.size

            /*
             * Les zéro placés immédiatement avant
             * un start code peuvent faire partie du
             * trailing_zero_8bits Annex-B.
             *
             * On les retire.
             */
            if (next != null) {

                while (
                    nalEnd > nalStart &&
                    data[nalEnd - 1] == 0.toByte()
                ) {

                    nalEnd--
                }
            }

            if (nalEnd > nalStart) {

                val nalData =
                    data.copyOfRange(
                        nalStart,
                        nalEnd
                    )

                if (nalData.size >= 2) {

                    val type =
                        getNalType(
                            nalData
                        )

                    val nal =
                        NalUnit(
                            type = type,
                            data = nalData
                        )

                    result.add(
                        nal
                    )

                    log.trace(
                        "H265 NAL : type={} ({}) size={} octets",
                        type,
                        nal.name,
                        nal.size
                    )

                } else {

                    log.warn(
                        "NAL HEVC trop courte : {} octet(s)",
                        nalData.size
                    )
                }
            }

            current = next
        }

        if (log.isDebugEnabled) {

            log.debug(
                "H265 Annex-B : {} NAL détectée(s) dans {} octets",
                result.size,
                data.size
            )

            for (nal in result) {

                log.debug(
                    "  NAL type={} ({}) - {} octets",
                    nal.type,
                    nal.name,
                    nal.size
                )
            }
        }

        return result
    }

    /*
     * ============================================================
     * TYPE NAL
     * ============================================================
     *
     * Header HEVC :
     *
     * bit 7       forbidden_zero_bit
     * bits 6..1   nal_unit_type
     * ...
     *
     * donc :
     *
     * (byte0 >> 1) & 0x3F
     */

    fun getNalType(
        nal: ByteArray
    ): Int {

        require(
            nal.size >= 2
        ) {
            "Une NAL HEVC doit contenir au moins 2 octets"
        }

        return (
                nal[0]
                    .toInt()
                    .ushr(1)
                ) and 0x3F
    }

    /*
     * ============================================================
     * NOM DU TYPE
     * ============================================================
     */

    fun nalTypeName(
        type: Int
    ): String {

        return when (type) {

            0 ->
                "TRAIL_N"

            1 ->
                "TRAIL_R"

            2 ->
                "TSA_N"

            3 ->
                "TSA_R"

            4 ->
                "STSA_N"

            5 ->
                "STSA_R"

            6 ->
                "RADL_N"

            7 ->
                "RADL_R"

            8 ->
                "RASL_N"

            9 ->
                "RASL_R"

            16 ->
                "BLA_W_LP"

            17 ->
                "BLA_W_RADL"

            18 ->
                "BLA_N_LP"

            19 ->
                "IDR_W_RADL"

            20 ->
                "IDR_N_LP"

            21 ->
                "CRA_NUT"

            NAL_VPS ->
                "VPS"

            NAL_SPS ->
                "SPS"

            NAL_PPS ->
                "PPS"

            35 ->
                "AUD"

            36 ->
                "EOS"

            37 ->
                "EOB"

            38 ->
                "FD"

            NAL_PREFIX_SEI ->
                "PREFIX_SEI"

            NAL_SUFFIX_SEI ->
                "SUFFIX_SEI"

            else ->
                "NAL_$type"
        }
    }

    /*
     * ============================================================
     * PARAMETER SETS
     * ============================================================
     *
     * Très utile pour le SDP RTSP :
     *
     * sprop-vps
     * sprop-sps
     * sprop-pps
     */

    data class ParameterSets(
        val vps: ByteArray?,
        val sps: ByteArray?,
        val pps: ByteArray?
    ) {

        val complete: Boolean
            get() =
                vps != null &&
                        sps != null &&
                        pps != null
    }

    fun findParameterSets(
        nals: List<NalUnit>
    ): ParameterSets {

        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        for (nal in nals) {

            when (nal.type) {

                NAL_VPS -> {
                    if (vps == null) {
                        vps = nal.data
                    }
                }

                NAL_SPS -> {
                    if (sps == null) {
                        sps = nal.data
                    }
                }

                NAL_PPS -> {
                    if (pps == null) {
                        pps = nal.data
                    }
                }
            }
        }

        return ParameterSets(
            vps = vps,
            sps = sps,
            pps = pps
        )
    }

    /*
     * ============================================================
     * START CODE
     * ============================================================
     */

    private data class StartCode(
        val offset: Int,
        val length: Int
    )

    private fun findStartCode(
        data: ByteArray,
        from: Int
    ): StartCode? {

        if (data.size < 3) {
            return null
        }

        var i =
            from.coerceAtLeast(0)

        while (i <= data.size - 3) {

            /*
             * 00 00 00 01
             */
            if (
                i <= data.size - 4 &&
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() &&
                data[i + 3] == 1.toByte()
            ) {

                return StartCode(
                    offset = i,
                    length = 4
                )
            }

            /*
             * 00 00 01
             */
            if (
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte()
            ) {

                return StartCode(
                    offset = i,
                    length = 3
                )
            }

            i++
        }

        return null
    }
}