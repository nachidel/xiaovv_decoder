package fr.nachidel.xiaovv

import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.SwingUtilities

/** DPAPI lie le secret au compte Windows courant, sans dépendance native supplémentaire. */
internal class WindowsHttpsPasswordStore(
    private val file: File,
    private val prompt: () -> CharArray? = ::askHttpsPassword
) {
    fun loadOrCreate(keyStoreFile: File, keyStoreType: String): String {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "Le stockage chiffré HTTPS nécessite Windows. Sur Linux, utiliser la variable du mot de passe."
        }
        if (file.exists()) {
            val encrypted = file.readBytes()
            val clear = try {
                transform(encrypted, protect = false)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Impossible de lire le mot de passe HTTPS avec ce compte Windows. " +
                        "Supprimer ${file.absolutePath} pour le saisir à nouveau.", e
                )
            }
            return try { clear.toString(Charsets.UTF_8) } finally { clear.fill(0) }
        }

        require(keyStoreFile.isFile) { "Certificat HTTPS introuvable : ${keyStoreFile.absolutePath}" }
        val password = prompt() ?: error("Saisie du mot de passe HTTPS annulée")
        try {
            require(password.isNotEmpty() && !password.all(Char::isWhitespace)) {
                "Le mot de passe du certificat HTTPS est obligatoire"
            }
            try {
                loadHttpsKeyStore(keyStoreFile, keyStoreType, password)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Certificat HTTPS invalide ou mot de passe incorrect. " +
                        "Le mot de passe n'a pas été enregistré ; relancer le programme pour réessayer.", e
                )
            }
            val value = String(password)
            val clear = value.toByteArray(Charsets.UTF_8)
            val encrypted = try { transform(clear, protect = true) } finally { clear.fill(0) }
            val target = file.absoluteFile.toPath()
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, ".xiaovv-password-", ".tmp")
            try {
                Files.write(temporary, encrypted)
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
            return value
        } finally {
            password.fill('\u0000')
        }
    }

    private fun transform(input: ByteArray, protect: Boolean): ByteArray {
        val method = if (protect) "Protect" else "Unprotect"
        // Le secret passe par stdin, jamais par les arguments, un fichier temporaire ou les logs.
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            try {
                Add-Type -AssemblyName System.Security
                ${'$'}data = [Convert]::FromBase64String([Console]::In.ReadLine())
                ${'$'}result = [System.Security.Cryptography.ProtectedData]::$method(
                    ${'$'}data, ${'$'}null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
                [Console]::Out.Write([Convert]::ToBase64String(${'$'}result))
            } catch { exit 1 }
        """.trimIndent()
        val powershell = File(
            System.getenv("SystemRoot") ?: "C:/Windows",
            "System32/WindowsPowerShell/v1.0/powershell.exe"
        )
        val process = ProcessBuilder(
            powershell.absolutePath, "-NoLogo", "-NoProfile", "-NonInteractive",
            "-WindowStyle", "Hidden", "-Command", script
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readBytes() } }
        val encoded = Base64.getEncoder().encode(input)
        try {
            process.outputStream.use {
                it.write(encoded)
                it.write('\n'.code)
            }
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                error("Le chiffrement Windows du mot de passe HTTPS n'a pas répondu")
            }
            check(process.exitValue() == 0) { "Échec du chiffrement Windows du mot de passe HTTPS" }
            val result = output.get(5, TimeUnit.SECONDS)
            return try {
                Base64.getDecoder().decode(result)
            } finally {
                result.fill(0)
            }
        } finally {
            encoded.fill(0)
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

private fun askHttpsPassword(): CharArray? {
    System.console()?.let { return it.readPassword("Mot de passe d'export du certificat HTTPS : ") }
    check(!GraphicsEnvironment.isHeadless()) {
        "Saisie interactive indisponible : définir la variable du mot de passe HTTPS."
    }
    var result: CharArray? = null
    val show = Runnable {
        val field = JPasswordField(24)
        val panel = JPanel(BorderLayout(0, 10)).apply {
            add(JLabel("<html>Mot de passe d'export du certificat Jeedom.<br>" +
                "Il sera enregistré chiffré pour ton compte Windows sur ce PC.</html>"), BorderLayout.NORTH)
            add(field, BorderLayout.CENTER)
        }
        SwingUtilities.invokeLater { field.requestFocusInWindow() }
        try {
            if (JOptionPane.showConfirmDialog(
                    null, panel, "Xiaovv — Activer HTTPS", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE
                ) == JOptionPane.OK_OPTION) {
                result = field.password
            }
        } finally {
            field.text = ""
        }
    }
    if (SwingUtilities.isEventDispatchThread()) show.run() else SwingUtilities.invokeAndWait(show)
    return result
}
