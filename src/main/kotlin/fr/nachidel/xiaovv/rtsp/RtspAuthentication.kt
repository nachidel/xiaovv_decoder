package fr.nachidel.xiaovv.rtsp

import fr.nachidel.xiaovv.UserCredential
import fr.nachidel.xiaovv.UserStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Semaphore

/** Comptes communs au site et à RTSP ; le mot de passe n'est pas gardé dans la session. */
class RtspAuthentication(private val users: UserStore, private val now: () -> Long = System::currentTimeMillis) {
    private data class Attempts(var count: Int, val until: Long)
    private val attempts = mutableMapOf<String, Attempts>()
    private val checks = Semaphore(2)

    internal class Session(val credential: UserCredential, val authorizationHash: ByteArray)

    internal fun authenticate(authorization: String?, ip: String): Session? {
        if (authorization == null || authorization.length > 1024 || !authorization.startsWith("Basic ", true)) return null
        if (!canAttempt(ip) || !checks.tryAcquire()) return null
        try {
            val decoded = try { Base64.getDecoder().decode(authorization.substringAfter(' ').trim()) }
                catch (_: IllegalArgumentException) { failed(ip); return null }
            val separator = decoded.indexOf(':'.code.toByte())
            if (separator < 1) { decoded.fill(0); failed(ip); return null }
            val username = decoded.copyOfRange(0, separator).toString(Charsets.UTF_8)
            val password = decoded.copyOfRange(separator + 1, decoded.size).toString(Charsets.UTF_8)
            decoded.fill(0)
            val credential = users.authenticateCredential(username, password)
            if (credential == null) { failed(ip); return null }
            synchronized(attempts) { attempts.remove(ip) }
            return Session(credential, fingerprint(authorization))
        } finally { checks.release() }
    }

    internal fun isCurrent(session: Session) = users.isCurrent(session.credential)
    internal fun canUseCamera(session: Session, cameraId: String) =
        isCurrent(session) && session.credential.account.canUseCamera(cameraId)
    internal fun matches(session: Session, authorization: String) =
        MessageDigest.isEqual(session.authorizationHash, fingerprint(authorization))

    private fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    private fun canAttempt(ip: String) = synchronized(attempts) {
        attempts.entries.removeIf { it.value.until <= now() }
        (attempts[ip]?.count ?: 0) < 8
    }
    private fun failed(ip: String) = synchronized(attempts) {
        if (attempts.size >= 2048) attempts.entries.minByOrNull { it.value.until }?.let { attempts.remove(it.key) }
        attempts.getOrPut(ip) { Attempts(0, now() + 5 * 60_000L) }.count++
    }
}
