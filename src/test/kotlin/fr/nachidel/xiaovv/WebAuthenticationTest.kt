package fr.nachidel.xiaovv

import fr.nachidel.xiaovv.camera.CameraApiServer
import fr.nachidel.xiaovv.rtsp.RtspServer
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.*

class WebAuthenticationTest {
    private class Fixture : Closeable {
        val directory = Files.createTempDirectory("xiaovv-auth-test-").toFile()
        val users = UserStore(File(directory, "users.properties"))
        var time = System.currentTimeMillis()
        val authentication = WebAuthentication(users) { time }
        private val previous = listOf("xiaovv.config", "xiaovv.mqtt.config").associateWith(System::getProperty)
        private val rtsp = RtspServer("127.0.0.1", 0)
        private val runtime = CameraRuntimeManager(rtsp)
        private val port = ServerSocket(0).use { it.localPort }
        private val cast = CastManager(0, "127.0.0.1", port)
        private val api: CameraApiServer
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        init {
            File(directory, "application.properties").apply { writeText("camera.salon.enabled=true\ncamera.entree.enabled=true\n")
                System.setProperty("xiaovv.config", absolutePath) }
            File(directory, "mqtt.properties").apply { writeText("mqtt.ids=\n")
                System.setProperty("xiaovv.mqtt.config", absolutePath) }
            runtime.initialize(listOf("salon", "entree").map {
                CameraConfig(it, true, "stream-$it", "127.0.0.1", 8800, "fixture-device", "fixture", "fixture-camera-password", CameraResolution.HIGH, 1000)
            })
            api = CameraApiServer(runtime, cast, "127.0.0.1", port, TOKEN, 0, webAuthentication = authentication)
            api.start()
        }
        fun request(path: String, method: String = "GET", values: Map<String, String> = emptyMap(), cookie: String? = null,
                    csrf: String? = null, token: String? = null, origin: String? = null): HttpResponse<String> {
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(10))
            cookie?.let { builder.header("Cookie", it) }
            csrf?.let { builder.header("X-CSRF-Token", it) }
            token?.let { builder.header("X-API-Token", it) }
            origin?.let { builder.header("Origin", it) }
            if (method != "GET") {
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                val body = values.entries.joinToString("&") { (key, value) ->
                    URLEncoder.encode(key, Charsets.UTF_8) + "=" + URLEncoder.encode(value, Charsets.UTF_8)
                }
                builder.method(method, HttpRequest.BodyPublishers.ofString(body))
            }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        }
        fun login(username: String, password: String = PASSWORD): String {
            val response = request("/api/auth/login", "POST", mapOf("username" to username, "password" to password))
            assertEquals(200, response.statusCode(), response.body())
            return response.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
        }
        fun csrf(cookie: String): String = Regex("\"csrfToken\":\"([^\"]+)\"")
            .find(request("/api/auth/session", cookie = cookie).body())!!.groupValues[1]
        override fun close() {
            api.close(); runtime.close(); cast.close(); rtsp.close(); client.close()
            previous.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        }
    }

    @Test fun `installation et connexion protegent le dashboard et laissent le token aux integrations`() {
        Fixture().use { f ->
            assertEquals(302, f.request("/").statusCode())
            assertEquals("/login", f.request("/live").headers().firstValue("Location").orElseThrow())
            assertEquals(200, f.request("/login").statusCode())
            assertEquals("image/webp", f.request("/favicon.webp").headers().firstValue("Content-Type").orElseThrow())
            assertTrue(f.request("/api/auth/session").body().contains("\"setupRequired\":true"))
            assertEquals(401, f.request("/api/auth/setup", "POST", mapOf("username" to "admin", "password" to PASSWORD)).statusCode())
            assertTrue(f.users.isEmpty())
            val setup = f.request("/api/auth/setup", "POST", mapOf("username" to "admin", "password" to PASSWORD), token = TOKEN)
            assertEquals(200, setup.statusCode())
            val header = setup.headers().firstValue("Set-Cookie").orElseThrow()
            assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("SameSite=Strict"))
            val cookie = header.substringBefore(';')
            assertEquals(200, f.request("/", cookie = cookie).statusCode())
            assertEquals(401, f.request("/api/cameras").statusCode())
            assertEquals(200, f.request("/api/cameras", token = TOKEN).statusCode())
            assertFalse(f.request("/auth.js").body().contains("prompt("))
            assertEquals(409, f.request("/api/auth/setup", "POST", mapOf("username" to "other", "password" to PASSWORD), token = TOKEN).statusCode())
            assertEquals(403, f.request("/api/auth/logout", "POST", cookie = cookie).statusCode())
            assertEquals(200, f.request("/api/auth/logout", "POST", cookie = cookie, csrf = f.csrf(cookie)).statusCode())
            assertEquals(401, f.request("/api/cameras", cookie = cookie).statusCode())
            assertEquals(401, f.request("/api/auth/login", "POST", mapOf("username" to "admin", "password" to "incorrect")).statusCode())
        }
    }

    @Test fun `droits par camera appliques au catalogue et a toutes les routes directes`() {
        Fixture().use { f ->
            f.users.setup("admin", PASSWORD)
            val admin = f.login("admin")
            val values = mapOf("username" to "alice", "password" to PASSWORD, "role" to "user", "cameras" to "salon")
            assertEquals(403, f.request("/api/auth/users", "POST", values, cookie = admin).statusCode())
            assertEquals(403, f.request("/api/auth/users", "POST", values, cookie = admin, csrf = f.csrf(admin), origin = "https://foreign.example").statusCode())
            assertEquals(201, f.request("/api/auth/users", "POST", values, cookie = admin, csrf = f.csrf(admin)).statusCode())
            val alice = f.login("alice")
            val list = f.request("/api/cameras", cookie = alice)
            assertEquals(200, list.statusCode())
            assertTrue(Regex("\"id\"\\s*:\\s*\"salon\"").containsMatchIn(list.body()))
            assertFalse(Regex("\"id\"\\s*:\\s*\"entree\"").containsMatchIn(list.body()))
            assertEquals(200, f.request("/api/cameras/salon/status", cookie = alice).statusCode())
            for (endpoint in listOf("status", "snapshot", "live.mjpeg", "live.mp4", "audio.mp3", "ptz/up", "light/on", "image/flip", "talkback/status")) {
                assertEquals(403, f.request("/api/cameras/entree/$endpoint", cookie = alice, csrf = f.csrf(alice)).statusCode(), endpoint)
            }
            assertEquals(403, f.request("/api/cameras//entree/status", cookie = alice).statusCode())
            assertEquals(403, f.request("/api//cameras/salon/ptz/up", cookie = alice).statusCode())
            for (path in listOf("/api/auth/users", "/api/config/cameras", "/api/mqtt/servers")) {
                assertEquals(403, f.request(path, cookie = alice).statusCode(), path)
            }
            for (action in listOf("start", "stop")) {
                assertEquals(403, f.request("/api/cast/$action", "POST", mapOf("cameraId" to "entree", "deviceAddress" to "127.0.0.1"),
                    cookie = alice, csrf = f.csrf(alice)).statusCode())
            }
            assertEquals(200, f.request("/api/cameras/entree/status", cookie = admin).statusCode())
            assertEquals(200, f.request("/api/auth/users/alice", "POST", mapOf("role" to "user", "cameras" to "entree"), cookie = admin, csrf = f.csrf(admin)).statusCode())
            assertEquals(401, f.request("/api/cameras", cookie = alice).statusCode())
            val reconnected = f.login("alice")
            assertEquals(403, f.request("/api/cameras/salon/status", cookie = reconnected).statusCode())
            assertEquals(200, f.request("/api/cameras/entree/status", cookie = reconnected).statusCode())
            assertEquals(setOf("entree"), UserStore(f.users.file).account("alice")!!.cameras)
            assertEquals(400, f.request("/api/auth/users/admin", "DELETE", cookie = admin, csrf = f.csrf(admin)).statusCode())
            assertEquals(200, f.request("/api/auth/users/alice", "DELETE", cookie = admin, csrf = f.csrf(admin)).statusCode())
            assertEquals(401, f.request("/api/cameras", cookie = reconnected).statusCode())
        }
    }

    @Test fun `mot de passe expiration et limitation des tentatives`() {
        Fixture().use { f ->
            f.users.setup("admin", PASSWORD)
            f.users.create("alice", PASSWORD, UserRole.USER, setOf("salon"))
            val first = f.login("alice")
            val second = f.login("alice")
            val change = f.request("/api/auth/password", "POST", mapOf("currentPassword" to PASSWORD, "password" to "un nouveau mot de passe"), cookie = first, csrf = f.csrf(first))
            assertEquals(200, change.statusCode())
            assertEquals(401, f.request("/api/cameras", cookie = second).statusCode())
            assertEquals(401, f.request("/api/cameras", cookie = first).statusCode())
            val replacement = change.headers().firstValue("Set-Cookie").orElseThrow().substringBefore(';')
            assertEquals(200, f.request("/api/cameras", cookie = replacement).statusCode())
            assertEquals(setOf("salon"), f.users.account("alice")!!.cameras)
            f.time += 31 * 60_000L
            assertEquals(401, f.request("/api/cameras", cookie = replacement).statusCode())
            for (attempt in 1..8) assertEquals(401, f.request("/api/auth/login", "POST", mapOf("username" to "admin", "password" to "wrong")).statusCode())
            val limited = f.request("/api/auth/login", "POST", mapOf("username" to "admin", "password" to PASSWORD))
            assertEquals(429, limited.statusCode())
            assertEquals("300", limited.headers().firstValue("Retry-After").orElseThrow())
            f.time += 5 * 60_000L
            assertTrue(f.login("admin").startsWith("xiaovv_session="))
        }
    }

    @Test fun `tableau commun personnel transfert et execution autorisee sans exposer les URLs`() {
        Fixture().use { f ->
            f.users.setup("admin", PASSWORD)
            f.users.create("alice", PASSWORD, UserRole.USER)
            f.users.create("bob", PASSWORD, UserRole.USER)
            val admin = f.login("admin"); val alice = f.login("alice"); val bob = f.login("bob")
            fun field(response: HttpResponse<String>, key: String) = Regex("\"$key\":\"([^\"]*)\"").find(response.body())!!.groupValues[1]
            fun revision(response: HttpResponse<String>) = Regex("\"revision\":(\\d+)").find(response.body())!!.groupValues[1]
            val requests = java.util.concurrent.atomic.AtomicInteger()
            val target = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
            target.createContext("/fixture") { exchange -> requests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }
            target.start()
            try {
                val values = mapOf("kind" to "action", "scope" to "common", "name" to "Commun", "method" to "GET",
                    "url" to "http://127.0.0.1:${target.address.port}/fixture?key=fixture-secret")
                assertEquals(401, f.request("/api/dashboard").statusCode())
                assertEquals(403, f.request("/api/dashboard", "POST", values, cookie = admin).statusCode())
                assertEquals(403, f.request("/api/dashboard", "POST", values, cookie = alice, csrf = f.csrf(alice)).statusCode())
                val common = f.request("/api/dashboard", "POST", values, cookie = admin, csrf = f.csrf(admin))
                assertEquals(200, common.statusCode())
                val commonId = field(common, "id")
                val commonList = f.request("/api/dashboard", cookie = alice)
                assertTrue(commonList.body().contains(commonId))
                assertFalse(commonList.body().contains("fixture-secret"))
                assertEquals(403, f.request("/api/dashboard/$commonId/execute", "POST", cookie = alice).statusCode())
                assertEquals(200, f.request("/api/dashboard/$commonId/execute", "POST", cookie = alice, csrf = f.csrf(alice)).statusCode())
                assertEquals(1, requests.get())
                assertEquals(403, f.request("/api/http-action", "POST", values, cookie = alice, csrf = f.csrf(alice)).statusCode())
                val personalValues = values + mapOf("scope" to "personal", "name" to "Personnel", "importId" to "old-button-id")
                val personal = f.request("/api/dashboard", "POST", personalValues, cookie = alice, csrf = f.csrf(alice))
                assertEquals(200, personal.statusCode())
                val personalId = field(personal, "id")
                assertEquals(personalId, field(f.request("/api/dashboard", "POST", personalValues, cookie = alice, csrf = f.csrf(alice)), "id"))
                assertFalse(f.request("/api/dashboard", cookie = bob).body().contains(personalId))
                assertEquals(403, f.request("/api/dashboard?owner=alice", cookie = bob).statusCode())
                assertEquals(403, f.request("/api/dashboard/$personalId/execute", "POST", cookie = bob, csrf = f.csrf(bob)).statusCode())
                assertEquals(403, f.request("/api/dashboard", "POST", personalValues + ("owner" to "bob"), cookie = alice, csrf = f.csrf(alice)).statusCode())
                val transfer = mapOf("target" to "bob", "mode" to "copy", "revision" to revision(personal))
                val copy = f.request("/api/dashboard/$personalId/transfer", "POST", transfer, cookie = alice, csrf = f.csrf(alice))
                assertEquals(200, copy.statusCode()); assertNotEquals(personalId, field(copy, "id"))
                val moved = f.request("/api/dashboard/$personalId/transfer", "POST", transfer + ("mode" to "move"), cookie = alice, csrf = f.csrf(alice))
                assertEquals(200, moved.statusCode())
                assertFalse(f.request("/api/dashboard", cookie = alice).body().contains(personalId))
                assertTrue(f.request("/api/dashboard", cookie = bob).body().contains(personalId))
                assertEquals(409, f.request("/api/dashboard", "POST", personalValues + mapOf("id" to personalId, "revision" to revision(personal)), cookie = bob, csrf = f.csrf(bob)).statusCode())
                assertTrue(f.request("/api/dashboard?owner=bob", cookie = admin).body().contains(personalId))
                assertEquals(200, f.request("/api/dashboard/layout", "POST", mapOf("hidden" to commonId, "sizes" to "{}"), cookie = bob, csrf = f.csrf(bob)).statusCode())
                assertEquals(commonId, DashboardStore(f.authentication.dashboard.file).layout("bob")["hidden"])
                assertFalse(f.request("/api/dashboard", cookie = alice).body().contains("\"hidden\""))
                assertEquals(200, f.request("/api/mqtt/sources", cookie = alice).statusCode())
                assertEquals(403, f.request("/api/mqtt/servers", cookie = alice).statusCode())
            } finally { target.stop(0) }
        }
    }

    companion object {
        const val TOKEN = "integration-token-not-a-secret"
        const val PASSWORD = "un mot de passe de test"
    }
}
