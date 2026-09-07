package fr.nachidel.xiaovv

import com.sun.net.httpserver.HttpExchange
import fr.nachidel.xiaovv.logging.logger
import su.litvak.chromecast.api.v2.ChromeCast
import su.litvak.chromecast.api.v2.ChromeCasts
import su.litvak.chromecast.api.v2.Media
import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CastDeviceInfo(
    val name: String,
    val model: String?,
    val address: String,
    val port: Int
)

data class CastSessionInfo(
    val cameraId: String,
    val deviceName: String,
    val deviceAddress: String,
    val active: Boolean
)

class CastManager(
    private val rtspPort: Int,
    private val apiBindAddress: String,
    private val apiPort: Int
) : Closeable {

    companion object {

        /*
         * Default Media Receiver officiel Google Cast.
         */
        private const val DEFAULT_MEDIA_RECEIVER_APP_ID =
            "CC1AD845"

        private const val WATCHDOG_PERIOD_SECONDS =
            8L

        /*
         * Le watchdog ne se base plus sur getMediaStatus(), dont le
         * résultat peut être incomplet / transitoire selon les receivers.
         *
         * On surveille désormais quelque chose de beaucoup plus concret :
         * est-ce que l'écran continue réellement à demander la playlist
         * et les segments HLS à Xiaovv ?
         */
        private const val SEGMENT_REQUEST_STALE_MS =
            40_000L

        private const val LOAD_GRACE_PERIOD_MS =
            40_000L

        private const val MIN_RELOAD_INTERVAL_MS =
            30_000L

        /*
         * Avec des segments DASH de 2 s, plus de 12 s sans nouveau fragment
         * signifie que FFmpeg est vivant mais que sa timeline d'entrée s'est
         * probablement bloquée.
         */
        private const val ENCODER_OUTPUT_STALE_MS =
            12_000L
    }

    private val log =
        logger<CastManager>()

    private val running =
        AtomicBoolean(
            false
        )

    private val lock =
        ReentrantLock()

    private val sessionsByCamera =
        ConcurrentHashMap<String, CastSession>()

    private val scheduler =
        Executors.newSingleThreadScheduledExecutor {
                runnable ->

            Thread(
                runnable,
                "xiaovv-cast-watchdog"
            ).apply {
                isDaemon =
                    true
            }
        }

    fun start() {

        if (
            !running.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        try {

            ChromeCasts.startDiscovery()

            log.info(
                "Découverte Google Cast démarrée"
            )

        } catch (e: Exception) {

            /*
             * L'absence de Cast sur le réseau ne doit jamais empêcher
             * Xiaovv de démarrer.
             */
            log.warn(
                "Découverte Google Cast indisponible : {}",
                e.message
            )
        }

        scheduler.scheduleWithFixedDelay(
            {
                watchdog()
            },
            WATCHDOG_PERIOD_SECONDS,
            WATCHDOG_PERIOD_SECONDS,
            TimeUnit.SECONDS
        )
    }

    fun devices():
            List<CastDeviceInfo> {

        return try {

            ChromeCasts.get()
                .map { cast ->

                    CastDeviceInfo(
                        name =
                            cast.title
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: cast.name
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }
                                ?: cast.address,

                        model =
                            cast.model
                                ?.takeIf {
                                    it.isNotBlank()
                                },

                        address =
                            cast.address,

                        port =
                            cast.port
                    )
                }
                .distinctBy {
                    it.address
                }
                .sortedBy {
                    it.name.lowercase()
                }

        } catch (e: Exception) {

            log.warn(
                "Lecture des appareils Cast impossible : {}",
                e.message
            )

            emptyList()
        }
    }

    fun activeSessions():
            List<CastSessionInfo> {

        return sessionsByCamera
            .values
            .sortedBy {
                it.cameraId
            }
            .map {
                it.info()
            }
    }

    fun startCast(
        cameraId: String,
        streamName: String,
        deviceAddress: String
    ): CastSessionInfo {

        require(
            cameraId.isNotBlank()
        ) {
            "Caméra absente."
        }

        require(
            streamName.isNotBlank()
        ) {
            "Flux RTSP absent."
        }

        require(
            apiBindAddress != "127.0.0.1" &&
                    !apiBindAddress.equals(
                        "localhost",
                        ignoreCase = true
                    )
        ) {
            "Le Cast nécessite que l'API Xiaovv soit accessible sur le LAN " +
                    "(par exemple api.bind-address=0.0.0.0)."
        }

        val discovered =
            devices()
                .firstOrNull {
                    it.address ==
                            deviceAddress
                }

        val deviceName =
            discovered
                ?.name
                ?: deviceAddress

        val devicePort =
            discovered
                ?.port
                ?: 8009

        return lock.withLock {

            /*
             * Une caméra n'est castée que vers un écran à la fois.
             */
            sessionsByCamera.remove(
                cameraId
            )
                ?.let {
                    stopSession(
                        it,
                        stopReceiver =
                            true
                    )
                }

            /*
             * Un même écran ne reçoit qu'une caméra Xiaovv.
             * Si l'utilisateur choisit cet écran depuis une autre tuile,
             * on remplace proprement l'ancien cast.
             */
            sessionsByCamera
                .values
                .filter {
                    it.deviceAddress ==
                            deviceAddress
                }
                .toList()
                .forEach { session ->

                    sessionsByCamera.remove(
                        session.cameraId,
                        session
                    )

                    stopSession(
                        session,
                        stopReceiver =
                            true
                    )
                }

            val sessionId =
                UUID.randomUUID()
                    .toString()
                    .replace(
                        "-",
                        ""
                    )

            val directory =
                Files.createTempDirectory(
                    "xiaovv-cast-$cameraId-"
                )

            val localAddress =
                localAddressFor(
                    deviceAddress
                )

            val mediaUrl =
                "http://" +
                        localAddress +
                        ":" +
                        apiPort +
                        "/cast-media/" +
                        sessionId +
                        "/manifest.mpd"

            val cast =
                ChromeCast(
                    deviceAddress,
                    devicePort
                )

            val session =
                CastSession(
                    id =
                        sessionId,

                    cameraId =
                        cameraId,

                    streamName =
                        streamName,

                    deviceName =
                        deviceName,

                    deviceAddress =
                        deviceAddress,

                    directory =
                        directory,

                    mediaUrl =
                        mediaUrl,

                    cast =
                        cast
                )

            /*
             * Le Chromecast doit pouvoir atteindre /cast-media/... dès
             * l'instant où la commande LOAD est envoyée.
             */
            sessionsByCamera[
                cameraId
            ] =
                session

            try {

                startEncoder(
                    session
                )

                waitForPlaylist(
                    session
                )

                loadMedia(
                    session,
                    forceLaunch =
                        true
                )

                /*
                 * Important : la session est publiée dans sessionsByCamera
                 * AVANT LOAD pour que le Chromecast puisse déjà accéder à
                 * /cast-media/..., mais le watchdog ne doit pas la juger
                 * tant que ce premier LOAD n'est pas terminé.
                 */
                session.ready.set(
                    true
                )

                log.info(
                    "[{}] cast démarré vers {}",
                    cameraId,
                    deviceName
                )

                session.info()

            } catch (e: Exception) {

                sessionsByCamera.remove(
                    cameraId,
                    session
                )

                stopSession(
                    session,
                    stopReceiver =
                        false
                )

                throw IllegalStateException(
                    "Impossible de démarrer le cast vers '$deviceName' : " +
                            (
                                    e.message
                                        ?: e.javaClass.simpleName
                                    ),
                    e
                )
            }
        }
    }

    fun stopCast(
        cameraId: String
    ): Boolean {

        return lock.withLock {

            val session =
                sessionsByCamera.remove(
                    cameraId
                )
                    ?: return@withLock false

            stopSession(
                session,
                stopReceiver =
                    true
            )

            log.info(
                "[{}] cast arrêté",
                cameraId
            )

            true
        }
    }

    fun handleMedia(
        exchange: HttpExchange
    ) {

        val path =
            exchange.requestURI
                .path
                .trim('/')

        val segments =
            path.split('/')
                .filter {
                    it.isNotBlank()
                }

        if (
            segments.size != 3 ||
            segments[0] != "cast-media"
        ) {

            sendMediaNotFound(
                exchange
            )

            return
        }

        val sessionId =
            segments[1]

        val fileName =
            segments[2]

        if (
            fileName != "manifest.mpd" &&
            !fileName.matches(
                Regex(
                    """init-stream\d+\.m4s"""
                )
            ) &&
            !fileName.matches(
                Regex(
                    """chunk-stream\d+-\d+\.m4s"""
                )
            )
        ) {

            sendMediaNotFound(
                exchange
            )

            return
        }

        val session =
            sessionsByCamera
                .values
                .firstOrNull {
                    it.id ==
                            sessionId
                }

        if (
            session == null ||
            session.stopped.get()
        ) {

            sendMediaNotFound(
                exchange
            )

            return
        }

        val file =
            session.directory
                .resolve(
                    fileName
                )
                .normalize()

        if (
            !file.startsWith(
                session.directory
            ) ||
            !Files.isRegularFile(
                file
            )
        ) {

            sendMediaNotFound(
                exchange
            )

            return
        }

        val now =
            System.currentTimeMillis()

        /*
         * HEAD peut être utilisé pour sonder un fichier mais ne prouve
         * pas que le receiver le consomme réellement. Le watchdog ne
         * prend donc en compte que les GET.
         */
        if (
            exchange.requestMethod.equals(
                "GET",
                ignoreCase = true
            )
        ) {

            when {

                fileName == "manifest.mpd" -> {

                    session.lastManifestRequestAt =
                        now

                    if (
                        session.manifestSeen.compareAndSet(
                            false,
                            true
                        )
                    ) {

                        log.info(
                            "[{}] manifest DASH demandé par {}",
                            session.cameraId,
                            session.deviceName
                        )
                    }
                }

                fileName.startsWith(
                    "init-stream"
                ) -> {

                    if (
                        session.initSeen.compareAndSet(
                            false,
                            true
                        )
                    ) {

                        log.info(
                            "[{}] initialisation DASH/fMP4 demandée par {}",
                            session.cameraId,
                            session.deviceName
                        )
                    }
                }

                else -> {

                    session.lastSegmentRequestAt =
                        now

                    if (
                        session.segmentSeen.compareAndSet(
                            false,
                            true
                        )
                    ) {

                        log.info(
                            "[{}] premier fragment vidéo DASH demandé par {}",
                            session.cameraId,
                            session.deviceName
                        )
                    }
                }
            }
        }

        val bytes =
            try {

                Files.readAllBytes(
                    file
                )

            } catch (_: Exception) {

                sendMediaNotFound(
                    exchange
                )

                return
            }

        val origin =
            exchange.requestHeaders
                .getFirst(
                    "Origin"
                )
                ?.takeIf {
                    it.isNotBlank()
                }

        /*
         * Google Cast exige un CORS propre pour les flux adaptatifs.
         * On reflète l'Origin lorsque le receiver en envoie un, au lieu
         * d'utiliser "*" qui est explicitement déconseillé pour ce cas.
         */
        exchange.responseHeaders.set(
            "Access-Control-Allow-Origin",
            origin ?: "*"
        )

        exchange.responseHeaders.set(
            "Vary",
            "Origin"
        )

        exchange.responseHeaders.set(
            "Access-Control-Allow-Methods",
            "GET, HEAD, OPTIONS"
        )

        exchange.responseHeaders.set(
            "Access-Control-Allow-Headers",
            "Content-Type, Accept-Encoding, Range, Origin"
        )

        exchange.responseHeaders.set(
            "Access-Control-Expose-Headers",
            "Content-Type, Content-Length, Accept-Ranges, Content-Range"
        )

        exchange.responseHeaders.set(
            "Accept-Ranges",
            "bytes"
        )

        exchange.responseHeaders.set(
            "Content-Type",
            when {

                fileName.endsWith(
                    ".mpd"
                ) ->
                    "application/dash+xml"

                else ->
                    "video/mp4"
            }
        )

        /*
         * Les fragments sont locaux et très courts. On désactive le cache
         * afin qu'un reload du Cast ne puisse jamais réutiliser un ancien
         * seg-xxxx.m4s portant le même nom.
         */
        exchange.responseHeaders.set(
            "Cache-Control",
            "no-store, no-cache, must-revalidate, max-age=0"
        )

        val headOnly =
            exchange.requestMethod.equals(
                "HEAD",
                ignoreCase = true
            )

        val rangeHeader =
            exchange.requestHeaders
                .getFirst(
                    "Range"
                )

        val range =
            parseByteRange(
                rangeHeader,
                bytes.size
            )

        if (
            rangeHeader != null &&
            range == null
        ) {

            exchange.responseHeaders.set(
                "Content-Range",
                "bytes */${bytes.size}"
            )

            exchange.sendResponseHeaders(
                416,
                -1
            )

            exchange.close()

            return
        }

        if (
            range != null
        ) {

            val start =
                range.first

            val end =
                range.last

            val length =
                end -
                        start +
                        1

            exchange.responseHeaders.set(
                "Content-Range",
                "bytes $start-$end/${bytes.size}"
            )

            exchange.responseHeaders.set(
                "Content-Length",
                length.toString()
            )

            if (
                headOnly
            ) {

                exchange.sendResponseHeaders(
                    206,
                    -1
                )

                exchange.close()

            } else {

                exchange.sendResponseHeaders(
                    206,
                    length.toLong()
                )

                exchange.responseBody.use {
                        output ->

                    output.write(
                        bytes,
                        start,
                        length
                    )
                }
            }

        } else {

            exchange.responseHeaders.set(
                "Content-Length",
                bytes.size.toString()
            )

            if (
                headOnly
            ) {

                exchange.sendResponseHeaders(
                    200,
                    -1
                )

                exchange.close()

            } else {

                exchange.sendResponseHeaders(
                    200,
                    bytes.size.toLong()
                )

                exchange.responseBody.use {
                        output ->

                    output.write(
                        bytes
                    )
                }
            }
        }
    }

    private fun watchdog() {

        if (
            !running.get()
        ) {
            return
        }

        sessionsByCamera
            .values
            .toList()
            .forEach { session ->

                if (
                    session.stopped.get()
                ) {
                    return@forEach
                }

                /*
                 * La session est rendue visible avant la fin de startCast()
                 * pour que /cast-media/... soit déjà accessible au receiver.
                 * Ne surtout pas la relancer pendant cette phase.
                 */
                if (
                    !session.ready.get()
                ) {
                    return@forEach
                }

                try {

                    val process =
                        session.process

                    if (
                        process == null ||
                        !process.isAlive
                    ) {

                        log.warn(
                            "[{}] encodeur Cast arrêté, relance",
                            session.cameraId
                        )

                        session.lastManifestRequestAt =
                            0L

                        session.lastSegmentRequestAt =
                            0L

                        session.manifestSeen.set(
                            false
                        )

                        session.segmentSeen.set(
                            false
                        )

                        session.initSeen.set(
                            false
                        )

                        startEncoder(
                            session
                        )

                        waitForPlaylist(
                            session
                        )

                        loadMedia(
                            session,
                            forceLaunch =
                                true
                        )

                        return@forEach
                    }

                    val now =
                        System.currentTimeMillis()

                    /*
                     * Le process FFmpeg peut rester "alive" tout en ne
                     * produisant plus aucun fragment si la timeline RTSP
                     * se dérègle. On surveille donc aussi la création réelle
                     * des .m4s.
                     */
                    val lastGeneratedFragmentAt =
                        latestDashFragmentTimestamp(
                            session.directory
                        )

                    if (
                        lastGeneratedFragmentAt > 0L &&
                        now -
                        lastGeneratedFragmentAt >
                        ENCODER_OUTPUT_STALE_MS
                    ) {

                        val staleSeconds =
                            (
                                    now -
                                            lastGeneratedFragmentAt
                                    ) /
                                    1_000L

                        log.warn(
                            "[{}] encodeur DASH vivant mais aucun nouveau fragment depuis {} s, redémarrage",
                            session.cameraId,
                            staleSeconds
                        )

                        session.lastManifestRequestAt =
                            0L

                        session.lastSegmentRequestAt =
                            0L

                        session.manifestSeen.set(
                            false
                        )

                        session.segmentSeen.set(
                            false
                        )

                        session.initSeen.set(
                            false
                        )

                        startEncoder(
                            session
                        )

                        waitForPlaylist(
                            session
                        )

                        loadMedia(
                            session,
                            forceLaunch =
                                true
                        )

                        return@forEach
                    }

                    val lastSegmentRequest =
                        session.lastSegmentRequestAt

                    val healthy =
                        if (
                            lastSegmentRequest > 0L
                        ) {

                            now -
                                    lastSegmentRequest <=
                                    SEGMENT_REQUEST_STALE_MS

                        } else {

                            /*
                             * Le simple téléchargement de index.m3u8 ne prouve
                             * pas que la vidéo est lue. On attend explicitement
                             * une demande de segment avant de considérer le Cast
                             * comme réellement démarré.
                             */
                            now -
                                    session.lastReloadAt <=
                                    LOAD_GRACE_PERIOD_MS
                        }

                    if (healthy) {
                        return@forEach
                    }

                    if (
                        now -
                        session.lastReloadAt <
                        MIN_RELOAD_INTERVAL_MS
                    ) {
                        return@forEach
                    }

                    val silenceSeconds =
                        if (
                            lastSegmentRequest > 0L
                        ) {
                            (
                                    now -
                                            lastSegmentRequest
                                    ) /
                                    1_000L
                        } else {
                            -1L
                        }

                    if (
                        silenceSeconds >= 0L
                    ) {

                        log.warn(
                            "[{}] plus de fragment vidéo DASH depuis {} s sur {}, relance de sécurité",
                            session.cameraId,
                            silenceSeconds,
                            session.deviceName
                        )

                    } else if (
                        session.lastManifestRequestAt > 0L
                    ) {

                        log.warn(
                            "[{}] {} charge le manifest DASH mais aucun fragment vidéo, relance",
                            session.cameraId,
                            session.deviceName
                        )

                    } else {

                        log.warn(
                            "[{}] {} ne charge même pas le manifest DASH, relance",
                            session.cameraId,
                            session.deviceName
                        )
                    }

                    try {

                        val mediaStatus =
                            session.cast.getMediaStatus()

                        if (
                            mediaStatus != null
                        ) {

                            log.warn(
                                "[{}] état Cast avant relance : state={}, idleReason={}, time={}",
                                session.cameraId,
                                mediaStatus.playerState,
                                mediaStatus.idleReason,
                                mediaStatus.currentTime
                            )
                        }

                    } catch (statusError: Exception) {

                        log.debug(
                            "[{}] MediaStatus Cast indisponible : {}",
                            session.cameraId,
                            statusError.message
                        )
                    }

                    session.lastManifestRequestAt =
                        0L

                    session.lastSegmentRequestAt =
                        0L

                    session.manifestSeen.set(
                        false
                    )

                    session.segmentSeen.set(
                        false
                    )

                    session.initSeen.set(
                        false
                    )

                    loadMedia(
                        session,
                        forceLaunch =
                            true
                    )

                } catch (e: Exception) {

                    val now =
                        System.currentTimeMillis()

                    if (
                        now -
                        session.lastReloadAt <
                        MIN_RELOAD_INTERVAL_MS
                    ) {
                        return@forEach
                    }

                    log.warn(
                        "[{}] contrôle Cast impossible sur {} : {}",
                        session.cameraId,
                        session.deviceName,
                        e.message
                    )

                    try {

                        try {
                            session.cast.disconnect()
                        } catch (_: Exception) {
                        }

                        if (
                            session.process == null ||
                            session.process?.isAlive !=
                            true
                        ) {

                            startEncoder(
                                session
                            )

                            waitForPlaylist(
                                session
                            )
                        }

                        session.lastManifestRequestAt =
                            0L

                        session.lastSegmentRequestAt =
                            0L

                        session.manifestSeen.set(
                            false
                        )

                        session.segmentSeen.set(
                            false
                        )

                        loadMedia(
                            session,
                            forceLaunch =
                                true
                        )

                    } catch (reloadError: Exception) {

                        log.warn(
                            "[{}] relance Cast échouée sur {} : {}",
                            session.cameraId,
                            session.deviceName,
                            reloadError.message
                        )
                    }
                }
            }
    }

    private fun loadMedia(
        session: CastSession,
        forceLaunch: Boolean
    ) {

        if (
            session.stopped.get()
        ) {
            return
        }

        val cast =
            session.cast

        val status =
            cast.getStatus()

        if (
            forceLaunch ||
            !status.isAppRunning(
                DEFAULT_MEDIA_RECEIVER_APP_ID
            )
        ) {

            if (
                !status.isAppRunning(
                    DEFAULT_MEDIA_RECEIVER_APP_ID
                )
            ) {

                cast.launchApp(
                    DEFAULT_MEDIA_RECEIVER_APP_ID
                )
            }
        }

        /*
         * Toute relance repart avec une nouvelle fenêtre de grâce.
         * Les requêtes HLS suivantes prouveront ensuite que l'écran
         * consomme réellement le flux.
         */
        session.lastManifestRequestAt =
            0L

        session.lastSegmentRequestAt =
            0L

        session.manifestSeen.set(
            false
        )

        session.segmentSeen.set(
            false
        )

        /*
         * Très important pour un HLS sans fin : le Default Media Receiver
         * doit savoir explicitement qu'il s'agit d'un flux LIVE.
         *
         * L'ancien overload load(title, ..., url, contentType) envoyait
         * streamType=null et duration=null. Certains Nest Hub acceptaient
         * alors la playlist mais n'entamaient jamais la lecture des segments.
         */
        /*
         * DASH force le chemin Shaka Player côté Google Cast.
         */
        cast.load(
            Media(
                session.mediaUrl,
                "application/dash+xml",
                -1.0,
                Media.StreamType.LIVE
            )
        )

        session.lastReloadAt =
            System.currentTimeMillis()
    }

    private fun startEncoder(
        session: CastSession
    ) {

        if (
            session.stopped.get()
        ) {
            return
        }

        stopEncoder(
            session
        )

        cleanMediaDirectory(
            session.directory
        )

        val ffmpeg =
            ffmpegExecutable()

        val source =
            "rtsp://127.0.0.1:" +
                    rtspPort +
                    "/" +
                    session.streamName

        val manifest =
            session.directory
                .resolve(
                    "manifest.mpd"
                )

        /*
         * FFmpeg travaille dans session.directory : tous les noms du muxer
         * DASH restent relatifs et sont identiques sous Windows/Linux.
         */
        val manifestFileName =
            "manifest.mpd"

        val initSegmentFileName =
            "init-stream${'$'}RepresentationID${'$'}.m4s"

        val mediaSegmentFileName =
            "chunk-stream${'$'}RepresentationID${'$'}-${'$'}Number%05d${'$'}.m4s"

        /*
         * MODE DE COMPATIBILITÉ CAST / DASH :
         *
         * Le Nest Hub atteignait correctement le manifest HLS, init fMP4
         * et premier fragment, puis arrêtait la lecture. On conserve donc
         * le profil vidéo très conservateur mais on remplace HLS par DASH.
         *
         * Flux VIDÉO SEULE :
         * - H.264 Baseline level 3.0
         * - 640x360
         * - 10 i/s
         * - aucun B-frame
         * - GOP / segment de 2 secondes
         * - fenêtre live de 24 secondes
         * - timestamps RTSP remplacés par l'horloge d'arrivée
         * - timeline de sortie reconstruite à 10 fps constants
         *
         * Si ce mode affiche l'image, l'audio sera réintroduit ensuite
         * comme piste/rendition séparée.
         */
        val command =
            listOf(
                ffmpeg,
                "-hide_banner",
                "-loglevel",
                "error",
                "-rtsp_transport",
                "tcp",
                "-fflags",
                "+genpts+discardcorrupt",
                "-use_wallclock_as_timestamps",
                "1",
                "-analyzeduration",
                "500000",
                "-probesize",
                "1000000",
                "-i",
                source,
                "-map",
                "0:v:0",
                "-an",
                "-vf",
                "fps=10,scale=640:360:flags=fast_bilinear,setpts=N/(10*TB)",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-tune",
                "zerolatency",
                "-profile:v",
                "baseline",
                "-level:v",
                "3.0",
                "-pix_fmt",
                "yuv420p",
                "-bf",
                "0",
                "-g",
                "20",
                "-keyint_min",
                "20",
                "-sc_threshold",
                "0",
                "-flags",
                "+cgop",
                "-b:v",
                "900k",
                "-maxrate",
                "1100k",
                "-bufsize",
                "1800k",
                "-f",
                "dash",
                "-seg_duration",
                "2",
                "-window_size",
                "12",
                "-extra_window_size",
                "6",
                "-use_template",
                "1",
                "-use_timeline",
                "1",
                "-adaptation_sets",
                "id=0,streams=v",
                "-init_seg_name",
                initSegmentFileName,
                "-media_seg_name",
                mediaSegmentFileName,
                manifestFileName
            )

        val errorLog =
            session.directory
                .resolve(
                    "ffmpeg-error.log"
                )
                .toFile()

        val process =
            ProcessBuilder(
                command
            )
                .directory(
                    session.directory
                        .toFile()
                )
                .redirectError(
                    ProcessBuilder.Redirect.appendTo(
                        errorLog
                    )
                )
                .redirectOutput(
                    ProcessBuilder.Redirect.DISCARD
                )
                .start()

        session.process =
            process
    }

    private fun waitForPlaylist(
        session: CastSession
    ) {

        val manifest =
            session.directory
                .resolve(
                    "manifest.mpd"
                )

        val deadline =
            System.nanoTime() +
                    TimeUnit.SECONDS.toNanos(
                        10
                    )

        while (
            System.nanoTime() <
            deadline
        ) {

            val hasInitSegment =
                try {

                    Files.list(
                        session.directory
                    )
                        .use { files ->

                            files.anyMatch {
                                    file ->

                                file.fileName
                                    .toString()
                                    .matches(
                                        Regex(
                                            """init-stream\d+\.m4s"""
                                        )
                                    ) &&
                                        Files.size(
                                            file
                                        ) > 0L
                            }
                        }

                } catch (_: Exception) {
                    false
                }

            val hasMediaFragment =
                try {

                    Files.list(
                        session.directory
                    )
                        .use { files ->

                            files.anyMatch {
                                    file ->

                                file.fileName
                                    .toString()
                                    .matches(
                                        Regex(
                                            """chunk-stream\d+-\d+\.m4s"""
                                        )
                                    ) &&
                                        Files.size(
                                            file
                                        ) > 0L
                            }
                        }

                } catch (_: Exception) {
                    false
                }

            if (
                Files.isRegularFile(
                    manifest
                ) &&
                Files.size(
                    manifest
                ) > 0L &&
                hasInitSegment &&
                hasMediaFragment
            ) {
                return
            }

            val process =
                session.process

            if (
                process != null &&
                !process.isAlive
            ) {

                val detail =
                    readEncoderError(
                        session
                    )

                error(
                    "FFmpeg Cast s'est arrêté avant la création du flux" +
                            if (
                                detail.isBlank()
                            ) {
                                "."
                            } else {
                                " : $detail"
                            }
                )
            }

            Thread.sleep(
                150
            )
        }

        val generatedFiles =
            try {

                Files.list(
                    session.directory
                )
                    .use { files ->

                        files
                            .map {
                                it.fileName
                                    .toString()
                            }
                            .sorted()
                            .toList()
                    }
                    .joinToString(
                        ", "
                    )

            } catch (_: Exception) {
                "lecture impossible"
            }

        val ffmpegError =
            readEncoderError(
                session
            )

        error(
            "Le flux Cast DASH n'a pas démarré dans le délai imparti. " +
                    "Fichiers générés : [$generatedFiles]" +
                    if (
                        ffmpegError.isBlank()
                    ) {
                        ""
                    } else {
                        ". FFmpeg : $ffmpegError"
                    }
        )
    }

    private fun stopSession(
        session: CastSession,
        stopReceiver: Boolean
    ) {

        if (
            !session.stopped.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        stopEncoder(
            session
        )

        if (
            stopReceiver
        ) {

            try {

                val status =
                    session.cast.getStatus()

                if (
                    status.isAppRunning(
                        DEFAULT_MEDIA_RECEIVER_APP_ID
                    )
                ) {

                    session.cast.stopApp()
                }

            } catch (_: Exception) {
            }
        }

        try {
            session.cast.disconnect()
        } catch (_: Exception) {
        }

        try {

            Files.walk(
                session.directory
            )
                .sorted(
                    Comparator.reverseOrder()
                )
                .forEach {
                    try {
                        Files.deleteIfExists(
                            it
                        )
                    } catch (_: Exception) {
                    }
                }

        } catch (_: Exception) {
        }
    }

    private fun stopEncoder(
        session: CastSession
    ) {

        val process =
            session.process
                ?: return

        session.process =
            null

        try {

            process.destroy()

            if (
                !process.waitFor(
                    2,
                    TimeUnit.SECONDS
                )
            ) {

                process.destroyForcibly()
            }

        } catch (_: Exception) {

            try {
                process.destroyForcibly()
            } catch (_: Exception) {
            }
        }
    }

    private fun latestDashFragmentTimestamp(
        directory: Path
    ): Long {

        return try {

            var latest =
                0L

            Files.list(
                directory
            )
                .use { files ->

                    files.forEach { file ->

                        val name =
                            file.fileName
                                .toString()

                        if (
                            name.matches(
                                Regex(
                                    """chunk-stream\d+-\d+\.m4s"""
                                )
                            ) &&
                            Files.isRegularFile(
                                file
                            )
                        ) {

                            val timestamp =
                                Files.getLastModifiedTime(
                                    file
                                )
                                    .toMillis()

                            if (
                                timestamp >
                                latest
                            ) {
                                latest =
                                    timestamp
                            }
                        }
                    }
                }

            latest

        } catch (_: Exception) {
            0L
        }
    }

    private fun cleanMediaDirectory(
        directory: Path
    ) {

        try {

            Files.list(
                directory
            )
                .use { files ->

                    files.forEach { file ->

                        val name =
                            file.fileName
                                .toString()

                        if (
                            name == "manifest.mpd" ||
                            name.endsWith(
                                ".tmp"
                            ) ||
                            name.matches(
                                Regex(
                                    """init-stream\d+\.m4s"""
                                )
                            ) ||
                            name.matches(
                                Regex(
                                    """chunk-stream\d+-\d+\.m4s"""
                                )
                            )
                        ) {

                            try {
                                Files.deleteIfExists(
                                    file
                                )
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

        } catch (_: Exception) {
        }
    }

    private fun readEncoderError(
        session: CastSession
    ): String {

        val file =
            session.directory
                .resolve(
                    "ffmpeg-error.log"
                )

        if (
            !Files.isRegularFile(
                file
            )
        ) {
            return ""
        }

        return try {

            Files.readAllLines(
                file
            )
                .takeLast(
                    4
                )
                .joinToString(
                    " "
                )
                .take(
                    600
                )

        } catch (_: Exception) {
            ""
        }
    }

    private fun ffmpegExecutable():
            String {

        return System.getProperty(
            "xiaovv.ffmpeg"
        )
            ?.takeIf {
                it.isNotBlank()
            }
            ?: System.getenv(
                "XIAOVV_FFMPEG"
            )
                ?.takeIf {
                    it.isNotBlank()
                }
            ?: "ffmpeg"
    }

    private fun localAddressFor(
        remoteAddress: String
    ): String {

        /*
         * Permet de sélectionner automatiquement la bonne interface
         * réseau, notamment sur une machine avec Ethernet + Wi-Fi/VPN.
         */
        return DatagramSocket().use {
                socket ->

            socket.connect(
                InetSocketAddress(
                    remoteAddress,
                    8009
                )
            )

            val address =
                socket.localAddress
                    ?.hostAddress

            require(
                !address.isNullOrBlank() &&
                        address !=
                        "0.0.0.0"
            ) {
                "Impossible de déterminer l'adresse LAN du serveur Xiaovv."
            }

            address
        }
    }

    private fun parseByteRange(
        header: String?,
        size: Int
    ): IntRange? {

        if (
            header.isNullOrBlank()
        ) {
            return null
        }

        val match =
            Regex(
                """^bytes=(\d*)-(\d*)$"""
            )
                .matchEntire(
                    header.trim()
                )
                ?: return null

        val rawStart =
            match.groupValues[1]

        val rawEnd =
            match.groupValues[2]

        if (
            rawStart.isBlank() &&
            rawEnd.isBlank()
        ) {
            return null
        }

        val start:
                Int

        val end:
                Int

        if (
            rawStart.isBlank()
        ) {

            val suffixLength =
                rawEnd.toIntOrNull()
                    ?: return null

            if (
                suffixLength <= 0
            ) {
                return null
            }

            start =
                (
                        size -
                                suffixLength
                        )
                    .coerceAtLeast(
                        0
                    )

            end =
                size -
                        1

        } else {

            start =
                rawStart.toIntOrNull()
                    ?: return null

            end =
                if (
                    rawEnd.isBlank()
                ) {
                    size -
                            1
                } else {
                    rawEnd.toIntOrNull()
                        ?: return null
                }
        }

        if (
            start < 0 ||
            start >= size ||
            end < start
        ) {
            return null
        }

        return start..
                end.coerceAtMost(
                    size -
                            1
                )
    }

    private fun sendMediaNotFound(
        exchange: HttpExchange
    ) {

        exchange.sendResponseHeaders(
            404,
            -1
        )

        exchange.close()
    }

    override fun close() {

        if (
            !running.getAndSet(
                false
            )
        ) {
            return
        }

        scheduler.shutdownNow()

        lock.withLock {

            sessionsByCamera
                .values
                .toList()
                .forEach { session ->

                    stopSession(
                        session,
                        stopReceiver =
                            true
                    )
                }

            sessionsByCamera.clear()
        }

        try {
            ChromeCasts.stopDiscovery()
        } catch (_: Exception) {
        }

        log.info(
            "Gestionnaire Google Cast arrêté"
        )
    }

    private class CastSession(
        val id: String,
        val cameraId: String,
        val streamName: String,
        val deviceName: String,
        val deviceAddress: String,
        val directory: Path,
        val mediaUrl: String,
        val cast: ChromeCast
    ) {

        val stopped =
            AtomicBoolean(
                false
            )

        /*
         * La session doit être insérée dans la map avant le LOAD pour
         * servir le HLS, mais le watchdog ne peut la surveiller qu'une
         * fois le démarrage complètement terminé.
         */
        val ready =
            AtomicBoolean(
                false
            )

        val manifestSeen =
            AtomicBoolean(
                false
            )

        val segmentSeen =
            AtomicBoolean(
                false
            )

        val initSeen =
            AtomicBoolean(
                false
            )

        @Volatile
        var lastManifestRequestAt:
                Long =
            0L

        @Volatile
        var lastSegmentRequestAt:
                Long =
            0L

        @Volatile
        var process:
                Process? =
            null

        @Volatile
        var lastReloadAt:
                Long =
            0L

        fun info():
                CastSessionInfo {

            return CastSessionInfo(
                cameraId =
                    cameraId,

                deviceName =
                    deviceName,

                deviceAddress =
                    deviceAddress,

                active =
                    !stopped.get()
            )
        }
    }
}
