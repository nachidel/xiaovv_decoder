package fr.nachidel.xiaovv.v380

import fr.nachidel.xiaovv.logging.logger
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class V380Stream(
    private val input: InputStream,
    authTicket: Int
) : Closeable {

    private val log = logger<V380Stream>()

    private val parser =
        V380MediaParser()

    private val decoder =
        V380MediaDecoder(
            authTicket = authTicket
        )

    private val running =
        AtomicBoolean(false)

    private var workerThread: Thread? = null

    /*
     * Plusieurs consommateurs pourront écouter le flux :
     *
     * - RTSP
     * - enregistrement
     * - snapshot
     * - debug
     *
     * sans toucher au code réseau V380.
     */
    private val listeners =
        CopyOnWriteArrayList<FrameListener>()

    /*
     * ============================================================
     * LISTENER
     * ============================================================
     */

    fun interface FrameListener {

        fun onFrame(
            frame: V380MediaDecoder.DecodedVideoFrame
        )
    }

    /*
     * ============================================================
     * STATISTIQUES
     * ============================================================
     */

    data class Statistics(
        val frames: Long,
        val keyFrames: Long,
        val bytes: Long,
        val fps: Double
    )

    @Volatile
    private var statistics =
        Statistics(
            frames = 0,
            keyFrames = 0,
            bytes = 0,
            fps = 0.0
        )

    fun getStatistics(): Statistics {
        return statistics
    }

    /*
     * ============================================================
     * LISTENERS
     * ============================================================
     */

    fun addListener(
        listener: FrameListener
    ) {

        listeners.add(
            listener
        )

        log.debug(
            "Listener vidéo ajouté - total={}",
            listeners.size
        )
    }

    fun removeListener(
        listener: FrameListener
    ) {

        listeners.remove(
            listener
        )

        log.debug(
            "Listener vidéo supprimé - total={}",
            listeners.size
        )
    }

    /*
     * ============================================================
     * START
     * ============================================================
     */

    fun start() {

        if (!running.compareAndSet(false, true)) {

            log.warn(
                "Le flux vidéo est déjà démarré"
            )

            return
        }

        log.info(
            "Démarrage du lecteur vidéo continu"
        )

        workerThread =
            Thread(
                {
                    streamLoop()
                },
                "xiaovv-video-stream"
            ).apply {

                /*
                 * On veut que ce thread fasse réellement
                 * partie de la vie du service.
                 */
                isDaemon = false

                start()
            }
    }

    /*
     * ============================================================
     * BOUCLE PRINCIPALE
     * ============================================================
     */

    private fun streamLoop() {

        log.info(
            "Boucle de réception vidéo démarrée"
        )

        var totalFrames = 0L
        var totalKeyFrames = 0L
        var totalBytes = 0L

        var windowFrames = 0L

        var lastStatsTime =
            System.nanoTime()

        try {

            while (running.get()) {

                try {

                    /*
                     * Lecture d'une frame V380 complète.
                     */
                    val mediaFrame =
                        parser.readNextFrame(
                            input
                        )

                    /*
                     * D'autres types de paquets peuvent
                     * circuler sur la socket.
                     */
                    if (!mediaFrame.isVideo) {

                        log.trace(
                            "Frame média ignorée : type=0x{}",
                            mediaFrame.type
                                .toString(16)
                                .uppercase()
                        )

                        continue
                    }

                    /*
                     * AES -> H.265.
                     */
                    val decoded =
                        decoder.decode(
                            mediaFrame
                        )

                    totalFrames++
                    windowFrames++

                    totalBytes +=
                        decoded.payload.size

                    if (decoded.keyFrame) {
                        totalKeyFrames++
                    }

                    log.debug(
                        "VIDEO frame={} type=0x{} NAL={} keyframe={} size={}",
                        totalFrames,
                        decoded.outerType
                            .toString(16)
                            .uppercase()
                            .padStart(2, '0'),
                        decoded.nalType ?: "?",
                        decoded.keyFrame,
                        decoded.payload.size
                    )

                    /*
                     * Distribution aux consommateurs.
                     */
                    notifyListeners(
                        decoded
                    )

                    /*
                     * Statistiques toutes les 5 secondes.
                     */
                    val now =
                        System.nanoTime()

                    val elapsed =
                        now - lastStatsTime

                    if (
                        elapsed >=
                        30_000_000_000L
                    ) {

                        val elapsedSeconds =
                            elapsed /
                                    1_000_000_000.0

                        val currentFps =
                            windowFrames /
                                    elapsedSeconds

                        statistics =
                            Statistics(
                                frames =
                                    totalFrames,

                                keyFrames =
                                    totalKeyFrames,

                                bytes =
                                    totalBytes,

                                fps =
                                    currentFps
                            )

                        log.info(
                            "VIDEO stats : {} frames | {} fps | {} keyframes | {} Mo reçus",
                            totalFrames,
                            formatDouble(
                                currentFps
                            ),
                            totalKeyFrames,
                            formatDouble(
                                totalBytes /
                                        1024.0 /
                                        1024.0
                            )
                        )

                        windowFrames = 0

                        lastStatsTime =
                            now
                    }

                } catch (_: SocketTimeoutException) {

                    /*
                     * Un timeout n'est pas forcément une erreur.
                     *
                     * Cela permet aussi de vérifier régulièrement
                     * running lors d'un arrêt du programme.
                     */

                    if (running.get()) {

                        log.debug(
                            "Timeout de lecture vidéo"
                        )
                    }
                }
            }

        } catch (e: EOFException) {

            if (running.get()) {

                log.error(
                    "La caméra a fermé le flux vidéo",
                    e
                )
            }

        } catch (e: SocketException) {

            if (running.get()) {

                log.error(
                    "Connexion vidéo interrompue",
                    e
                )
            }

        } catch (e: Exception) {

            if (running.get()) {

                log.error(
                    "Erreur dans la boucle vidéo",
                    e
                )
            }

        } finally {

            running.set(false)

            statistics =
                Statistics(
                    frames =
                        totalFrames,

                    keyFrames =
                        totalKeyFrames,

                    bytes =
                        totalBytes,

                    fps =
                        statistics.fps
                )

            log.info(
                "Boucle de réception vidéo arrêtée"
            )

            log.info(
                "VIDEO total : {} frames, {} keyframes, {} Mo",
                totalFrames,
                totalKeyFrames,
                formatDouble(
                    totalBytes /
                            1024.0 /
                            1024.0
                )
            )
        }
    }

    /*
     * ============================================================
     * DISTRIBUTION
     * ============================================================
     */

    private fun notifyListeners(
        frame: V380MediaDecoder.DecodedVideoFrame
    ) {

        for (listener in listeners) {

            try {

                listener.onFrame(
                    frame
                )

            } catch (e: Exception) {

                /*
                 * Un consommateur défectueux ne doit surtout
                 * pas tuer la récupération vidéo de la caméra.
                 */
                log.error(
                    "Erreur dans un listener vidéo",
                    e
                )
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

    /*
     * ============================================================
     * WAIT
     * ============================================================
     */

    fun await() {

        workerThread?.join()
    }

    fun await(
        timeoutMs: Long
    ) {

        require(timeoutMs >= 0) {
            "timeoutMs ne peut pas être négatif"
        }

        workerThread?.join(
            timeoutMs
        )
    }

    /*
     * ============================================================
     * STOP
     * ============================================================
     */

    fun stop() {

        if (!running.getAndSet(false)) {
            return
        }

        log.info(
            "Arrêt du flux vidéo demandé"
        )

        /*
         * interrupt() ne stoppe pas nécessairement
         * un InputStream réseau bloqué.
         *
         * La socket sera fermée par V380Client.close(),
         * ce qui libérera immédiatement la lecture.
         */
        workerThread?.interrupt()
    }

    override fun close() {
        stop()
    }

    /*
     * ============================================================
     * FORMAT
     * ============================================================
     */

    private fun formatDouble(
        value: Double
    ): String {

        return String.format(
            Locale.ROOT,
            "%.1f",
            value
        )
    }
}