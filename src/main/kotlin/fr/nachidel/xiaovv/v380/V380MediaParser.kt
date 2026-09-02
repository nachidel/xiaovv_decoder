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

        /**
         * Garde-fou contre un faux header trouvé pendant une
         * resynchronisation.
         *
         * 4096 fragments de 500 octets permettent déjà une frame
         * théorique d'environ 2 Mo, très au-dessus de ce que nous
         * observons actuellement.
         */
        const val MAX_TOTAL_FRAGMENTS = 4096

        /**
         * Nombre maximal d'octets que l'on accepte de parcourir
         * pour retrouver un header V380 cohérent.
         *
         * Si aucune synchronisation n'est retrouvée dans cette
         * fenêtre, V380Stream abandonnera cette connexion et le
         * CameraSupervisor en ouvrira une nouvelle.
         */
        const val MAX_RESYNC_BYTES = 64 * 1024

        /**
         * Protection mémoire en cas de flux fortement corrompu.
         */
        const val MAX_PENDING_ASSEMBLIES = 512

        /*
         * Types observés sur notre Xiaovv.
         */
        const val TYPE_VIDEO_28 = 0x28
        const val TYPE_VIDEO_29 = 0x29

        /**
         * Type audio observé dans la capture de notre Xiaovv.
         *
         * Après déchiffrement, la charge utile contient une frame
         * AAC LC / ADTS à 16 kHz mono.
         */
        const val TYPE_AUDIO_18 = 0x18

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

        val isAudio: Boolean
            get() =
                type == TYPE_AUDIO_18

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
         * TCP ne respecte pas les limites des paquets.
         *
         * On lit normalement exactement 12 octets de header.
         * Si le flux est décalé, readSynchronizedHeader() fait
         * glisser une fenêtre octet par octet jusqu'au prochain
         * header V380 cohérent.
         */
        val headerBytes =
            readSynchronizedHeader(
                input
            )

        val header =
            decodeHeader(
                headerBytes
            )

        val payload =
            if (header.payloadLength == 0) {

                ByteArray(0)

            } else {

                readExact(
                    input,
                    header.payloadLength
                )
            }

        log.trace(
            "MEDIA fragment : " +
                    "type=0x{}, seq={}, fragment={}/{}, payload={} octets",
            header.type
                .toString(16)
                .uppercase()
                .padStart(2, '0'),
            header.sequence,
            header.fragmentIndex + 1,
            header.totalFragments,
            header.payloadLength
        )

        return Fragment(
            header = header,
            payload = payload
        )
    }

    /*
     * ============================================================
     * SYNCHRONISATION DU FLUX
     * ============================================================
     */

    private fun readSynchronizedHeader(
        input: InputStream
    ): ByteArray {

        val window =
            readExact(
                input,
                HEADER_SIZE
            )

        if (isPlausibleHeader(window)) {
            return window
        }

        val firstMagic =
            window[0].toInt() and 0xFF

        log.warn(
            "MEDIA désynchronisé : header invalide " +
                    "(magic=0x{}, header={}). " +
                    "Recherche du prochain header V380...",
            firstMagic
                .toString(16)
                .uppercase()
                .padStart(2, '0'),
            V380Protocol.hex(window)
        )

        /*
         * Tout assemblage en cours est désormais suspect :
         * un ou plusieurs octets / fragments ont été perdus.
         */
        clearAssemblies()

        var skippedBytes = 0

        while (skippedBytes < MAX_RESYNC_BYTES) {

            System.arraycopy(
                window,
                1,
                window,
                0,
                HEADER_SIZE - 1
            )

            val next =
                input.read()

            if (next < 0) {

                throw EOFException(
                    "Connexion fermée pendant la resynchronisation média " +
                            "après $skippedBytes octets ignorés"
                )
            }

            window[HEADER_SIZE - 1] =
                next.toByte()

            skippedBytes++

            if (isPlausibleHeader(window)) {

                val header =
                    decodeHeader(
                        window
                    )

                log.warn(
                    "MEDIA resynchronisé après {} octet(s) ignoré(s) : " +
                            "type=0x{}, seq={}, fragment={}/{}, payload={} octets",
                    skippedBytes,
                    header.type
                        .toString(16)
                        .uppercase()
                        .padStart(2, '0'),
                    header.sequence,
                    header.fragmentIndex + 1,
                    header.totalFragments,
                    header.payloadLength
                )

                return window.copyOf()
            }
        }

        throw IllegalStateException(
            "Impossible de resynchroniser le flux média V380 " +
                    "après $MAX_RESYNC_BYTES octets"
        )
    }

    private fun isPlausibleHeader(
        bytes: ByteArray
    ): Boolean {

        if (bytes.size != HEADER_SIZE) {
            return false
        }

        val magic =
            bytes[0].toInt() and 0xFF

        if (magic != MAGIC) {
            return false
        }

        val totalFragments =
            V380Protocol.readUInt16LE(
                bytes,
                3
            )

        if (
            totalFragments !in
            1..MAX_TOTAL_FRAGMENTS
        ) {
            return false
        }

        val fragmentIndex =
            V380Protocol.readUInt16LE(
                bytes,
                5
            )

        if (
            fragmentIndex !in
            0 until totalFragments
        ) {
            return false
        }

        val payloadLength =
            V380Protocol.readUInt16LE(
                bytes,
                7
            )

        if (
            payloadLength !in
            0..MAX_FRAGMENT_PAYLOAD
        ) {
            return false
        }

        return true
    }

    private fun decodeHeader(
        bytes: ByteArray
    ): FragmentHeader {

        require(isPlausibleHeader(bytes)) {
            "Header média V380 incohérent : ${V380Protocol.hex(bytes)}"
        }

        return FragmentHeader(
            type =
                bytes[1].toInt() and 0xFF,

            sequence =
                bytes[2].toInt() and 0xFF,

            totalFragments =
                V380Protocol.readUInt16LE(
                    bytes,
                    3
                ),

            fragmentIndex =
                V380Protocol.readUInt16LE(
                    bytes,
                    5
                ),

            payloadLength =
                V380Protocol.readUInt16LE(
                    bytes,
                    7
                )
        )
    }

    private fun clearAssemblies() {

        if (assemblies.isNotEmpty()) {

            log.debug(
                "MEDIA abandon de {} assemblage(s) incomplet(s)",
                assemblies.size
            )

            assemblies.clear()
        }
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

        if (
            assemblies.size >=
            MAX_PENDING_ASSEMBLIES &&
            !assemblies.containsKey(key)
        ) {

            log.warn(
                "MEDIA trop d'assemblages incomplets ({}), remise à zéro",
                assemblies.size
            )

            assemblies.clear()
        }

        var assembly =
            assemblies[key]

        if (assembly == null) {

            assembly =
                createAssembly(
                    header
                )

            assemblies[key] =
                assembly
        }

        /*
         * Tous les fragments d'une même frame doivent
         * annoncer le même nombre total.
         *
         * Une incohérence locale ne justifie plus de couper la
         * connexion entière : on abandonne uniquement cette frame.
         */
        if (
            assembly.totalFragments !=
            header.totalFragments
        ) {

            log.warn(
                "MEDIA frame incohérente abandonnée : " +
                        "type=0x{}, seq={}, fragments {} -> {}",
                header.type
                    .toString(16)
                    .uppercase()
                    .padStart(2, '0'),
                header.sequence,
                assembly.totalFragments,
                header.totalFragments
            )

            assemblies.remove(key)

            return null
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

                assemblies.remove(key)

                log.warn(
                    "MEDIA frame complète incohérente abandonnée : " +
                            "fragment absent, type=0x{}, seq={}",
                    header.type
                        .toString(16)
                        .uppercase()
                        .padStart(2, '0'),
                    header.sequence
                )

                return null
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
                    ?: return null

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

        } else if (frame.isAudio) {

            log.trace(
                "AUDIO frame reçue : " +
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

    private fun createAssembly(
        header: FragmentHeader
    ): Assembly {

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

        return Assembly(
            totalFragments =
                header.totalFragments,

            fragments =
                arrayOfNulls(
                    header.totalFragments
                )
        )
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
