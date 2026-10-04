package fr.nachidel.xiaovv

import fr.nachidel.xiaovv.rtsp.RtspAuthentication
import fr.nachidel.xiaovv.rtsp.RtspServer
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class RtspAuthenticationTest {
    private class Client(port: Int) : Closeable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
        val input = BufferedInputStream(socket.getInputStream())
        private var sequence = 0
        data class Response(val code: Int, val headers: Map<String, String>)
        fun request(method: String, stream: String, authorization: String? = null): Response {
            val request = "$method rtsp://127.0.0.1:${socket.port}/$stream RTSP/1.0\r\nCSeq: ${++sequence}\r\n" +
                (authorization?.let { "Authorization: $it\r\n" } ?: "") + "\r\n"
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val status = line().split(' ')[1].toInt()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            input.readNBytes(headers["content-length"]?.toInt() ?: 0)
            return Response(status, headers)
        }
        fun line(): String {
            val bytes = java.io.ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                check(byte >= 0) { "Connexion RTSP fermée" }
                if (byte == 10) break
                if (byte != 13) bytes.write(byte)
            }
            return bytes.toString(Charsets.US_ASCII)
        }
        override fun close() = socket.close()
    }

    @Test fun `RTSP demande les identifiants et respecte les identifiants camera independants des streams`() {
        val users = store()
        users.setup("admin", PASSWORD)
        users.create("alice", PASSWORD, UserRole.USER, setOf("salon"))
        RtspServer("127.0.0.1", 0, RtspAuthentication(users), trustLoopback = false).use { server ->
            server.createStream("video-salon", cameraId = "salon")
            server.createStream("video-entree", cameraId = "entree")
            server.start()
            Client(server.listeningPort).use { client ->
                assertEquals(200, client.request("OPTIONS", "video-salon").code)
                val challenge = client.request("DESCRIBE", "video-salon")
                assertEquals(401, challenge.code)
                assertTrue(challenge.headers["www-authenticate"]!!.startsWith("Basic realm=\"Xiaovv\""))
                assertEquals(401, client.request("DESCRIBE", "video-salon", basic("alice", "incorrect")).code)
                assertEquals(200, client.request("DESCRIBE", "video-salon", basic("alice", PASSWORD)).code)
                assertEquals(403, client.request("DESCRIBE", "video-entree").code)
                assertEquals(403, client.request("SETUP", "video-entree/trackID=0").code)
                users.update("alice", null, UserRole.USER, emptySet())
                assertEquals(-1, client.input.read(), "La connexion existante doit se fermer après retrait des droits")
            }
            Client(server.listeningPort).use { client ->
                assertEquals(200, client.request("DESCRIBE", "video-entree", basic("admin", PASSWORD)).code)
            }
        }
    }

    @Test fun `les sessions RTSP sont invalidees apres changement de droits ou de mot de passe`() {
        val users = store()
        users.setup("admin", PASSWORD)
        users.create("alice", PASSWORD, UserRole.USER, setOf("salon"))
        val auth = RtspAuthentication(users)
        val session = auth.authenticate(basic("alice", PASSWORD), "fixture-ip")!!
        assertTrue(auth.canUseCamera(session, "salon"))
        users.update("alice", null, UserRole.USER, emptySet())
        assertFalse(auth.isCurrent(session))
        val replacement = auth.authenticate(basic("alice", PASSWORD), "fixture-ip")!!
        assertFalse(auth.canUseCamera(replacement, "salon"))
        users.update("alice", "un nouveau mot de passe", UserRole.USER)
        assertFalse(auth.isCurrent(replacement))
        assertNull(auth.authenticate(basic("alice", PASSWORD), "fixture-ip"))
        assertNotNull(auth.authenticate(basic("alice", "un nouveau mot de passe"), "fixture-ip"))
    }

    @Test fun `les lectures locales de FFmpeg restent possibles et les tentatives sont limitees`() {
        val users = store()
        users.setup("admin", PASSWORD)
        var time = System.currentTimeMillis()
        val auth = RtspAuthentication(users) { time }
        repeat(8) { assertNull(auth.authenticate(basic("admin", "incorrect"), "fixture-ip")) }
        assertNull(auth.authenticate(basic("admin", PASSWORD), "fixture-ip"))
        time += 5 * 60_000L
        assertNotNull(auth.authenticate(basic("admin", PASSWORD), "fixture-ip"))
        RtspServer("127.0.0.1", 0, auth).use { server ->
            server.createStream("salon")
            server.start()
            Client(server.listeningPort).use { assertEquals(200, it.request("DESCRIBE", "salon").code) }
        }
    }

    private fun store() = UserStore(File(Files.createTempDirectory("xiaovv-rtsp-auth-test-").toFile(), "users.properties"))
    private fun basic(username: String, password: String) = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
    companion object { const val PASSWORD = "un mot de passe de test" }
}
