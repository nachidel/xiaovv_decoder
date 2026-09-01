package fr.nachidel.xiaovv.v380

import fr.nachidel.xiaovv.logging.logger
import java.io.EOFException
import java.io.InputStream

class V380MediaParser {

    private val log = logger<V380MediaParser>()

    companion object {

        /**
         * Header d'un fragment V380.
         *
         * 0       1 byte    magic = 0x7F
         * 1       1 byte    type
         * 2       1 byte    sequence / frame id
         * 3       2 bytes   nombre total de fragments
         * 5       2 bytes   numéro du fragment
         * 7       2 bytes   longueur payload
         * 9       3 bytes   inconnu / réservé
         */
        const val HEADER_SIZE = 12

        const val MAGIC = 0x7F

        /**
         * Taille maximale observée dans notre PCAP.
         *
         * Les fragments vidéo font au maximum
         * 500 octets de payload.
         */
        const val MAX_FRAGMENT_PAYLOAD = 500

        /*
         * Types observés sur notre Xiaovv.
         */
        const val TYPE_VIDEO_28 = 0x28
        const val TYPE_VIDEO_29 = 0x29

        /**
         * Autre type observé dans le flux.
         *
         * On ne lui attribue pas encore de signification.
         */
        const val TYPE_5B = 0x5B
    }

    /*
     * ============================================================
     * FRAGMENT
     * ============================================================
     */

    data class FragmentHeader(
        val type: Int,
        val sequence: Int,
        val totalFragments: Int,
        val fragmentIndex: Int,
        val payloadLength: Int
    )

    data class Fragment(
        val header: FragmentHeader,
        val payload: ByteArray
    )

    /*
     * ============================================================
     * FRAME RECONSTITUÉE
     * ============================================================
     */

    data class MediaFrame(
        val type: Int,
        val sequence: Int,
        val fragmentCount: Int,
        val data: ByteArray
    ) {

        val isVideo: Boolean
            get() =
                type == TYPE_VIDEO_28 ||
                        type == TYPE_VIDEO_29

        /**
         * Sur les trames vidéo récentes observées,
         * les 16 premiers octets du frame reconstitué
         * constituent un header interne.
         *
         * On ne le décode pas encore ici.
         */
        fun payloadAfterVideoHeader(): ByteArray {

            require(isVideo) {
                "Cette frame n'est pas une frame vidéo"
            }

            require(data.size > 16) {
                "Frame vidéo trop courte : ${data.size} octets"
            }

            return data.copyOfRange(
                16,
                data.size
            )
        }
    }

    /*
     * ============================================================
     * ASSEMBLAGE
     * ============================================================
     */

    private data class FrameKey(
        val type: Int,
        val sequence: Int
    )

    private data class Assembly(
        val totalFragments: Int,
        val fragments: Array<ByteArray?>,
        var receivedFragments: Int = 0
    )

    private val assemblies =
        mutableMapOf<FrameKey, Assembly>()

    /*
     * ============================================================
     * LECTURE D'UN FRAGMENT
     * ============================================================
     */

    fun readFragment(
        input: InputStream
    ): Fragment {

        /*
         * Important :
         *
         * TCP ne respecte pas les limites des paquets.
         *
         * On lit donc EXACTEMENT :
         *
         * 12 octets de header
         * puis
         * payloadLength octets.
         */

        val headerBytes =
            readExact(
                input,
                HEADER_SIZE
            )

        /*
         * Synchronisation du protocole.
         */
        val magic =
            headerBytes[0].toInt() and 0xFF

        if (magic != MAGIC) {

            throw IllegalStateException(
                "Header média invalide : " +
                        "magic=0x${magic.toString(16).uppercase()} " +
                        "au lieu de 0x7F. " +
                        "Header=${V380Protocol.hex(headerBytes)}"
            )
        }

        val type =
            headerBytes[1].toInt() and 0xFF

        val sequence =
            headerBytes[2].toInt() and 0xFF

        val totalFragments =
            V380Protocol.readUInt16LE(
                headerBytes,
                3
            )

        val fragmentIndex =
            V380Protocol.readUInt16LE(
                headerBytes,
                5
            )

        val payloadLength =
            V380Protocol.readUInt16LE(
                headerBytes,
                7
            )

        /*
         * Contrôles de cohérence.
         */
        require(totalFragments > 0) {
            "Nombre de fragments invalide : $totalFragments"
        }

        require(fragmentIndex < totalFragments) {
            "Index de fragment invalide : " +
                    "$fragmentIndex/$totalFragments"
        }

        require(
            payloadLength in 0..MAX_FRAGMENT_PAYLOAD
        ) {
            "Taille de fragment invalide : " +
                    "$payloadLength octets"
        }

        val payload =
            if (payloadLength == 0) {

                ByteArray(0)

            } else {

                readExact(
                    input,
                    payloadLength
                )
            }

        val header =
            FragmentHeader(
                type = type,
                sequence = sequence,
                totalFragments = totalFragments,
                fragmentIndex = fragmentIndex,
                payloadLength = payloadLength
            )

        log.trace(
            "MEDIA fragment : " +
                    "type=0x{}, seq={}, fragment={}/{}, payload={} octets",
            type
                .toString(16)
                .uppercase()
                .padStart(2, '0'),
            sequence,
            fragmentIndex + 1,
            totalFragments,
            payloadLength
        )

        return Fragment(
            header = header,
            payload = payload
        )
    }

    /*
     * ============================================================
     * ASSEMBLAGE D'UNE FRAME
     * ============================================================
     */

    fun accept(
        fragment: Fragment
    ): MediaFrame? {

        val header =
            fragment.header

        val key =
            FrameKey(
                type = header.type,
                sequence = header.sequence
            )

        /*
         * Si on reçoit un fragment 0 alors qu'une ancienne
         * assembly existe avec le même identifiant,
         * on repart proprement.
         *
         * sequence est sur un octet et finira donc
         * forcément par revenir à la même valeur.
         */
        if (
            header.fragmentIndex == 0 &&
            assemblies.containsKey(key)
        ) {

            log.warn(
                "MEDIA remplacement d'une frame incomplète : " +
                        "type=0x{}, seq={}",
                header.type
                    .toString(16)
                    .uppercase()
                    .padStart(2, '0'),
                header.sequence
            )

            assemblies.remove(key)
        }

        val assembly =
            assemblies.getOrPut(key) {

                log.debug(
                    "MEDIA nouvelle frame : " +
                            "type=0x{}, seq={}, fragments={}",
                    header.type
                        .toString(16)
                        .uppercase()
                        .padStart(2, '0'),
                    header.sequence,
                    header.totalFragments
                )

                Assembly(
                    totalFragments =
                        header.totalFragments,

                    fragments =
                        arrayOfNulls(
                            header.totalFragments
                        )
                )
            }

        /*
         * Tous les fragments d'une même frame doivent
         * annoncer le même nombre total.
         */
        if (
            assembly.totalFragments !=
            header.totalFragments
        ) {

            assemblies.remove(key)

            throw IllegalStateException(
                "Nombre de fragments incohérent pour " +
                        "type=0x${header.type.toString(16)}, " +
                        "seq=${header.sequence} : " +
                        "${assembly.totalFragments} -> " +
                        "${header.totalFragments}"
            )
        }

        /*
         * Ne compte pas deux fois un fragment retransmis.
         */
        if (
            assembly.fragments[
                header.fragmentIndex
            ] == null
        ) {

            assembly.fragments[
                header.fragmentIndex
            ] =
                fragment.payload

            assembly.receivedFragments++
        } else {

            log.debug(
                "MEDIA fragment dupliqué ignoré : " +
                        "type=0x{}, seq={}, fragment={}",
                header.type
                    .toString(16)
                    .uppercase()
                    .padStart(2, '0'),
                header.sequence,
                header.fragmentIndex
            )
        }

        /*
         * Frame pas encore complète.
         */
        if (
            assembly.receivedFragments <
            assembly.totalFragments
        ) {

            return null
        }

        /*
         * Calcul de la taille totale.
         */
        var totalSize = 0

        for (part in assembly.fragments) {

            if (part == null) {

                /*
                 * Ne devrait pas arriver puisque
                 * receivedFragments == totalFragments.
                 */
                assemblies.remove(key)

                throw IllegalStateException(
                    "Frame marquée complète mais fragment absent"
                )
            }

            totalSize += part.size
        }

        /*
         * Reconstruction.
         */
        val data =
            ByteArray(totalSize)

        var offset = 0

        for (part in assembly.fragments) {

            val current =
                part
                    ?: throw IllegalStateException(
                        "Fragment absent"
                    )

            current.copyInto(
                destination = data,
                destinationOffset = offset
            )

            offset += current.size
        }

        assemblies.remove(key)

        val frame =
            MediaFrame(
                type = header.type,
                sequence = header.sequence,
                fragmentCount =
                    assembly.totalFragments,
                data = data
            )

        log.debug(
            "MEDIA frame complète : " +
                    "type=0x{}, seq={}, fragments={}, taille={} octets",
            frame.type
                .toString(16)
                .uppercase()
                .padStart(2, '0'),
            frame.sequence,
            frame.fragmentCount,
            frame.data.size
        )

        if (frame.isVideo) {

            log.trace(
                "VIDEO frame reçue : " +
                        "type=0x{}, {} fragments, {} octets",
                frame.type
                    .toString(16)
                    .uppercase()
                    .padStart(2, '0'),
                frame.fragmentCount,
                frame.data.size
            )
        }

        return frame
    }

    /*
     * ============================================================
     * LECTURE JUSQU'À UNE FRAME COMPLÈTE
     * ============================================================
     */

    fun readNextFrame(
        input: InputStream
    ): MediaFrame {

        while (true) {

            val fragment =
                readFragment(
                    input
                )

            val frame =
                accept(
                    fragment
                )

            if (frame != null) {
                return frame
            }
        }
    }

    /*
     * ============================================================
     * EXACT READ
     * ============================================================
     */

    private fun readExact(
        input: InputStream,
        size: Int
    ): ByteArray {

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

            if (count < 0) {

                throw EOFException(
                    "Connexion fermée pendant la lecture média " +
                            "($offset/$size octets reçus)"
                )
            }

            offset += count
        }

        return buffer
    }
}