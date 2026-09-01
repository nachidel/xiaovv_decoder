package fr.nachidel.xiaovv.v380

import fr.nachidel.xiaovv.logging.logger
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object V380Auth {

    private val log = logger<V380Auth>()

    /*
     * ============================================================
     * LOGIN MODERNE OBSERVÉ DANS NOTRE PCAP
     * ============================================================
     *
     * Offset   Taille   Description
     *
     * 0        4        Commande = 31167
     * 4        4        unknown1 = 120
     * 8        1        unknown2 = 31
     * 9        4        unknown3 = 10
     * 13       4        Device ID
     * 17       32       Date "yyyy-MM-dd HH:mm:ss"
     * 49       32       Username
     * 81       64       Zone authentification
     *
     * Dans notre capture :
     *
     * zone authentification réellement utilisée :
     *
     * 81..96   = randomKey 16 octets
     * 97..128  = mot de passe chiffré 32 octets
     *
     * le reste est à zéro.
     *
     * Taille totale = 520 octets.
     */

    private const val UNKNOWN_1 = 120
    private const val UNKNOWN_2 = 31
    private const val UNKNOWN_3 = 10

    private const val OFFSET_COMMAND = 0
    private const val OFFSET_UNKNOWN_1 = 4
    private const val OFFSET_UNKNOWN_2 = 8
    private const val OFFSET_UNKNOWN_3 = 9
    private const val OFFSET_DEVICE_ID = 13
    private const val OFFSET_DATE = 17
    private const val OFFSET_USERNAME = 49
    private const val OFFSET_PASSWORD = 81

    private const val DATE_FIELD_SIZE = 32
    private const val USERNAME_FIELD_SIZE = 32
    private const val PASSWORD_FIELD_SIZE = 64

    /*
     * ============================================================
     * AES
     * ============================================================
     */

    /**
     * Clé statique Macrovideo/V380.
     *
     * 16 octets = AES-128.
     */
    private const val STATIC_KEY =
        "macrovideo+*#!^@"

    /**
     * Caractères utilisés pour la clé temporaire.
     */
    private const val RANDOM_CHARSET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                "abcdefghijklmnopqrstuvwxyz" +
                "1234567890" +
                "!@#$%^&*()_+-="

    private const val AES_KEY_SIZE = 16

    private val secureRandom =
        SecureRandom()

    private val dateFormatter =
        DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm:ss"
        )

    /*
     * ============================================================
     * CONSTRUCTION DU LOGIN
     * ============================================================
     */

    fun buildLoginRequest(
        deviceId: String,
        username: String,
        password: String
    ): ByteArray {

        val numericDeviceId =
            deviceId.toLongOrNull()
                ?: throw IllegalArgumentException(
                    "Device ID invalide : $deviceId"
                )

        require(
            numericDeviceId in 0..0xFFFFFFFFL
        ) {
            "Device ID hors plage uint32 : $deviceId"
        }

        return buildLoginRequest(
            deviceId = numericDeviceId,
            username = username,
            password = password
        )
    }

    fun buildLoginRequest(
        deviceId: Long,
        username: String,
        password: String
    ): ByteArray {

        require(
            username.toByteArray(
                Charsets.US_ASCII
            ).size < USERNAME_FIELD_SIZE
        ) {
            "Nom utilisateur trop long"
        }

        require(password.isNotEmpty()) {
            "Le mot de passe ne peut pas être vide"
        }

        val packet =
            ByteArray(
                V380Protocol.LOGIN_REQUEST_SIZE
            )

        /*
         * Commande 31167
         */
        V380Protocol.writeInt32LE(
            packet,
            OFFSET_COMMAND,
            V380Protocol.CMD_LOGIN
        )

        /*
         * Valeurs observées dans notre PCAP.
         */
        V380Protocol.writeInt32LE(
            packet,
            OFFSET_UNKNOWN_1,
            UNKNOWN_1
        )

        packet[OFFSET_UNKNOWN_2] =
            UNKNOWN_2.toByte()

        V380Protocol.writeInt32LE(
            packet,
            OFFSET_UNKNOWN_3,
            UNKNOWN_3
        )

        /*
         * Device ID
         */
        V380Protocol.writeUInt32LE(
            packet,
            OFFSET_DEVICE_ID,
            deviceId
        )

        /*
         * Date locale.
         */
        val date =
            LocalDateTime.now()
                .format(dateFormatter)

        V380Protocol.writeAscii(
            packet,
            OFFSET_DATE,
            DATE_FIELD_SIZE,
            date
        )

        /*
         * Username.
         */
        V380Protocol.writeAscii(
            packet,
            OFFSET_USERNAME,
            USERNAME_FIELD_SIZE,
            username
        )

        /*
         * Bloc authentification :
         *
         * 16 octets randomKey
         * +
         * 32 octets AES
         *
         * = 48 octets pour notre mot de passe actuel.
         */
        val authBlock =
            generatePasswordBlock(
                password
            )

        require(
            authBlock.size <=
                    PASSWORD_FIELD_SIZE
        ) {
            "Bloc d'authentification trop grand : " +
                    "${authBlock.size} octets"
        }

        authBlock.copyInto(
            destination = packet,
            destinationOffset = OFFSET_PASSWORD
        )

        log.debug(
            "Paquet LOGIN construit : " +
                    "deviceId={}, user={}, date={}, auth={} octets",
            deviceId,
            username,
            date,
            authBlock.size
        )

        return packet
    }

    /*
     * ============================================================
     * MOT DE PASSE
     * ============================================================
     *
     * Le PCAP nous permet de confirmer précisément :
     *
     * password
     *
     *   AES-128 ECB PKCS5Padding
     *   key = macrovideo+*#!^@
     *
     *       ↓
     *
     * encryptedPassword
     *
     *   AES-128 ECB PKCS5Padding
     *   key = randomKey
     *
     *       ↓
     *
     * encryptedPassword2
     *
     *
     * paquet final :
     *
     * randomKey + encryptedPassword2
     */

    private fun generatePasswordBlock(
        password: String
    ): ByteArray {

        val passwordBytes =
            password.toByteArray(
                Charsets.UTF_8
            )

        /*
         * Première passe.
         *
         * Avec le mot de passe contenu dans notre PCAP,
         * cette opération produit 16 octets.
         */
        val firstPass =
            aesEncryptPkcs5(
                key = STATIC_KEY.toByteArray(
                    Charsets.US_ASCII
                ),
                data = passwordBytes
            )

        log.debug(
            "AES passe 1 : {} -> {} octets",
            passwordBytes.size,
            firstPass.size
        )

        /*
         * Clé aléatoire utilisée par la seconde passe.
         */
        val randomKey =
            generateRandomKey()

        /*
         * Deuxième passe.
         *
         * Comme firstPass fait exactement 16 octets,
         * PKCS#5/PKCS#7 ajoute un bloc complet :
         *
         * 16 -> 32 octets.
         */
        val secondPass =
            aesEncryptPkcs5(
                key = randomKey,
                data = firstPass
            )

        log.debug(
            "AES passe 2 : {} -> {} octets",
            firstPass.size,
            secondPass.size
        )

        /*
         * Construction finale.
         */
        val result =
            ByteArray(
                randomKey.size +
                        secondPass.size
            )

        randomKey.copyInto(
            destination = result,
            destinationOffset = 0
        )

        secondPass.copyInto(
            destination = result,
            destinationOffset = randomKey.size
        )

        log.debug(
            "Bloc d'authentification généré : {} octets",
            result.size
        )

        /*
         * Ne jamais logger :
         *
         * - password
         * - randomKey
         * - ciphertext
         */

        return result
    }

    /*
     * ============================================================
     * AES-128 ECB + PKCS5Padding
     * ============================================================
     *
     * Java nomme ce padding PKCS5Padding.
     *
     * Pour AES (blocs de 16 octets), son comportement
     * correspond au padding PKCS#7 observé dans le PCAP.
     */

    private fun aesEncryptPkcs5(
        key: ByteArray,
        data: ByteArray
    ): ByteArray {

        require(
            key.size == AES_KEY_SIZE
        ) {
            "La clé AES doit faire exactement 16 octets"
        }

        val cipher =
            Cipher.getInstance(
                "AES/ECB/PKCS5Padding"
            )

        val keySpec =
            SecretKeySpec(
                key,
                "AES"
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            keySpec
        )

        return cipher.doFinal(
            data
        )
    }

    /*
     * ============================================================
     * RANDOM KEY
     * ============================================================
     */

    private fun generateRandomKey(): ByteArray {

        val key =
            ByteArray(
                AES_KEY_SIZE
            )

        for (i in key.indices) {

            val index =
                secureRandom.nextInt(
                    RANDOM_CHARSET.length
                )

            key[i] =
                RANDOM_CHARSET[index]
                    .code
                    .toByte()
        }

        return key
    }

    /*
     * ============================================================
     * RÉPONSE LOGIN
     * ============================================================
     */

    data class LoginResponse(
        val command: Int,
        val result: Int,
        val protocolVersion: Int,
        val authTicket: Int
    ) {

        val success: Boolean
            get() =
                command ==
                        V380Protocol.CMD_LOGIN_RESPONSE &&
                        result == LOGIN_OK
    }

    fun parseLoginResponse(
        packet: ByteArray
    ): LoginResponse {

        require(
            packet.size >=
                    V380Protocol.LOGIN_RESPONSE_SIZE
        ) {
            "Réponse LOGIN trop courte : " +
                    "${packet.size} octets"
        }

        val command =
            V380Protocol.readInt32LE(
                packet,
                0
            )

        val result =
            V380Protocol.readInt32LE(
                packet,
                4
            )

        /*
         * Notre réponse PCAP réussie :
         *
         * offset 12 = 0x1F = 31
         */
        val protocolVersion =
            packet[12].toInt() and 0xFF

        /*
         * Ticket session.
         *
         * Offset 13 dans la réponse LOGIN.
         *
         * Il sera repris dans la commande 301.
         */
        val authTicket =
            V380Protocol.readInt32LE(
                packet,
                13
            )

        val response =
            LoginResponse(
                command = command,
                result = result,
                protocolVersion = protocolVersion,
                authTicket = authTicket
            )

        log.debug(
            "Réponse LOGIN : " +
                    "command={}, result={}, version={}, ticket={}",
            V380Protocol.commandDescription(
                response.command
            ),
            response.result,
            response.protocolVersion,
            response.authTicket
        )

        when (result) {

            LOGIN_OK ->
                log.info("Authentification V380 réussie - protocole v{}", protocolVersion)

            LOGIN_INVALID_USERNAME ->
                log.error("Authentification refusée : utilisateur incorrect")

            LOGIN_INVALID_PASSWORD ->
                log.error("Authentification refusée : mot de passe incorrect")

            LOGIN_INVALID_DEVICE_ID ->
                log.error("Authentification refusée : Device ID incorrect")

            else ->
                log.warn("Réponse d'authentification inconnue : {}", result)
        }

        return response
    }

    /*
     * ============================================================
     * CODES RETOUR
     * ============================================================
     */

    const val LOGIN_OK = 1001

    const val LOGIN_INVALID_USERNAME = 1011

    const val LOGIN_INVALID_PASSWORD = 1012

    const val LOGIN_INVALID_DEVICE_ID = 1018
}