package fr.nachidel.xiaovv

import fr.nachidel.xiaovv.camera.CameraSupervisor
import fr.nachidel.xiaovv.logging.logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import fr.nachidel.xiaovv.rtsp.RtspServer

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

    val supervisors =
        mutableListOf<CameraSupervisor>()

    for (
    camera in
    config.cameras
    ) {

        val stream =
            rtspServer.createStream(
                name =
                    camera.streamName
            )

        val supervisor =
            CameraSupervisor(
                config =
                    camera,

                rtspStream =
                    stream
            )

        supervisors.add(
            supervisor
        )
    }

    /*
     * ============================================================
     * API HTTP
     * ============================================================
     */

    val apiServer =
        CameraApiServer(
            supervisors =
                supervisors,

            bindAddress =
                config.api.bindAddress,

            port =
                config.api.port,

            apiToken =
                config.api.token
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

                    for (
                    supervisor in
                    supervisors
                    ) {

                        try {

                            supervisor.close()

                        } catch (_: Exception) {
                        }
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

    for (
    supervisor in
    supervisors
    ) {

        supervisor.start()
    }

    /*
     * ============================================================
     * START API
     * ============================================================
     */

    apiServer.start()

    log.info(
        "{} caméra(s) configurée(s)",
        supervisors.size
    )

    if (
        config.api.bindAddress ==
        "0.0.0.0"
    ) {

        log.info(
            "Pilotage LAN : http://<IP_DU_SERVEUR>:{}/",
            config.api.port
        )

    } else {

        log.info(
            "Pilotage : http://{}:{}/",
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