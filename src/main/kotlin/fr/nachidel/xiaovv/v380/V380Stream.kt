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

    companion object {

        /**
         * Si la socket reste ouverte mais qu'aucune frame média
         * complète n'est reçue pendant ce délai, on considère le
         * flux bloqué.
         *
         * La boucle se termine alors volontairement afin que le
         * CameraSupervisor ferme les sockets et reconnecte la caméra.
         */
        const val VIDEO_STALL_TIMEOUT_MS = 20_000L
    }

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

    private val audioListeners =
        CopyOnWriteArrayList<AudioFrameListener>()

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

    fun interface AudioFrameListener {

        fun onAudioFrame(
            frame: V380MediaDecoder.DecodedAudioFrame
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

    fun addAudioListener(
        listener: AudioFrameListener
    ) {

        audioListeners.addIfAbsent(
            listener
        )

        log.debug(
            "Listener audio ajouté - total={}",
            audioListeners.size
        )
    }

    fun removeAudioListener(
        listener: AudioFrameListener
    ) {

        audioListeners.remove(
            listener
        )

        log.debug(
            "Listener audio supprimé - total={}",
            audioListeners.size
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
        var totalAudioFrames = 0L
        var totalAudioBytes = 0L

        var windowFrames = 0L

        var lastStatsTime =
            System.nanoTime()

        /*
         * Horodatage de la dernière frame média complète reçue.
         *
         * Il est volontairement initialisé au démarrage afin qu'une
         * connexion qui s'ouvre mais ne produit jamais de données
         * soit également détectée par le watchdog.
         */
        var lastMediaFrameTime =
            System.nanoTime()

        try {

            while (running.get()) {

                try {

                    /*
                     * Lecture d'une frame V380 complète.
                     *
                     * V380MediaParser tente désormais de se
                     * resynchroniser lui-même lorsqu'il rencontre
                     * un header décalé / corrompu.
                     */
                    val mediaFrame =
                        parser.readNextFrame(
                            input
                        )

                    lastMediaFrameTime =
                        System.nanoTime()

                    /*
                     * Audio Xiaovv : type 0x18.
                     *
                     * Le décodeur retire le header interne V380,
                     * déchiffre l'AES et retire l'en-tête ADTS.
                     * Le listener reçoit donc directement un Access Unit AAC.
                     */
                    if (mediaFrame.isAudio) {

                        try {

                            val decodedAudio =
                                decoder.decodeAudio(
                                    mediaFrame
                                )

                            totalAudioFrames++
                            totalAudioBytes +=
                                decodedAudio.payload.size

                            if (totalAudioFrames == 1L) {

                                log.info(
                                    "AUDIO détecté : AAC LC {} Hz, {} canal(aux), type=0x{}",
                                    decodedAudio.sampleRate,
                                    decodedAudio.channels,
                                    decodedAudio.outerType
                                        .toString(16)
                                        .uppercase()
                                        .padStart(2, '0')
                                )
                            }

                            notifyAudioListeners(
                                decodedAudio
                            )

                        } catch (e: Exception) {

                            /*
                             * Une frame audio corrompue ne doit jamais
                             * faire tomber la vidéo ni provoquer une
                             * reconnexion de la caméra.
                             */
                            log.warn(
                                "Frame audio invalide ignorée : {}",
                                e.message
                            )
                        }

                        continue
                    }

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
                     * Statistiques toutes les 30 secondes.
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

                        if (totalAudioFrames > 0) {

                            log.info(
                                "AUDIO stats : {} frames AAC | {} Mo reçus",
                                totalAudioFrames,
                                formatDouble(
                                    totalAudioBytes /
                                            1024.0 /
                                            1024.0
                                )
                            )
                        }

                        windowFrames = 0

                        lastStatsTime =
                            now
                    }

                } catch (e: SocketTimeoutException) {

                    /*
                     * Un timeout isolé n'est pas forcément une erreur.
                     *
                     * En revanche, plusieurs timeouts successifs sans
                     * aucune frame média valide indiquent une connexion
                     * bloquée. Dans ce cas on remonte l'exception afin
                     * que la boucle s'arrête et que CameraSupervisor
                     * reconnecte complètement la caméra.
                     */
                    if (running.get()) {

                        val stalledMs =
                            (System.nanoTime() -
                                    lastMediaFrameTime) /
                                    1_000_000L

                        if (
                            stalledMs >=
                            VIDEO_STALL_TIMEOUT_MS
                        ) {

                            throw SocketTimeoutException(
                                "Aucune frame média valide depuis " +
                                        "$stalledMs ms"
                            ).also {
                                it.initCause(e)
                            }
                        }

                        log.debug(
                            "Timeout de lecture vidéo - " +
                                    "dernière frame il y a {} ms",
                            stalledMs
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

        } catch (e: SocketTimeoutException) {

            if (running.get()) {

                log.error(
                    "Watchdog vidéo déclenché : flux bloqué",
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

            if (totalAudioFrames > 0) {

                log.info(
                    "AUDIO total : {} frames AAC, {} Mo",
                    totalAudioFrames,
                    formatDouble(
                        totalAudioBytes /
                                1024.0 /
                                1024.0
                    )
                )
            }
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

    private fun notifyAudioListeners(
        frame: V380MediaDecoder.DecodedAudioFrame
    ) {

        for (listener in audioListeners) {

            try {

                listener.onAudioFrame(
                    frame
                )

            } catch (e: Exception) {

                log.error(
                    "Erreur dans un listener audio",
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
