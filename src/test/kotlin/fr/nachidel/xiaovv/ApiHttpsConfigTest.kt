package fr.nachidel.xiaovv

import fr.nachidel.xiaovv.camera.CameraApiServer
import fr.nachidel.xiaovv.rtsp.RtspServer
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.KeyStore
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiHttpsConfigTest {
    private fun properties(vararg values: Pair<String, String>) = Properties().apply {
        values.forEach { (key, value) -> setProperty(key, value) }
    }

    @Test
    fun `HTTP seul reste le comportement par defaut`() {
        assertNull(ApiHttpsConfig.load(Properties(), File("."), "127.0.0.1", 8080) { null })
    }

    @Test
    fun `les variables ont priorite et les chemins sont relatifs au fichier de configuration`() {
        val configDirectory = File("build/https-test-config").absoluteFile
        val config = ApiHttpsConfig.load(
            properties(
                "api.https.enabled" to "true",
                "api.https.port" to "8443",
                "api.https.key-store" to "certs/xiaovv.p12",
                "api.https.key-store-password-env" to "TEST_CERT_PASSWORD"
            ), configDirectory, "127.0.0.1", 8080
        ) { mapOf("XIAOVV_HTTPS_PORT" to "9443", "TEST_CERT_PASSWORD" to PASSWORD)[it] }!!
        assertEquals(9443, config.port)
        assertEquals("127.0.0.1", config.bindAddress)
        assertEquals(File(configDirectory, "certs/xiaovv.p12"), config.keyStoreFile)
        assertTrue(!config.toString().contains(PASSWORD))
    }

    @Test
    fun `les erreurs de configuration HTTPS sont refusees`() {
        for (port in listOf("0", "65536", "8080")) {
            assertFailsWith<IllegalArgumentException> {
                ApiHttpsConfig.load(
                    properties("api.https.enabled" to "true", "api.https.port" to port),
                    File("."), "127.0.0.1", 8080
                ) { null }
            }
        }
        assertFailsWith<IllegalStateException> {
            ApiHttpsConfig.load(
                properties("api.https.enabled" to "true", "api.https.key-store" to "missing.p12"),
                File("."), "127.0.0.1", 8080
            ) { null }
        }
    }

    @Test
    fun `un fichier absent ou un mauvais mot de passe bloque le demarrage`() {
        assertFailsWith<IllegalArgumentException> {
            ApiHttpsConfig("127.0.0.1", 0, File(certificate.parentFile, "missing.p12"), PASSWORD)
                .createServer()
        }
        assertFailsWith<IllegalArgumentException> {
            ApiHttpsConfig("127.0.0.1", 0, certificate, "incorrect-password").createServer()
        }
    }

    @Test
    fun `le certificat peut etre verifie sans occuper le port du service actif`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { occupied ->
            ApiHttpsConfig("127.0.0.1", occupied.localPort, certificate, PASSWORD).validateCertificate()
            assertFailsWith<IllegalArgumentException> {
                ApiHttpsConfig("127.0.0.1", occupied.localPort, certificate, "incorrect-password")
                    .validateCertificate()
            }
        }
    }

    @Test
    fun `le mot de passe Windows est chiffre puis relu sans nouvelle saisie`() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val directory = Files.createTempDirectory("xiaovv-dpapi-test-").toFile().apply { deleteOnExit() }
        val secretFile = File(directory, "password.dpapi").apply { deleteOnExit() }
        val entered = PASSWORD.toCharArray()
        var prompts = 0
        val store = WindowsHttpsPasswordStore(secretFile) { prompts++; entered }
        assertEquals(PASSWORD, store.loadOrCreate(certificate, "PKCS12"))
        assertEquals(1, prompts)
        assertTrue(entered.all { it == '\u0000' })
        assertTrue(secretFile.length() > 0)
        assertFalse(secretFile.readText().contains(PASSWORD))
        assertEquals(PASSWORD, WindowsHttpsPasswordStore(secretFile) {
            error("Le mot de passe enregistré ne doit pas être redemandé")
        }.loadOrCreate(certificate, "PKCS12"))

        val config = ApiHttpsConfig.load(
            properties(
                "api.https.enabled" to "true",
                "api.https.port" to ServerSocket(0).use { it.localPort.toString() },
                "api.https.key-store" to certificate.absolutePath,
                "api.https.key-store-password-file" to "password.dpapi"
            ), directory, "127.0.0.1", 8080
        ) { null }!!
        val server = config.createServer()
        try { server.start() } finally { server.stop(0) }
    }

    @Test
    fun `un mot de passe incorrect ou annule ne cree pas le fichier Windows`() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val directory = Files.createTempDirectory("xiaovv-dpapi-invalid-").toFile().apply { deleteOnExit() }
        val secretFile = File(directory, "password.dpapi")
        val entered = "incorrect-password".toCharArray()
        assertFailsWith<IllegalArgumentException> {
            WindowsHttpsPasswordStore(secretFile) { entered }.loadOrCreate(certificate, "PKCS12")
        }
        assertFalse(secretFile.exists())
        assertTrue(entered.all { it == '\u0000' })
        assertFailsWith<IllegalStateException> {
            WindowsHttpsPasswordStore(secretFile) { null }.loadOrCreate(certificate, "PKCS12")
        }
        assertFalse(secretFile.exists())
        assertFailsWith<IllegalArgumentException> {
            WindowsHttpsPasswordStore(secretFile) { error("Pas de saisie sans certificat") }
                .loadOrCreate(File(directory, "absent.p12"), "PKCS12")
        }
    }

    @Test
    fun `un fichier Windows illisible est signale sans etre remplace`() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val secretFile = Files.createTempFile("xiaovv-dpapi-corrupt-", ".dpapi").toFile()
            .apply { deleteOnExit(); writeText("corrupted") }
        val error = assertFailsWith<IllegalStateException> {
            WindowsHttpsPasswordStore(secretFile) { error("Pas de saisie implicite") }
                .loadOrCreate(certificate, "PKCS12")
        }
        assertTrue(error.message!!.contains("compte Windows"))
        assertEquals("corrupted", secretFile.readText())
    }

    @Test
    fun `le mot de passe de lenvironnement reste prioritaire sur le fichier Windows`() {
        val config = ApiHttpsConfig.load(
            properties(
                "api.https.enabled" to "true",
                "api.https.port" to ServerSocket(0).use { it.localPort.toString() },
                "api.https.key-store" to certificate.absolutePath,
                "api.https.key-store-password-file" to "absent.dpapi"
            ), File("."), "127.0.0.1", 8080
        ) { if (it == "XIAOVV_HTTPS_KEY_STORE_PASSWORD") PASSWORD else null }!!
        val server = config.createServer()
        try { server.start() } finally { server.stop(0) }
    }

    @Test
    fun `le serveur presente un certificat verifie en TLS 1_2 et 1_3`() {
        val server = ApiHttpsConfig("127.0.0.1", 0, certificate, PASSWORD).createServer()
        server.createContext("/") { exchange ->
            val content = "xiaovv-https".toByteArray()
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
            exchange.close()
        }
        try {
            server.start()
            val store = KeyStore.getInstance("PKCS12")
            certificate.inputStream().use { store.load(it, PASSWORD.toCharArray()) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trust.init(store)
            val context = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }

            for (protocol in listOf("TLSv1.2", "TLSv1.3")) {
                HttpClient.newBuilder()
                    .sslContext(context)
                    .sslParameters(SSLParameters().apply { protocols = arrayOf(protocol) })
                    .connectTimeout(Duration.ofSeconds(5))
                    .build().use { client ->
                        val request = HttpRequest.newBuilder(
                            URI("https://127.0.0.1:${server.address.port}/")
                        ).timeout(Duration.ofSeconds(5)).build()
                        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                        assertEquals(200, response.statusCode())
                        assertEquals("xiaovv-https", response.body())
                        assertEquals(protocol, response.sslSession().orElseThrow().protocol)
                    }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `le dashboard et le token API fonctionnent sur HTTP et HTTPS`() {
        val mqtt = Files.createTempFile("xiaovv-https-mqtt-", ".properties").toFile()
        mqtt.writeText("mqtt.ids=\n")
        mqtt.deleteOnExit()
        val previousMqtt = System.getProperty("xiaovv.mqtt.config")
        System.setProperty("xiaovv.mqtt.config", mqtt.absolutePath)
        try {
            ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { httpReservation ->
                ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { httpsReservation ->
                    val httpPort = httpReservation.localPort
                    val httpsPort = httpsReservation.localPort
                    httpReservation.close()
                    httpsReservation.close()
                    val runtime = CameraRuntimeManager(RtspServer("127.0.0.1", 0))
                    val cast = CastManager(0, "127.0.0.1", httpPort)
                    val authentication = WebAuthentication(UserStore(File(mqtt.parentFile, mqtt.name + ".users")))
                    authentication.users.setup("admin", "integration-account-password")
                    CameraApiServer(
                        runtime, cast, "127.0.0.1", httpPort, "xiaovv-integration-test-token", 0,
                        ApiHttpsConfig("127.0.0.1", httpsPort, certificate, PASSWORD),
                        authentication
                    ).use { api ->
                        api.start()
                        val store = KeyStore.getInstance("PKCS12")
                        certificate.inputStream().use { store.load(it, PASSWORD.toCharArray()) }
                        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                        trust.init(store)
                        val context = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
                        HttpClient.newBuilder().sslContext(context).build().use { client ->
                            for (base in listOf("http://127.0.0.1:$httpPort", "https://127.0.0.1:$httpsPort")) {
                                fun get(path: String, token: String? = null): HttpResponse<String> {
                                    val request = HttpRequest.newBuilder(URI(base + path))
                                        .timeout(Duration.ofSeconds(5))
                                    token?.let { request.header("X-API-Token", it) }
                                    return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
                                }
                                assertEquals(302, get("/").statusCode())
                                assertEquals("/login", get("/").headers().firstValue("Location").orElseThrow())
                                assertEquals(200, get("/login").statusCode())
                                assertEquals(401, get("/api/cameras").statusCode())
                                assertEquals(401, get("/api/cameras", "wrong-token").statusCode())
                                assertEquals(200, get("/api/cameras", "xiaovv-integration-test-token").statusCode())
                                val login = client.send(HttpRequest.newBuilder(URI("$base/api/auth/login"))
                                    .header("Content-Type", "application/x-www-form-urlencoded")
                                    .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=integration-account-password"))
                                    .build(), HttpResponse.BodyHandlers.ofString())
                                assertEquals(200, login.statusCode())
                                val cookie = login.headers().firstValue("Set-Cookie").orElseThrow()
                                assertTrue(cookie.contains("HttpOnly"))
                                if (base.startsWith("https:")) {
                                    assertTrue(cookie.startsWith("__Host-xiaovv_session="))
                                    assertTrue(cookie.contains("; Secure"))
                                } else assertFalse(cookie.contains("; Secure"))
                                val authenticated = client.send(HttpRequest.newBuilder(URI("$base/"))
                                    .header("Cookie", cookie.substringBefore(';')).build(), HttpResponse.BodyHandlers.ofString())
                                assertEquals(200, authenticated.statusCode())
                                assertFalse(authenticated.body().contains("xiaovv-integration-test-token"))
                            }
                        }
                    }
                    runtime.close()
                    cast.close()
                    // Les deux ports sont libérés à l'arrêt de l'application.
                    ServerSocket(httpPort, 50, InetAddress.getLoopbackAddress()).use { }
                    ServerSocket(httpsPort, 50, InetAddress.getLoopbackAddress()).use { }
                }
            }
        } finally {
            if (previousMqtt == null) System.clearProperty("xiaovv.mqtt.config")
            else System.setProperty("xiaovv.mqtt.config", previousMqtt)
        }
    }

    @Test
    fun `un echec du port HTTP libere aussi le port HTTPS`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { occupiedHttp ->
            val httpsPort = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
            val runtime = CameraRuntimeManager(RtspServer("127.0.0.1", 0))
            val cast = CastManager(0, "127.0.0.1", occupiedHttp.localPort)
            CameraApiServer(
                runtime, cast, "127.0.0.1", occupiedHttp.localPort, "xiaovv-integration-test-token", 0,
                ApiHttpsConfig("127.0.0.1", httpsPort, certificate, PASSWORD)
            ).use { api ->
                assertFailsWith<java.net.BindException> { api.start() }
                ServerSocket(httpsPort, 50, InetAddress.getLoopbackAddress()).use { }
            }
            runtime.close()
            cast.close()
        }
    }

    companion object {
        private const val PASSWORD = "xiaovv-test-certificate-password"
        private val certificate: File by lazy {
            val directory = Files.createTempDirectory("xiaovv-https-test-").toFile()
            directory.deleteOnExit()
            val file = File(directory, "localhost.p12").apply { deleteOnExit() }
            val windows = System.getProperty("os.name").lowercase().contains("windows")
            val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (windows) ".exe" else "")
            val process = ProcessBuilder(
                keytool.absolutePath, "-genkeypair", "-alias", "xiaovv-test", "-storetype", "PKCS12",
                "-keystore", file.absolutePath, "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt"
            ).redirectErrorStream(true).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("keytool n'a pas terminé la génération du certificat de test")
            }
            val output = process.inputStream.bufferedReader().readText()
            check(process.exitValue() == 0) { "Impossible de créer le certificat de test : $output" }
            file
        }
    }
}
