package fr.nachidel.xiaovv

import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.rtsp.RtspServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private val log =
    logger<Main>()

object Main

fun main() {

    log.info(
        "=================================================="
    )

    log.info(
        " Xiaovv Multi Camera RTSP Server"
    )

    log.info(
        "=================================================="
    )

    val config =
        AppConfig.load()

    val stopping =
        AtomicBoolean(false)

    val stopLatch =
        CountDownLatch(
            1
        )

    /*
     * ============================================================
     * RTSP
     * ============================================================
     */

    val rtspServer =
        RtspServer(
            bindAddress =
                config.rtsp.bindAddress,

            port =
                config.rtsp.port
        )

    /*
     * ============================================================
     * CAMÉRAS
     * ============================================================
     */

    val cameraRuntime =
        CameraRuntimeManager(
            rtspServer =
                rtspServer
        )

    cameraRuntime.initialize(
        config.cameras
    )

    /*
     * ============================================================
     * GOOGLE CAST
     * ============================================================
     */

    val castManager =
        CastManager(
            rtspPort =
                config.rtsp.port,

            apiBindAddress =
                config.api.bindAddress,

            apiPort =
                config.api.port
        )

    castManager.start()

    /*
     * ============================================================
     * API HTTP
     * ============================================================
     */

    val apiServer =
        CameraApiServer(
            cameraRuntime =
                cameraRuntime,

            castManager =
                castManager,

            bindAddress =
                config.api.bindAddress,

            port =
                config.api.port,

            apiToken =
                config.api.token,

            /*
             * Utilisé uniquement par le mur vidéo pour relire
             * localement les flux servis par notre propre serveur RTSP.
             */
            rtspPort =
                config.rtsp.port
        )

    /*
     * ============================================================
     * SHUTDOWN
     * ============================================================
     */

    Runtime.getRuntime()
        .addShutdownHook(

            Thread(
                {

                    if (
                        !stopping.compareAndSet(
                            false,
                            true
                        )
                    ) {
                        return@Thread
                    }

                    log.info(
                        "Arrêt de Xiaovv"
                    )

                    try {

                        apiServer.close()

                    } catch (_: Exception) {
                    }

                    try {

                        castManager.close()

                    } catch (_: Exception) {
                    }

                    try {

                        cameraRuntime.close()

                    } catch (_: Exception) {
                    }

                    try {

                        rtspServer.close()

                    } catch (_: Exception) {
                    }

                    stopLatch.countDown()

                },
                "xiaovv-shutdown"
            )
        )

    /*
     * ============================================================
     * START RTSP
     * ============================================================
     */

    rtspServer.start()

    for (
    camera in
    config.cameras
    ) {

        log.info(
            "RTSP [{}] : rtsp://127.0.0.1:{}/{}",
            camera.id,
            config.rtsp.port,
            camera.streamName
        )
    }

    /*
     * ============================================================
     * START CAMÉRAS
     * ============================================================
     */

    cameraRuntime.startAll()

    /*
     * ============================================================
     * START API
     * ============================================================
     */

    apiServer.start()

    log.info(
        "{} caméra(s) configurée(s)",
        cameraRuntime.snapshot().size
    )

    if (
        config.api.bindAddress ==
        "0.0.0.0"
    ) {

        log.info(
            "Pilotage LAN : http://<IP_DU_SERVEUR>:{}/",
            config.api.port
        )

        log.info(
            "Mur vidéo LAN : http://<IP_DU_SERVEUR>:{}/live",
            config.api.port
        )

    } else {

        log.info(
            "Pilotage : http://{}:{}/",
            config.api.bindAddress,
            config.api.port
        )

        log.info(
            "Mur vidéo : http://{}:{}/live",
            config.api.bindAddress,
            config.api.port
        )
    }

    /*
     * ============================================================
     * WAIT
     * ============================================================
     */

    try {

        stopLatch.await()

    } catch (_: InterruptedException) {

        Thread.currentThread()
            .interrupt()
    }
}
