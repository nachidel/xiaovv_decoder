package fr.nachidel.xiaovv

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsExchange
import java.net.URI
import java.net.URLDecoder
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/** Sessions navigateur et gestion des comptes ; les intégrations gardent leur token API. */
class WebAuthentication(
    val users: UserStore = UserStore(),
    private val now: () -> Long = System::currentTimeMillis
) {
    val dashboard = DashboardStore(File(users.file.absoluteFile.parentFile, "dashboard.properties"))
    private data class Session(val credential: UserCredential, val csrf: String, val secure: Boolean,
                               val created: Long, @Volatile var accessed: Long) {
        val username get() = credential.account.username
    }
    private data class Attempts(var count: Int, val until: Long)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val attempts = mutableMapOf<String, Attempts>()
    private val passwordChecks = Semaphore(2)
    private val random = SecureRandom()

    fun hasSession(exchange: HttpExchange) = session(exchange) != null
    fun account(exchange: HttpExchange): UserAccount? = session(exchange)?.credential?.account
    fun canUseCamera(exchange: HttpExchange, cameraId: String): Boolean =
        session(exchange)?.let { users.account(it.username)?.canUseCamera(cameraId) } ?: false

    /** Retourne true quand la réponse est terminée, false pour laisser passer la route métier. */
    fun handle(exchange: HttpExchange, path: String, legacyAuthorized: () -> Boolean): Boolean {
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.responseHeaders.set("Referrer-Policy", "no-referrer")
        exchange.responseHeaders.set("X-Frame-Options", "DENY")
        val assets = mapOf("/favicon.webp" to ("/favicon.webp" to "image/webp"),
            "/favicon.ico" to ("/favicon.webp" to "image/webp"),
            "/auth.js" to ("/auth.js" to "application/javascript; charset=UTF-8"),
            "/dashboard.js" to ("/dashboard.js" to "application/javascript; charset=UTF-8"),
            "/camera-audio.js" to ("/camera-audio.js" to "application/javascript; charset=UTF-8"),
            "/auth.css" to ("/auth.css" to "text/css; charset=UTF-8"))
        assets[path]?.let { (resource, type) ->
            if (exchange.requestMethod !in listOf("GET", "HEAD")) reply(exchange, 405, "Méthode non autorisée")
            else resource(exchange, resource, type)
            return true
        }
        if (path == "/login") {
            if (exchange.requestMethod != "GET") reply(exchange, 405, "Méthode non autorisée")
            else if (hasSession(exchange)) redirect(exchange, "/")
            else resource(exchange, "/login.html", "text/html; charset=UTF-8")
            return true
        }
        val dashboardExecution = Regex("^/api/dashboard/[a-f0-9-]{36}/execute$").matches(path)
        if (path.startsWith("/api/auth/") || (path.startsWith("/api/dashboard") && !dashboardExecution)) {
            try { authRoute(exchange, path, legacyAuthorized) }
            catch (_: DashboardForbidden) { reply(exchange, 403, "Accès à cet élément non autorisé") }
            catch (_: DashboardMissing) { reply(exchange, 404, "Élément introuvable") }
            catch (e: DashboardConflict) { reply(exchange, 409, e.message!!) }
            catch (e: IllegalArgumentException) { reply(exchange, 400, e.message ?: "Requête invalide") }
            catch (e: IllegalStateException) { reply(exchange, 409, e.message ?: "Opération impossible") }
            catch (_: java.io.IOException) { reply(exchange, 500, "Impossible d'enregistrer les utilisateurs") }
            return true
        }
        if (path.isEmpty() || path == "/live") {
            if (!hasSession(exchange)) { redirect(exchange, "/login"); return true }
        }
        if (path.startsWith("/api/")) {
            // Un token explicite est destiné aux clients API ; pas de cookie implicite/CSRF.
            if (legacyAuthorized()) return false
            val session = session(exchange)
            if (session == null) { reply(exchange, 401, "unauthorized"); return true }
            val account = users.account(session.username)
            if (account == null) { reply(exchange, 401, "unauthorized"); return true }
            val adminRoute = path.startsWith("/api/config/") || path.startsWith("/api/mqtt/servers") || path == "/api/http-action"
            if (adminRoute && account.role != UserRole.ADMIN) { reply(exchange, 403, "Accès réservé aux administrateurs"); return true }
            val cameraId = Regex("^/api/cameras/([^/]+)/").find(path)?.groupValues?.get(1)
            if (cameraId != null && !account.canUseCamera(cameraId)) {
                reply(exchange, 403, "Accès à cette caméra non autorisé")
                return true
            }
            val changesState = exchange.requestMethod !in listOf("GET", "HEAD") ||
                Regex("^/api/cameras/[^/]+/(ptz|light|image)/").containsMatchIn(path)
            if ((!sameOrigin(exchange)) || (changesState && !csrfMatches(exchange, session))) {
                reply(exchange, 403, "Requête non autorisée ; actualiser la page")
                return true
            }
        }
        return false
    }

    private fun authRoute(exchange: HttpExchange, path: String, legacyAuthorized: () -> Boolean) {
        if (!sameOrigin(exchange)) { reply(exchange, 403, "Origine de requête non autorisée"); return }
        if (path == "/api/auth/session" && exchange.requestMethod == "GET") {
            val session = session(exchange)
            val account = session?.let { users.account(it.username) }
            if (session == null || account == null) json(exchange, 200, """{"authenticated":false,"setupRequired":${users.isEmpty()}}""")
            else {
                json(exchange, 200, """{"authenticated":true,"username":"${account.username}","role":"${account.role.value}","csrfToken":"${session.csrf}"}""")
            }
            return
        }
        if (path == "/api/auth/login" || path == "/api/auth/setup") {
            if (exchange.requestMethod != "POST") { reply(exchange, 405, "Méthode non autorisée"); return }
            if (!canAttempt(exchange) || !passwordChecks.tryAcquire()) { tooManyAttempts(exchange); return }
            try {
                val form = form(exchange)
                val username = form["username"] ?: ""
                val password = form["password"] ?: ""
                val credential = if (path.endsWith("/setup")) {
                    if (!users.isEmpty()) { reply(exchange, 409, "Le compte administrateur existe déjà"); return }
                    if (!legacyAuthorized()) { failedAttempt(exchange); reply(exchange, 401, "Jeton d'installation invalide"); return }
                    users.setup(username, password).let { users.credential(it.username) }
                } else users.authenticateCredential(username, password)
                if (credential == null) { failedAttempt(exchange); reply(exchange, 401, "Identifiant ou mot de passe incorrect"); return }
                synchronized(attempts) { attempts.remove(exchange.remoteAddress.address.hostAddress) }
                createSession(exchange, credential)
                json(exchange, 200, """{"success":true}""")
            } finally { passwordChecks.release() }
            return
        }
        val session = session(exchange)
        if (session == null) { reply(exchange, 401, "unauthorized"); return }
        if (path.startsWith("/api/dashboard")) {
            dashboardRoute(exchange, path, session)
            return
        }
        if (path == "/api/auth/logout" && exchange.requestMethod == "POST") {
            if (!csrfMatches(exchange, session)) { reply(exchange, 403, "Requête non autorisée"); return }
            cookieId(exchange)?.let { sessions.remove(fingerprint(it)) }
            exchange.responseHeaders.add("Set-Cookie", "${cookieName(exchange)}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0${if (isSecure(exchange)) "; Secure" else ""}")
            json(exchange, 200, """{"success":true}""")
            return
        }
        if (path == "/api/auth/password" && exchange.requestMethod == "POST") {
            if (!csrfMatches(exchange, session)) { reply(exchange, 403, "Requête non autorisée"); return }
            if (!canAttempt(exchange) || !passwordChecks.tryAcquire()) { tooManyAttempts(exchange); return }
            try {
                val form = form(exchange)
                val verified = users.authenticateCredential(session.username, form["currentPassword"] ?: "")
                if (verified == null) {
                    failedAttempt(exchange); reply(exchange, 401, "Mot de passe actuel incorrect"); return
                }
                val password = form["password"] ?: ""
                require(password.length in 12..128) { "Le mot de passe doit contenir entre 12 et 128 caractères" }
                check(verified.revision == session.credential.revision) { "Compte modifié ; reconnectez-vous" }
                val credential = users.changePassword(verified, password)
                revoke(session.username)
                createSession(exchange, credential)
                json(exchange, 200, """{"success":true}""")
            } finally { passwordChecks.release() }
            return
        }
        if (path.startsWith("/api/auth/users")) {
            if (users.account(session.username)?.role != UserRole.ADMIN) { reply(exchange, 403, "Accès réservé aux administrateurs"); return }
            if (path == "/api/auth/users" && exchange.requestMethod == "GET") {
                val accounts = users.accounts().joinToString(",") {
                    val cameras = it.cameras.sorted().joinToString(",") { camera -> "\"$camera\"" }
                    """{"username":"${it.username}","role":"${it.role.value}","cameras":[$cameras]}"""
                }
                json(exchange, 200, "[$accounts]")
                return
            }
            if (!csrfMatches(exchange, session)) { reply(exchange, 403, "Requête non autorisée"); return }
            if (path == "/api/auth/users" && exchange.requestMethod == "POST") {
                val values = form(exchange)
                val username = values["username"].orEmpty().trim().lowercase(java.util.Locale.ROOT)
                if (users.account(username) == null) dashboard.removeUser(username)
                users.create(values["username"] ?: "", values["password"] ?: "", UserRole.parse(values["role"] ?: "user"), cameraPermissions(values))
                json(exchange, 201, """{"success":true}""")
                return
            }
            if (path.startsWith("/api/auth/users/") && exchange.requestMethod in listOf("POST", "DELETE")) {
                val username = path.removePrefix("/api/auth/users/")
                if (exchange.requestMethod == "DELETE") {
                    users.delete(username)
                    dashboard.removeUser(username)
                }
                else {
                    val values = form(exchange)
                    users.update(username, values["password"], UserRole.parse(values["role"] ?: "user"), cameraPermissions(values))
                }
                revoke(username)
                json(exchange, 200, """{"success":true}""")
                return
            }
        }
        reply(exchange, 405, "Méthode ou route non autorisée")
    }

    private fun session(exchange: HttpExchange): Session? {
        val id = cookieId(exchange) ?: return null
        val key = fingerprint(id)
        val session = sessions[key] ?: return null
        val time = now()
        if (session.secure != isSecure(exchange) || time - session.accessed > 30 * 60_000L ||
            time - session.created > 12 * 60 * 60_000L || !users.isCurrent(session.credential)) {
            sessions.remove(key)
            return null
        }
        session.accessed = time
        return session
    }

    private fun createSession(exchange: HttpExchange, credential: UserCredential) {
        check(users.isCurrent(credential)) { "Compte modifié ; reconnectez-vous" }
        val time = now()
        sessions.entries.removeIf { time - it.value.accessed > 30 * 60_000L || time - it.value.created > 12 * 60 * 60_000L }
        if (sessions.size >= 500) sessions.entries.minByOrNull { it.value.created }?.let { sessions.remove(it.key) }
        cookieId(exchange)?.let { sessions.remove(fingerprint(it)) }
        val id = opaqueToken()
        sessions[fingerprint(id)] = Session(credential, opaqueToken(), isSecure(exchange), time, time)
        exchange.responseHeaders.add("Set-Cookie", "${cookieName(exchange)}=$id; Path=/; HttpOnly; SameSite=Strict${if (isSecure(exchange)) "; Secure" else ""}")
    }

    private fun revoke(username: String) = sessions.entries.removeIf { it.value.username.equals(username, ignoreCase = true) }
    private fun opaqueToken() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    private fun fingerprint(id: String) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(id.toByteArray()))
    private fun isSecure(exchange: HttpExchange) = exchange is HttpsExchange
    private fun cookieName(exchange: HttpExchange) = if (isSecure(exchange)) "__Host-xiaovv_session" else "xiaovv_session"
    private fun cookieId(exchange: HttpExchange): String? = exchange.requestHeaders["Cookie"].orEmpty()
        .flatMap { it.split(';') }.map { it.trim().split('=', limit = 2) }
        .firstOrNull { it.size == 2 && it[0] == cookieName(exchange) && it[1].matches(Regex("[A-Za-z0-9_-]{43}")) }?.get(1)

    private fun csrfMatches(exchange: HttpExchange, session: Session) = MessageDigest.isEqual(
        (exchange.requestHeaders.getFirst("X-CSRF-Token") ?: "").toByteArray(), session.csrf.toByteArray())

    private fun sameOrigin(exchange: HttpExchange): Boolean {
        if (exchange.requestHeaders.getFirst("Sec-Fetch-Site") == "cross-site") return false
        val origin = exchange.requestHeaders.getFirst("Origin") ?: return true
        val host = exchange.requestHeaders.getFirst("Host") ?: return false
        return try {
            val supplied = URI(origin)
            val expected = URI("${if (isSecure(exchange)) "https" else "http"}://$host")
            fun port(uri: URI) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
            supplied.scheme == expected.scheme && supplied.host.equals(expected.host, true) && port(supplied) == port(expected)
        } catch (_: Exception) { false }
    }

    private fun canAttempt(exchange: HttpExchange): Boolean = synchronized(attempts) {
        val time = now()
        attempts.entries.removeIf { it.value.until <= time }
        (attempts[exchange.remoteAddress.address.hostAddress]?.count ?: 0) < 8
    }
    private fun failedAttempt(exchange: HttpExchange) = synchronized(attempts) {
        if (attempts.size > 2048) attempts.entries.minByOrNull { it.value.until }?.let { attempts.remove(it.key) }
        val ip = exchange.remoteAddress.address.hostAddress
        attempts.getOrPut(ip) { Attempts(0, now() + 5 * 60_000L) }.count++
    }
    private fun tooManyAttempts(exchange: HttpExchange) {
        exchange.responseHeaders.set("Retry-After", "300")
        reply(exchange, 429, "Trop de tentatives ; réessayer dans quelques minutes")
    }

    private fun form(exchange: HttpExchange, limit: Int = 8192): Map<String, String> {
        require(exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';') == "application/x-www-form-urlencoded") {
            "Format de formulaire invalide"
        }
        val bytes = exchange.requestBody.readNBytes(limit + 1)
        require(bytes.size <= limit) { "Formulaire trop volumineux" }
        return bytes.toString(Charsets.UTF_8).split('&').associate { part ->
            val pair = part.split('=', limit = 2)
            URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, Charsets.UTF_8)
        }
    }

    private fun dashboardRoute(exchange: HttpExchange, path: String, session: Session) {
        val user = account(exchange) ?: run { reply(exchange, 401, "unauthorized"); return }
        if (path == "/api/dashboard/recipients" && exchange.requestMethod == "GET") {
            json(exchange, 200, users.accounts().joinToString(",", "[", "]") { jsonString(it.username) })
            return
        }
        if (path == "/api/dashboard" && exchange.requestMethod == "GET") {
            val query = exchange.requestURI.rawQuery.orEmpty().split('&').associate {
                val pair = it.split('=', limit = 2)
                URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair.getOrElse(1) { "" }, Charsets.UTF_8)
            }
            val owner = query["owner"]?.ifBlank { null } ?: user.username
            if (owner != user.username && user.role != UserRole.ADMIN) throw DashboardForbidden()
            require(users.account(owner) != null) { "Utilisateur introuvable" }
            val items = dashboard.list(owner).joinToString(",", "[", "]") { it.toJson(user) }
            val layout = dashboard.layout(owner).entries.joinToString(",", "{", "}") { (key, value) -> "${jsonString(key)}:${jsonString(value)}" }
            json(exchange, 200, """{"owner":${jsonString(owner)},"items":$items,"layout":$layout}""")
            return
        }
        if (exchange.requestMethod !in listOf("POST", "DELETE")) { reply(exchange, 405, "Méthode non autorisée"); return }
        if (!csrfMatches(exchange, session)) { reply(exchange, 403, "Requête non autorisée"); return }
        val values = form(exchange, 262144)
        if (path == "/api/dashboard" && exchange.requestMethod == "POST") {
            if (values["scope"] != "common") require(users.account(values["owner"]?.ifBlank { null } ?: user.username) != null) { "Utilisateur introuvable" }
            val item = dashboard.put(user, values)
            json(exchange, 200, item.toJson(user))
            return
        }
        if (path == "/api/dashboard/layout" && exchange.requestMethod == "POST") {
            dashboard.saveLayout(user.username, values)
            json(exchange, 200, """{"success":true}""")
            return
        }
        val match = Regex("^/api/dashboard/([a-f0-9-]{36})(/transfer)?$").matchEntire(path)
        if (match != null) {
            val id = match.groupValues[1]
            if (match.groupValues[2].isEmpty() && exchange.requestMethod == "DELETE") {
                dashboard.delete(user, id, values["revision"]?.toLongOrNull())
                json(exchange, 200, """{"success":true}""")
                return
            }
            if (match.groupValues[2].isNotEmpty() && exchange.requestMethod == "POST") {
                val target = values["target"].orEmpty()
                require(users.account(target) != null) { "Destinataire introuvable" }
                val item = dashboard.transfer(user, id, target, values["mode"].orEmpty(), values["revision"]?.toLongOrNull())
                json(exchange, 200, item.toJson(user))
                return
            }
        }
        reply(exchange, 404, "Route inconnue")
    }

    private fun cameraPermissions(values: Map<String, String>) =
        (values["cameras"] ?: "").split(',').filter { it.isNotBlank() }.toSet()

    private fun redirect(exchange: HttpExchange, path: String) {
        exchange.responseHeaders.set("Location", path)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(302, -1)
    }
    private fun resource(exchange: HttpExchange, path: String, type: String) {
        val bytes = javaClass.getResourceAsStream(path)?.use { it.readAllBytes() } ?: error("Ressource introuvable : $path")
        exchange.responseHeaders.set("Content-Type", type)
        exchange.responseHeaders.set("Cache-Control", "no-cache")
        if (exchange.requestMethod == "HEAD") { exchange.sendResponseHeaders(200, -1); return }
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
    private fun reply(exchange: HttpExchange, status: Int, error: String) {
        val escaped = error.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
        json(exchange, status, """{"success":false,"error":"$escaped"}""")
    }
    private fun json(exchange: HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
