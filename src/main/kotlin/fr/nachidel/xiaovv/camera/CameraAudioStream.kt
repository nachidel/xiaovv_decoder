package fr.nachidel.xiaovv.camera

import com.sun.net.httpserver.HttpExchange
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Un flux audio seul : le navigateur conserve le mur MJPEG sans décoder la vidéo H.265. */
internal object CameraAudioStream {
    private val timeoutOptions = ConcurrentHashMap<String, String>()
    private val monitor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "xiaovv-audio-monitor").apply { isDaemon = true }
    }

    fun stream(exchange: HttpExchange, camera: CameraSupervisor, rtspPort: Int, authorized: () -> Boolean) {
        val ffmpeg = System.getProperty("xiaovv.ffmpeg")?.takeIf { it.isNotBlank() }
            ?: System.getenv("XIAOVV_FFMPEG")?.takeIf { it.isNotBlank() } ?: "ffmpeg"
        val command = listOf(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-rtsp_transport", "tcp",
            timeoutOption(ffmpeg), "10000000", "-allowed_media_types", "audio", "-analyzeduration", "1000000",
            "-fflags", "nobuffer", "-i", "rtsp://127.0.0.1:$rtspPort/${camera.config.streamName}",
            "-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-b:a", "64k", "-ac", "1",
            "-flush_packets", "1", "-f", "mp3", "pipe:1")
        val process = try { ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
            catch (_: Exception) { unavailable(exchange); return }
        val started = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val check = monitor.scheduleAtFixedRate({
            if (!authorized() || (!started.get() && System.nanoTime() > deadline)) process.destroyForcibly()
        }, 1, 1, TimeUnit.SECONDS)
        try {
            process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                if (read < 0 || !authorized()) { unavailable(exchange); return }
                started.set(true)
                exchange.responseHeaders.set("Content-Type", "audio/mpeg")
                exchange.responseHeaders.set("Cache-Control", "no-store, no-cache")
                exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { output ->
                    while (read >= 0 && authorized()) {
                        if (read > 0) { output.write(buffer, 0, read); output.flush() }
                        read = input.read(buffer)
                    }
                }
            }
        } catch (_: Exception) {
            // Le navigateur ferme la connexion lors d'un changement de caméra ou d'un arrêt.
        } finally {
            check.cancel(false)
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private fun timeoutOption(ffmpeg: String): String = timeoutOptions.computeIfAbsent(ffmpeg) {
        // FFmpeg 4 (Debian 11) nomme cette option stimeout ; les versions récentes utilisent timeout.
        val process = try { ProcessBuilder(ffmpeg, "-hide_banner", "-h", "demuxer=rtsp")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start() } catch (_: Exception) { return@computeIfAbsent "-timeout" }
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) return@computeIfAbsent "-timeout"
            val options = process.inputStream.bufferedReader().use { it.readText() }
            if (Regex("(?m)^\\s+-stimeout\\s").containsMatchIn(options)) "-stimeout" else "-timeout"
        } finally { process.destroyForcibly() }
    }

    private fun unavailable(exchange: HttpExchange) {
        val bytes = """{"success":false,"error":"Son indisponible : vérifier le microphone de la caméra et FFmpeg"}""".toByteArray()
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(503, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}
