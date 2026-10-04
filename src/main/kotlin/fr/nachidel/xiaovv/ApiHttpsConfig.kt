package fr.nachidel.xiaovv

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Properties
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** HTTPS facultatif, en parallèle de l'accès HTTP utilisé sur le LAN par Cast. */
class ApiHttpsConfig(
    val bindAddress: String,
    val port: Int,
    val keyStoreFile: File,
    private val keyStorePassword: String,
    val keyStoreType: String = "PKCS12"
) {
    fun validateCertificate() {
        val password = keyStorePassword.toCharArray()
        try {
            loadHttpsKeyStore(keyStoreFile, keyStoreType, password)
        } catch (e: Exception) {
            throw IllegalArgumentException("Certificat HTTPS invalide ou mot de passe incorrect", e)
        } finally {
            password.fill('\u0000')
        }
    }

    fun createServer(): HttpsServer {
        require(keyStoreFile.isFile) {
            "Certificat HTTPS introuvable : ${keyStoreFile.absolutePath}"
        }

        val password = keyStorePassword.toCharArray()
        val context = try {
            val keyStore = loadHttpsKeyStore(keyStoreFile, keyStoreType, password)
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keys.init(keyStore, password)
            SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Impossible de charger le certificat HTTPS : vérifier le fichier, " +
                    "son format, sa validité et son mot de passe", e
            )
        } finally {
            password.fill('\u0000')
        }

        return HttpsServer.create(InetSocketAddress(bindAddress, port), 0).apply {
            httpsConfigurator = object : HttpsConfigurator(context) {
                override fun configure(parameters: HttpsParameters) {
                    val tls = sslContext.defaultSSLParameters
                    tls.protocols = tls.protocols.filter {
                        it == "TLSv1.3" || it == "TLSv1.2"
                    }.toTypedArray()
                    parameters.setSSLParameters(tls)
                }
            }
        }
    }

    companion object {
        fun load(
            properties: Properties,
            configDirectory: File,
            httpBindAddress: String,
            httpPort: Int,
            environment: (String) -> String? = System::getenv
        ): ApiHttpsConfig? {
            fun value(key: String, env: String, default: String? = null): String =
                environment(env)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: default ?: error("Configuration absente : $key")

            val enabled = value("api.https.enabled", "XIAOVV_HTTPS_ENABLED", "false")
                .toBooleanStrictOrNull()
                ?: error("api.https.enabled doit valoir true ou false")
            if (!enabled) return null

            val port = value("api.https.port", "XIAOVV_HTTPS_PORT", "8443").toInt()
            require(port in 1..65535) { "Port HTTPS invalide : $port" }
            require(port != httpPort) { "Les ports HTTP et HTTPS doivent être différents" }

            fun resolvePath(path: String): File = File(path).let {
                if (it.isAbsolute) it else File(configDirectory, it.path)
            }
            val keyStoreFile = resolvePath(value("api.https.key-store", "XIAOVV_HTTPS_KEY_STORE"))
            val type = value("api.https.key-store-type", "XIAOVV_HTTPS_KEY_STORE_TYPE", "PKCS12")
                .uppercase()
            require(type in setOf("PKCS12", "JKS")) {
                "api.https.key-store-type doit valoir PKCS12 ou JKS"
            }
            val passwordEnv = properties.getProperty("api.https.key-store-password-env")
                ?.trim()?.takeIf { it.isNotEmpty() } ?: "XIAOVV_HTTPS_KEY_STORE_PASSWORD"
            val passwordFile = environment("XIAOVV_HTTPS_PASSWORD_FILE")
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: properties.getProperty("api.https.key-store-password-file")
                    ?.trim()?.takeIf { it.isNotEmpty() }
            // L'environnement reste prioritaire ; le fichier Windows est chiffré pour ce compte.
            val password = environment(passwordEnv)?.takeIf { it.isNotBlank() }
                ?: passwordFile?.let {
                    WindowsHttpsPasswordStore(resolvePath(it)).loadOrCreate(keyStoreFile, type)
                }
                ?: error("Mot de passe du certificat HTTPS absent : définir $passwordEnv")

            return ApiHttpsConfig(
                bindAddress = value("api.https.bind-address", "XIAOVV_HTTPS_BIND", httpBindAddress),
                port = port,
                keyStoreFile = keyStoreFile,
                keyStorePassword = password,
                keyStoreType = type
            )
        }
    }
}

internal fun loadHttpsKeyStore(file: File, type: String, password: CharArray): KeyStore {
    require(file.isFile) { "Certificat HTTPS introuvable : ${file.absolutePath}" }
    val store = KeyStore.getInstance(type)
    file.inputStream().buffered().use { store.load(it, password) }
    val aliases = store.aliases().toList().filter {
        store.isKeyEntry(it) && store.getKey(it, password) is PrivateKey
    }
    require(aliases.isNotEmpty()) { "Le fichier HTTPS doit contenir une clé privée et son certificat" }
    for (alias in aliases) {
        val certificate = store.getCertificate(alias) as? X509Certificate
            ?: error("Certificat X.509 absent pour la clé HTTPS '$alias'")
        certificate.checkValidity()
    }
    return store
}
