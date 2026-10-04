package fr.nachidel.xiaovv

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.Properties
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class UserRole(val value: String) {
    ADMIN("admin"), USER("user");

    companion object {
        fun parse(value: String) = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Rôle utilisateur invalide")
    }
}

data class UserAccount(val username: String, val role: UserRole, val cameras: Set<String> = emptySet()) {
    fun canUseCamera(cameraId: String) = role == UserRole.ADMIN || cameraId in cameras
}

internal data class UserCredential(val account: UserAccount, val revision: Long)

/** Fichier indépendant de la configuration déployée ; aucun mot de passe en clair. */
class UserStore(val file: File = defaultFile()) {
    private data class Record(val account: UserAccount, val salt: ByteArray, val hash: ByteArray, val iterations: Int, val revision: Long)
    private val random = SecureRandom()
    private var revision = 0L
    private var users = load()
    private val dummySalt = ByteArray(16).also(random::nextBytes)

    @Synchronized fun isEmpty() = users.isEmpty()
    @Synchronized fun accounts() = users.values.map { it.account }.sortedBy { it.username }
    @Synchronized fun account(username: String) = users[normalize(username)]?.account

    fun authenticate(username: String, password: String): UserAccount? = authenticateCredential(username, password)?.account

    @Synchronized internal fun credential(username: String): UserCredential? =
        users[normalize(username)]?.let { UserCredential(it.account, it.revision) }

    @Synchronized internal fun isCurrent(credential: UserCredential): Boolean =
        users[credential.account.username]?.revision == credential.revision

    @Synchronized internal fun changePassword(credential: UserCredential, password: String): UserCredential {
        check(isCurrent(credential)) { "Compte modifié ; reconnectez-vous" }
        update(credential.account.username, password, credential.account.role)
        return credential(credential.account.username)!!
    }

    internal fun authenticateCredential(username: String, password: String): UserCredential? {
        if (username.length > 32 || password.length > 128) return null
        val name = normalize(username)
        val record = synchronized(this) { users[name] }
        val computed = hash(password, record?.salt ?: dummySalt, record?.iterations ?: ITERATIONS)
        return try {
            synchronized(this) {
                if (record != null && users[name] === record && MessageDigest.isEqual(record.hash, computed))
                    UserCredential(record.account, record.revision) else null
            }
        } finally { computed.fill(0) }
    }

    @Synchronized fun setup(username: String, password: String): UserAccount {
        check(users.isEmpty()) { "Le compte administrateur existe déjà" }
        return create(username, password, UserRole.ADMIN)
    }

    @Synchronized fun create(username: String, password: String, role: UserRole, cameras: Set<String> = emptySet()): UserAccount {
        val name = validUsername(username)
        require(name !in users) { "Ce nom d'utilisateur existe déjà" }
        require(users.size < 200) { "Nombre maximal d'utilisateurs atteint" }
        val record = record(name, password, role, cameras)
        save(users + (name to record))
        return record.account
    }

    @Synchronized fun update(username: String, password: String?, role: UserRole, cameras: Set<String>? = null): UserAccount {
        val name = validUsername(username)
        val old = users[name] ?: throw IllegalArgumentException("Utilisateur introuvable")
        if (old.account.role == UserRole.ADMIN && role != UserRole.ADMIN) requireOtherAdmin(name)
        val permissions = validCameras(cameras ?: old.account.cameras)
        val updated = if (password.isNullOrEmpty()) old.copy(account = UserAccount(name, role, permissions), revision = ++revision) else record(name, password, role, permissions)
        save(users + (name to updated))
        return updated.account
    }

    @Synchronized fun delete(username: String) {
        val name = validUsername(username)
        val old = users[name] ?: throw IllegalArgumentException("Utilisateur introuvable")
        if (old.account.role == UserRole.ADMIN) requireOtherAdmin(name)
        save(users - name)
    }

    private fun requireOtherAdmin(name: String) {
        require(users.values.any { it.account.username != name && it.account.role == UserRole.ADMIN }) {
            "Il faut conserver au moins un administrateur"
        }
    }

    private fun record(name: String, password: String, role: UserRole, cameras: Set<String>): Record {
        require(password.length in 12..128) { "Le mot de passe doit contenir entre 12 et 128 caractères" }
        val salt = ByteArray(16).also(random::nextBytes)
        return Record(UserAccount(name, role, validCameras(cameras)), salt, hash(password, salt, ITERATIONS), ITERATIONS, ++revision)
    }

    private fun hash(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }

    private fun load(): Map<String, Record> {
        if (!file.exists()) return emptyMap()
        val props = Properties().apply { file.inputStream().use { load(it) } }
        require(props.getProperty("format") == "1") { "Format du fichier utilisateurs invalide" }
        val records = props.stringPropertyNames().filter { it.startsWith("user.") }.associate { key ->
            val name = validUsername(key.removePrefix("user."))
            val fields = props.getProperty(key).split(':')
            require(fields.size == 4) { "Compte utilisateur invalide" }
            val iterations = fields[1].toInt()
            val salt = Base64.getDecoder().decode(fields[2])
            val hash = Base64.getDecoder().decode(fields[3])
            require(iterations in ITERATIONS..2_000_000 && salt.size >= 16 && hash.size == 32) { "Hachage utilisateur invalide" }
            val cameras = props.getProperty("access.$name", "").split(',').filter { it.isNotEmpty() }.toSet()
            name to Record(UserAccount(name, UserRole.parse(fields[0]), validCameras(cameras)), salt, hash, iterations, ++revision)
        }
        require(records.isNotEmpty() && records.values.any { it.account.role == UserRole.ADMIN }) {
            "Le fichier utilisateurs doit contenir un administrateur"
        }
        return records
    }

    private fun save(updated: Map<String, Record>) {
        val target = file.absoluteFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".users-", ".tmp")
        try {
            val props = Properties().apply {
                setProperty("format", "1")
                updated.toSortedMap().forEach { (name, record) ->
                    setProperty("access.$name", record.account.cameras.sorted().joinToString(","))
                    setProperty("user.$name", listOf(record.account.role.value, record.iterations.toString(),
                        Base64.getEncoder().encodeToString(record.salt), Base64.getEncoder().encodeToString(record.hash)).joinToString(":"))
                }
            }
            Files.newOutputStream(temporary).use { props.store(it, "Xiaovv users - PBKDF2-HMAC-SHA256") }
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            users = updated
        } finally { Files.deleteIfExists(temporary) }
    }

    companion object {
        private const val ITERATIONS = 600_000
        private fun validCameras(cameras: Set<String>): Set<String> {
            require(cameras.size <= 200 && cameras.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }) { "Liste de caméras invalide" }
            return cameras.toSortedSet().toSet()
        }
        private fun normalize(name: String) = name.trim().lowercase(Locale.ROOT)
        private fun validUsername(username: String): String = normalize(username).also {
            require(it.matches(Regex("[a-z0-9][a-z0-9_.-]{2,31}"))) {
                "Nom d'utilisateur : 3 à 32 lettres, chiffres, points, tirets ou underscores"
            }
        }

        fun defaultFile(): File {
            val explicit = System.getProperty("xiaovv.users.file") ?: System.getenv("XIAOVV_USERS_FILE")
            if (!explicit.isNullOrBlank()) return File(explicit)
            val config = System.getProperty("xiaovv.config") ?: System.getenv("XIAOVV_CONFIG")
            return if (config.isNullOrBlank()) File("config/users.properties") else File(File(config).absoluteFile.parentFile, "users.properties")
        }
    }
}
