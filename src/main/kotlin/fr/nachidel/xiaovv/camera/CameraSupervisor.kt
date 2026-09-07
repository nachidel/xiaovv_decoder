package fr.nachidel.xiaovv.camera

import fr.nachidel.xiaovv.CameraConfig
import fr.nachidel.xiaovv.CameraResolution
import fr.nachidel.xiaovv.rtsp.RtspStream
import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.v380.V380Client
import fr.nachidel.xiaovv.v380.V380Protocol
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

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

    /*
     * Disponibilité réseau indépendante du flux RTSP.
     *
     * true  = le service V380 répond sur le port TCP configuré ;
     * false = le service V380 n'est actuellement pas joignable.
     */
    private val networkAvailable =
        AtomicBoolean(false)

    private var availabilityThread:
            Thread? =
        null

    private val demandLock =
        ReentrantLock()

    private val demandChanged =
        demandLock.newCondition()

    @Volatile
    private var rtspConsumers =
        0

    private val demandListener =
        RtspStream.DemandListener { consumers ->
            onRtspDemandChanged(consumers)
        }

    private var thread:
            Thread? =
        null

    init {

        rtspStream.addDemandListener(
            demandListener
        )
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

        availabilityThread =
            Thread(
                {
                    availabilityLoop()
                },
                "xiaovv-camera-${config.id}-availability"
            ).apply {

                isDaemon =
                    true

                start()
            }

        log.info(
            "[{}] superviseur démarré (caméra à la demande RTSP)",
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

            /*
             * Aucun client RTSP en PLAY : aucune connexion caméra.
             */
            if (!waitForRtspDemand()) {
                break
            }

            var client: V380Client? =
                null

            try {

                attempt++

                client =
                    V380Client(
                        name =   config.streamName,
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
                    "[{}] connexion à {}:{} (tentative {}, demande RTSP={})",
                    config.id,
                    config.host,
                    config.port,
                    attempt,
                    rtspConsumers
                )

                client.connect()

                networkAvailable.set(
                    true
                )

                /*
                 * La dernière session RTSP peut avoir disparu pendant
                 * l'authentification. Dans ce cas on n'ouvre pas le flux.
                 */
                if (!hasRtspDemand()) {

                    log.debug(
                        "[{}] demande RTSP disparue pendant la connexion",
                        config.id
                    )

                    continue
                }

                client.start()

                log.info(
                    "[{}] caméra connectée : {}x{} @ {} fps",
                    config.id,
                    client.getVideoWidth(),
                    client.getVideoHeight(),
                    client.getVideoFps()
                )

                attempt = 0

                client.await()

                if (
                    running.get() &&
                    hasRtspDemand()
                ) {

                    log.warn(
                        "[{}] flux caméra interrompu - " +
                                "reconnexion automatique",
                        config.id
                    )
                }

            } catch (e: InterruptedException) {

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
                    running.get() &&
                    hasRtspDemand()
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
                hasRtspDemand() &&
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

    /*
     * ============================================================
     * DISPONIBILITÉ RÉSEAU
     * ============================================================
     *
     * Le test n'ouvre PAS de flux vidéo.
     *
     * Lorsque la caméra est en veille, on vérifie périodiquement
     * que son service V380 TCP est joignable. La socket de test est
     * immédiatement refermée.
     *
     * Pendant une connexion/lecture V380 active, aucun test
     * supplémentaire n'est effectué.
     */
    private fun availabilityLoop() {

        var firstCheck =
            true

        while (
            running.get()
        ) {

            /*
             * Ne jamais créer une connexion de sonde en parallèle
             * d'une vraie session V380.
             */
            val client =
                currentClient.get()

            val available =
                if (client != null) {

                    if (
                        client.isStreamConnected()
                    ) {
                        true
                    } else {
                        /*
                         * Connexion/authentification en cours :
                         * on conserve le dernier état connu.
                         */
                        networkAvailable.get()
                    }

                } else {

                    probeCameraAvailability()
                }

            val previous =
                networkAvailable.getAndSet(
                    available
                )

            if (
                firstCheck ||
                previous != available
            ) {

                log.info(
                    "[{}] disponibilité réseau : {}",
                    config.id,
                    if (available) {
                        "EN LIGNE"
                    } else {
                        "INDISPONIBLE"
                    }
                )

                firstCheck =
                    false
            }

            try {

                Thread.sleep(
                    NETWORK_PROBE_INTERVAL_MS
                )

            } catch (_: InterruptedException) {

                Thread.currentThread()
                    .interrupt()

                break
            }
        }
    }

    private fun probeCameraAvailability(): Boolean {

        return try {

            Socket().use { socket ->

                socket.connect(
                    InetSocketAddress(
                        config.host,
                        config.port
                    ),
                    NETWORK_PROBE_TIMEOUT_MS
                )
            }

            true

        } catch (_: Exception) {

            false
        }
    }

    /**
     * Attend le premier PLAY RTSP. Le thread de supervision existe
     * toujours, mais aucune socket vers la caméra n'est ouverte.
     */
    private fun waitForRtspDemand(): Boolean {

        demandLock.lock()

        try {

            while (
                running.get() &&
                rtspConsumers <= 0
            ) {

                try {

                    demandChanged.await()

                } catch (_: InterruptedException) {

                    Thread.currentThread()
                        .interrupt()

                    return false
                }
            }

            return running.get()

        } finally {

            demandLock.unlock()
        }
    }

    private fun hasRtspDemand(): Boolean {
        return rtspConsumers > 0
    }

    /**
     * Un PLAY fait passer le compteur à > 0 : le superviseur se
     * réveille. Quand le dernier lecteur disparaît, on ferme la
     * connexion caméra sans bloquer le thread RTSP du lecteur.
     */
    private fun onRtspDemandChanged(
        consumers: Int
    ) {

        val previous: Int

        demandLock.lock()

        try {

            previous =
                rtspConsumers

            rtspConsumers =
                consumers.coerceAtLeast(0)

            demandChanged.signalAll()

        } finally {

            demandLock.unlock()
        }

        if (
            previous == 0 &&
            consumers > 0
        ) {

            log.info(
                "[{}] activation caméra demandée par RTSP",
                config.id
            )
        }

        if (
            previous > 0 &&
            consumers <= 0
        ) {

            log.info(
                "[{}] plus aucun lecteur RTSP - arrêt de la caméra",
                config.id
            )

            closeCurrentClientAsync()
        }
    }

    private fun closeCurrentClientAsync() {

        val client =
            currentClient.get()
                ?: return

        Thread(
            {

                /*
                 * Un nouveau lecteur a pu arriver entre la notification
                 * et l'exécution de ce thread. Dans ce cas on conserve
                 * la caméra active.
                 */
                if (
                    running.get() &&
                    !hasRtspDemand() &&
                    currentClient.get() === client
                ) {

                    try {
                        client.close()
                    } catch (e: Exception) {

                        log.debug(
                            "[{}] erreur pendant l'arrêt à la demande : {}",
                            config.id,
                            e.message
                        )
                    }
                }
            },
            "xiaovv-camera-${config.id}-on-demand-stop"
        ).apply {

            isDaemon =
                true

            start()
        }
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

            running.get() &&
                    hasRtspDemand()

        } catch (_: InterruptedException) {

            Thread.currentThread()
                .interrupt()

            false
        }
    }

    /*
     * ============================================================
     * ÉTAT
     * ============================================================
     */

    enum class CameraState(
        val apiValue: String
    ) {

        ACTIVE(
            "active"
        ),

        STANDBY(
            "standby"
        ),

        UNAVAILABLE(
            "unavailable"
        )
    }

    fun isRunning(): Boolean {
        return running.get()
    }

    fun isConnected(): Boolean {

        return currentClient
            .get()
            ?.isStreamConnected()
            ?: false
    }

    fun isNetworkAvailable(): Boolean {

        return isConnected() ||
                networkAvailable.get()
    }

    fun getState(): CameraState {

        if (
            isConnected()
        ) {
            return CameraState.ACTIVE
        }

        if (
            networkAvailable.get()
        ) {
            return CameraState.STANDBY
        }

        return CameraState.UNAVAILABLE
    }

    /*
     * ============================================================
     * DERNIÈRE IMAGE
     * ============================================================
     */

    fun hasSnapshot(): Boolean {
        return rtspStream.hasSnapshot()
    }

    fun getLatestSnapshot(): ByteArray? {
        return rtspStream.getLatestSnapshot()
    }

    fun getLatestSnapshotTimestamp(): Long {
        return rtspStream.getLatestSnapshotTimestamp()
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

        rtspStream.removeDemandListener(
            demandListener
        )

        val wasRunning =
            running.getAndSet(
                false
            )

        demandLock.lock()

        try {
            demandChanged.signalAll()
        } finally {
            demandLock.unlock()
        }

        if (!wasRunning) {
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

        availabilityThread
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

        availabilityThread
            ?.let { availability ->

                try {

                    availability.join(
                        2_000
                    )

                } catch (_: InterruptedException) {

                    Thread.currentThread()
                        .interrupt()
                }
            }

        thread =
            null

        availabilityThread =
            null

        currentClient.set(
            null
        )

        log.info(
            "[{}] superviseur arrêté",
            config.id
        )
    }
    private companion object {

        /*
         * 10 secondes : assez réactif pour l'interface sans sonder
         * inutilement les caméras en permanence.
         */
        const val NETWORK_PROBE_INTERVAL_MS =
            10_000L

        /*
         * Le réseau local répond normalement en quelques millisecondes.
         * 800 ms laisse une marge confortable sans bloquer longtemps
         * lorsqu'une caméra est réellement absente.
         */
        const val NETWORK_PROBE_TIMEOUT_MS =
            800
    }

}
