package fr.nachidel.xiaovv.camera

import fr.nachidel.xiaovv.CameraConfig
import fr.nachidel.xiaovv.CameraResolution
import fr.nachidel.xiaovv.rtsp.RtspStream
import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.v380.V380Client
import fr.nachidel.xiaovv.v380.V380Protocol
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraSupervisor(
    val config: CameraConfig,
    private val rtspStream: RtspStream
) : Closeable {

    private val log =
        logger<CameraSupervisor>()

    private val running =
        AtomicBoolean(false)

    private val currentClient =
        AtomicReference<V380Client?>()

    private var thread:
            Thread? =
        null

    fun start() {

        if (
            !running.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        thread =
            Thread(
                {
                    runLoop()
                },
                "xiaovv-camera-${config.id}"
            ).apply {

                isDaemon =
                    false

                start()
            }

        log.info(
            "[{}] superviseur démarré",
            config.id
        )
    }

    private fun runLoop() {

        while (
            running.get()
        ) {

            val resolution =
                when (
                    config.resolution
                ) {

                    CameraResolution.LOW ->
                        V380Protocol.RESOLUTION_LOW

                    CameraResolution.HIGH ->
                        V380Protocol.RESOLUTION_HIGH
                }

            val client =
                V380Client(
                    host =
                        config.host,

                    port =
                        config.port,

                    deviceId =
                        config.deviceId,

                    username =
                        config.username,

                    password =
                        config.password,

                    videoResolution =
                        resolution
                )

            currentClient.set(
                client
            )

            client.addVideoListener(
                rtspStream
            )

            try {

                log.info(
                    "[{}] connexion à {}:{}",
                    config.id,
                    config.host,
                    config.port
                )

                client.connect()

                client.start()

                log.info(
                    "[{}] caméra connectée : {}x{} @ {} fps",
                    config.id,
                    client.getVideoWidth(),
                    client.getVideoHeight(),
                    client.getVideoFps()
                )

                client.await()

                if (
                    running.get()
                ) {

                    log.warn(
                        "[{}] flux caméra interrompu",
                        config.id
                    )
                }

            } catch (e: Exception) {

                if (
                    running.get()
                ) {

                    log.warn(
                        "[{}] caméra indisponible : {}",
                        config.id,
                        e.message
                    )
                }

            } finally {

                try {
                    client.close()
                } catch (_: Exception) {
                }

                currentClient.compareAndSet(
                    client,
                    null
                )
            }

            if (
                running.get()
            ) {

                log.info(
                    "[{}] reconnexion dans {} ms",
                    config.id,
                    config.reconnectDelayMs
                )

                try {

                    Thread.sleep(
                        config.reconnectDelayMs
                    )

                } catch (_: InterruptedException) {

                    Thread.currentThread()
                        .interrupt()

                    break
                }
            }
        }
    }

    /*
     * ============================================================
     * ÉTAT
     * ============================================================
     */

    fun isRunning(): Boolean {
        return running.get()
    }

    fun isConnected(): Boolean {

        return currentClient
            .get()
            ?.isStreamConnected()
            ?: false
    }

    /*
     * ============================================================
     * PTZ
     * ============================================================
     */

    fun ptzUp(): Boolean =
        executeCommand {
            it.ptzUp()
        }

    fun ptzDown(): Boolean =
        executeCommand {
            it.ptzDown()
        }

    fun ptzLeft(): Boolean =
        executeCommand {
            it.ptzLeft()
        }

    fun ptzRight(): Boolean =
        executeCommand {
            it.ptzRight()
        }

    fun ptzStop(): Boolean =
        executeCommand {
            it.ptzStop()
        }

    /*
     * ============================================================
     * LUMIÈRE
     * ============================================================
     */

    fun lightOn(): Boolean =
        executeCommand {
            it.lightOn()
        }

    fun lightOff(): Boolean =
        executeCommand {
            it.lightOff()
        }

    fun lightAuto(): Boolean =
        executeCommand {
            it.lightAuto()
        }

    /*
     * ============================================================
     * IMAGE
     * ============================================================
     */

    fun imageColor(): Boolean =
        executeCommand {
            it.imageColor()
        }

    fun imageBw(): Boolean =
        executeCommand {
            it.imageBw()
        }

    fun imageAuto(): Boolean =
        executeCommand {
            it.imageAuto()
        }

    fun imageFlip(): Boolean =
        executeCommand {
            it.imageFlip()
        }

    /*
     * ============================================================
     * COMMANDE GÉNÉRIQUE
     * ============================================================
     */

    private fun executeCommand(
        command: (V380Client) -> Unit
    ): Boolean {

        val client =
            currentClient.get()

        if (
            client == null
        ) {

            log.warn(
                "[{}] commande impossible : caméra non connectée",
                config.id
            )

            return false
        }

        if (
            !client.isStreamConnected()
        ) {

            log.warn(
                "[{}] commande impossible : flux non connecté",
                config.id
            )

            return false
        }

        return try {

            command(
                client
            )

            true

        } catch (e: Exception) {

            log.warn(
                "[{}] commande impossible : {}",
                config.id,
                e.message
            )

            false
        }
    }

    /*
     * ============================================================
     * CLOSE
     * ============================================================
     */

    override fun close() {

        if (
            !running.getAndSet(
                false
            )
        ) {
            return
        }

        log.info(
            "[{}] arrêt du superviseur",
            config.id
        )

        try {

            currentClient
                .get()
                ?.close()

        } catch (_: Exception) {
        }

        thread
            ?.interrupt()

        try {

            thread
                ?.join(
                    5_000
                )

        } catch (_: InterruptedException) {

            Thread.currentThread()
                .interrupt()
        }

        thread =
            null

        currentClient.set(
            null
        )

        log.info(
            "[{}] superviseur arrêté",
            config.id
        )
    }
}