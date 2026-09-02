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

    /*
     * ============================================================
     * BOUCLE DE SUPERVISION
     * ============================================================
     *
     * Principe :
     *
     * - aucune limite de tentatives ;
     * - un nouveau V380Client est créé à chaque reconnexion ;
     * - toute ancienne socket est fermée avant de repartir ;
     * - une erreur d'une caméra ne touche jamais les autres ;
     * - tant que close() n'a pas été appelé, on réessaie.
     */
    private fun runLoop() {

        val resolution =
            when (
                config.resolution
            ) {

                CameraResolution.LOW ->
                    V380Protocol.RESOLUTION_LOW

                CameraResolution.HIGH ->
                    V380Protocol.RESOLUTION_HIGH
            }

        var attempt = 0L

        while (
            running.get()
        ) {

            var client: V380Client? =
                null

            try {

                attempt++

                client =
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

                client.addAudioListener(
                    rtspStream
                )

                log.info(
                    "[{}] connexion à {}:{} (tentative {})",
                    config.id,
                    config.host,
                    config.port,
                    attempt
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

                /*
                 * Une fois la caméra connectée, le compteur sert
                 * uniquement à diagnostiquer les séries d'échecs.
                 */
                attempt = 0

                /*
                 * await() revient lorsque V380Stream s'arrête :
                 * EOF, socket fermée, watchdog, erreur non récupérable,
                 * ou arrêt volontaire.
                 */
                client.await()

                if (
                    running.get()
                ) {

                    log.warn(
                        "[{}] flux caméra interrompu - " +
                                "reconnexion automatique",
                        config.id
                    )
                }

            } catch (e: InterruptedException) {

                /*
                 * close() interrompt volontairement le superviseur.
                 */
                Thread.currentThread()
                    .interrupt()

                if (
                    running.get()
                ) {

                    log.warn(
                        "[{}] superviseur interrompu",
                        config.id
                    )
                }

                break

            } catch (e: Exception) {

                if (
                    running.get()
                ) {

                    log.warn(
                        "[{}] caméra indisponible : {}",
                        config.id,
                        e.message ?: e.javaClass.simpleName
                    )

                    log.debug(
                        "[{}] détail de l'erreur de connexion",
                        config.id,
                        e
                    )
                }

            } catch (t: Throwable) {

                /*
                 * On protège le thread contre les erreurs inattendues
                 * non fatales, mais on ne masque jamais une erreur JVM
                 * grave telle qu'un OutOfMemoryError.
                 */
                if (isFatal(t)) {
                    throw t
                }

                if (
                    running.get()
                ) {

                    log.error(
                        "[{}] erreur inattendue dans le superviseur - " +
                                "reconnexion automatique",
                        config.id,
                        t
                    )
                }

            } finally {

                if (client != null) {

                    try {
                        client.close()
                    } catch (e: Exception) {

                        log.debug(
                            "[{}] erreur ignorée pendant la fermeture du client : {}",
                            config.id,
                            e.message
                        )
                    }

                    currentClient.compareAndSet(
                        client,
                        null
                    )
                }
            }

            if (
                running.get() &&
                !waitBeforeReconnect()
            ) {
                break
            }
        }

        log.debug(
            "[{}] boucle du superviseur terminée",
            config.id
        )
    }

    private fun waitBeforeReconnect(): Boolean {

        val delayMs =
            config.reconnectDelayMs
                .coerceAtLeast(0L)

        if (delayMs == 0L) {
            return running.get()
        }

        log.info(
            "[{}] reconnexion dans {} ms",
            config.id,
            delayMs
        )

        return try {

            Thread.sleep(
                delayMs
            )

            running.get()

        } catch (_: InterruptedException) {

            Thread.currentThread()
                .interrupt()

            false
        }
    }

    private fun isFatal(
        throwable: Throwable
    ): Boolean {

        return throwable is VirtualMachineError ||
                throwable is ThreadDeath ||
                throwable is LinkageError
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
