package fr.nachidel.xiaovv.rtsp

import fr.nachidel.xiaovv.h265.H265AnnexB
import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.v380.V380MediaDecoder
import fr.nachidel.xiaovv.v380.V380Stream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class RtspStream(
    val name: String,
    val fps: Int = 20
) : V380Stream.FrameListener,
    V380Stream.AudioFrameListener,
    Closeable {

    private val log =
        logger<RtspStream>()

    private val active =
        AtomicBoolean(true)

    companion object {
        const val VIDEO_PAYLOAD_TYPE = 96
        const val AUDIO_PAYLOAD_TYPE = 97

        private const val DEFAULT_AUDIO_SAMPLE_RATE = 16_000
        private const val DEFAULT_AUDIO_CHANNELS = 1

        /*
         * AAC LC / 16 kHz / mono : AudioSpecificConfig = 0x1408.
         * Valeur confirmée par la capture Xiaovv.
         */
        private val DEFAULT_AUDIO_CONFIG =
            byteArrayOf(
                0x14,
                0x08
            )

        private const val SNAPSHOT_INTERVAL_MS = 10_000L
        private const val SNAPSHOT_FFMPEG_TIMEOUT_SECONDS = 5L
        private const val SNAPSHOT_WIDTH = 640
    }

    fun interface AccessUnitListener {

        fun onAccessUnit(
            frame: V380MediaDecoder.DecodedVideoFrame,
            nals: List<H265AnnexB.NalUnit>
        )
    }

    fun interface AudioAccessUnitListener {

        fun onAudioAccessUnit(
            frame: V380MediaDecoder.DecodedAudioFrame
        )
    }

    /**
     * Notifie le superviseur quand le nombre de sessions RTSP
     * réellement en PLAY change.
     */
    fun interface DemandListener {

        fun onDemandChanged(
            consumers: Int
        )
    }

    private val listeners =
        CopyOnWriteArrayList<AccessUnitListener>()

    private val audioListeners =
        CopyOnWriteArrayList<AudioAccessUnitListener>()

    private val demandListeners =
        CopyOnWriteArrayList<DemandListener>()

    private val consumers =
        AtomicInteger(0)

    private val latestSnapshot =
        AtomicReference<ByteArray?>()

    private val latestSnapshotTimestamp =
        AtomicLong(0L)

    private val lastSnapshotAttempt =
        AtomicLong(0L)

    private val snapshotInProgress =
        AtomicBoolean(false)

    private val snapshotEnabled =
        AtomicBoolean(true)

    private val snapshotFailureLogged =
        AtomicBoolean(false)

    private val snapshotExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "xiaovv-snapshot-$name").apply {
                isDaemon = true
            }
        }

    private val snapshotFile: Path =
        Path.of(
            System.getProperty("user.home"),
            ".xiaovv",
            "snapshots",
            name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jpg"
        )

    init {
        loadPersistedSnapshot()
    }

    private val parameterLock =
        Any()

    private var cachedVps: ByteArray? =
        null

    private var cachedSps: ByteArray? =
        null

    private var cachedPps: ByteArray? =
        null

    private var audioSampleRate =
        DEFAULT_AUDIO_SAMPLE_RATE

    private var audioChannels =
        DEFAULT_AUDIO_CHANNELS

    private var audioObjectType =
        2

    private var audioSpecificConfig =
        DEFAULT_AUDIO_CONFIG.copyOf()

    override fun onFrame(
        frame: V380MediaDecoder.DecodedVideoFrame
    ) {

        if (!active.get()) {
            return
        }

        val nals =
            H265AnnexB.split(
                frame.payload
            )

        if (nals.isEmpty()) {
            return
        }

        updateParameterSets(
            nals
        )

        scheduleSnapshot(
            frame,
            nals
        )

        for (listener in listeners) {

            try {

                listener.onAccessUnit(
                    frame,
                    nals
                )

            } catch (e: Exception) {

                log.warn(
                    "Erreur listener vidéo RTSP '{}' : {}",
                    name,
                    e.message
                )
            }
        }
    }

    override fun onAudioFrame(
        frame: V380MediaDecoder.DecodedAudioFrame
    ) {

        if (!active.get()) {
            return
        }

        updateAudioParameters(
            frame
        )

        for (listener in audioListeners) {

            try {

                listener.onAudioAccessUnit(
                    frame
                )

            } catch (e: Exception) {

                log.warn(
                    "Erreur listener audio RTSP '{}' : {}",
                    name,
                    e.message
                )
            }
        }
    }

    private fun updateParameterSets(
        nals: List<H265AnnexB.NalUnit>
    ) {

        synchronized(parameterLock) {

            for (nal in nals) {

                when (nal.type) {

                    H265AnnexB.NAL_VPS -> {

                        if (
                            cachedVps == null ||
                            !cachedVps!!.contentEquals(
                                nal.data
                            )
                        ) {

                            cachedVps =
                                nal.data.copyOf()

                            log.info(
                                "[{}] H265 VPS détecté : {} octets",
                                name,
                                nal.data.size
                            )
                        }
                    }

                    H265AnnexB.NAL_SPS -> {

                        if (
                            cachedSps == null ||
                            !cachedSps!!.contentEquals(
                                nal.data
                            )
                        ) {

                            cachedSps =
                                nal.data.copyOf()

                            log.info(
                                "[{}] H265 SPS détecté : {} octets",
                                name,
                                nal.data.size
                            )
                        }
                    }

                    H265AnnexB.NAL_PPS -> {

                        if (
                            cachedPps == null ||
                            !cachedPps!!.contentEquals(
                                nal.data
                            )
                        ) {

                            cachedPps =
                                nal.data.copyOf()

                            log.info(
                                "[{}] H265 PPS détecté : {} octets",
                                name,
                                nal.data.size
                            )
                        }
                    }
                }
            }
        }
    }

    /*
     * ============================================================
     * DERNIÈRE IMAGE / MINIATURE
     * ============================================================
     */
    private fun scheduleSnapshot(
        frame: V380MediaDecoder.DecodedVideoFrame,
        nals: List<H265AnnexB.NalUnit>
    ) {

        if (!snapshotEnabled.get()) {
            return
        }

        val keyFrame =
            frame.keyFrame ||
                    nals.any {
                        it.isKeyFrameNal
                    }

        if (!keyFrame) {
            return
        }

        val now =
            System.currentTimeMillis()

        val previousAttempt =
            lastSnapshotAttempt.get()

        if (
            previousAttempt > 0L &&
            now - previousAttempt < SNAPSHOT_INTERVAL_MS
        ) {
            return
        }

        if (
            !snapshotInProgress.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        lastSnapshotAttempt.set(now)

        val hevc =
            buildSnapshotHevc(nals)

        if (hevc == null) {
            snapshotInProgress.set(false)
            return
        }

        snapshotExecutor.execute {

            try {

                val jpeg =
                    convertHevcToJpeg(hevc)

                if (
                    jpeg != null &&
                    jpeg.isNotEmpty()
                ) {

                    val timestamp =
                        System.currentTimeMillis()

                    latestSnapshot.set(jpeg)
                    latestSnapshotTimestamp.set(timestamp)

                    persistSnapshot(
                        jpeg,
                        timestamp
                    )
                }

            } catch (e: Exception) {

                logSnapshotFailure(e)

            } finally {

                snapshotInProgress.set(false)
            }
        }
    }

    private fun buildSnapshotHevc(
        nals: List<H265AnnexB.NalUnit>
    ): ByteArray? {

        val vps: ByteArray?
        val sps: ByteArray?
        val pps: ByteArray?

        synchronized(parameterLock) {
            vps = cachedVps?.copyOf()
            sps = cachedSps?.copyOf()
            pps = cachedPps?.copyOf()
        }

        if (vps == null || sps == null || pps == null) {
            return null
        }

        val output =
            ByteArrayOutputStream()

        fun writeNal(data: ByteArray) {
            output.write(byteArrayOf(0x00, 0x00, 0x00, 0x01))
            output.write(data)
        }

        writeNal(vps)
        writeNal(sps)
        writeNal(pps)

        for (nal in nals) {

            if (
                nal.type == H265AnnexB.NAL_VPS ||
                nal.type == H265AnnexB.NAL_SPS ||
                nal.type == H265AnnexB.NAL_PPS
            ) {
                continue
            }

            writeNal(nal.data)
        }

        return output.toByteArray()
    }

    private fun convertHevcToJpeg(
        hevc: ByteArray
    ): ByteArray? {

        val ffmpeg =
            System.getProperty("xiaovv.ffmpeg")
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv("XIAOVV_FFMPEG")
                    ?.takeIf { it.isNotBlank() }
                ?: "ffmpeg"

        val inputFile =
            Files.createTempFile(
                "xiaovv-${name}-",
                ".h265"
            )

        val outputFile =
            Files.createTempFile(
                "xiaovv-${name}-",
                ".jpg"
            )

        try {

            Files.write(inputFile, hevc)

            val process =
                try {
                    ProcessBuilder(
                        ffmpeg,
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-f",
                        "hevc",
                        "-i",
                        inputFile.toString(),
                        "-frames:v",
                        "1",
                        "-vf",
                        "scale=$SNAPSHOT_WIDTH:-2",
                        "-q:v",
                        "4",
                        outputFile.toString()
                    )
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                } catch (e: Exception) {
                    snapshotEnabled.set(false)
                    throw e
                }

            val finished =
                process.waitFor(
                    SNAPSHOT_FFMPEG_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )

            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException(
                    "timeout FFmpeg pendant la génération de la miniature"
                )
            }

            if (process.exitValue() != 0) {
                throw IllegalStateException(
                    "FFmpeg a terminé avec le code ${process.exitValue()}"
                )
            }

            if (
                !Files.exists(outputFile) ||
                Files.size(outputFile) <= 0L
            ) {
                throw IllegalStateException(
                    "FFmpeg n'a produit aucune miniature"
                )
            }

            return Files.readAllBytes(outputFile)

        } finally {
            try {
                Files.deleteIfExists(inputFile)
            } catch (_: Exception) {
            }
            try {
                Files.deleteIfExists(outputFile)
            } catch (_: Exception) {
            }
        }
    }

    private fun loadPersistedSnapshot() {

        try {

            if (
                Files.exists(snapshotFile) &&
                Files.size(snapshotFile) > 0L
            ) {

                latestSnapshot.set(
                    Files.readAllBytes(snapshotFile)
                )

                latestSnapshotTimestamp.set(
                    Files.getLastModifiedTime(snapshotFile)
                        .toMillis()
                )
            }

        } catch (e: Exception) {

            log.debug(
                "[{}] impossible de relire la dernière miniature : {}",
                name,
                e.message
            )
        }
    }

    private fun persistSnapshot(
        jpeg: ByteArray,
        timestamp: Long
    ) {

        try {

            val parent =
                snapshotFile.parent

            Files.createDirectories(parent)

            val temp =
                Files.createTempFile(
                    parent,
                    name.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-",
                    ".jpg.tmp"
                )

            try {

                Files.write(temp, jpeg)

                try {

                    Files.move(
                        temp,
                        snapshotFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                    )

                } catch (_: Exception) {

                    Files.move(
                        temp,
                        snapshotFile,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }

                Files.setLastModifiedTime(
                    snapshotFile,
                    java.nio.file.attribute.FileTime.fromMillis(timestamp)
                )

            } finally {

                try {
                    Files.deleteIfExists(temp)
                } catch (_: Exception) {
                }
            }

        } catch (e: Exception) {

            log.debug(
                "[{}] impossible d'enregistrer la dernière miniature : {}",
                name,
                e.message
            )
        }
    }

    private fun logSnapshotFailure(
        error: Exception
    ) {

        if (
            snapshotFailureLogged.compareAndSet(
                false,
                true
            )
        ) {
            log.warn(
                "[{}] miniature JPEG indisponible : {}. " +
                        "Installe FFmpeg ou définis XIAOVV_FFMPEG / -Dxiaovv.ffmpeg.",
                name,
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    fun hasSnapshot(): Boolean {
        return latestSnapshot.get() != null
    }

    fun getLatestSnapshot(): ByteArray? {
        return latestSnapshot.get()?.copyOf()
    }

    fun getLatestSnapshotTimestamp(): Long {
        return latestSnapshotTimestamp.get()
    }

    private fun updateAudioParameters(
        frame: V380MediaDecoder.DecodedAudioFrame
    ) {

        synchronized(parameterLock) {

            val changed =
                audioSampleRate != frame.sampleRate ||
                        audioChannels != frame.channels ||
                        audioObjectType != frame.audioObjectType ||
                        !audioSpecificConfig.contentEquals(
                            frame.audioSpecificConfig
                        )

            audioSampleRate =
                frame.sampleRate

            audioChannels =
                frame.channels

            audioObjectType =
                frame.audioObjectType

            audioSpecificConfig =
                frame.audioSpecificConfig.copyOf()

            if (changed) {

                log.info(
                    "[{}] AAC détecté : objectType={}, {} Hz, {} canal(aux), config={}",
                    name,
                    audioObjectType,
                    audioSampleRate,
                    audioChannels,
                    audioSpecificConfig.joinToString("") {
                        "%02X".format(
                            it.toInt() and 0xFF
                        )
                    }
                )
            }
        }
    }

    fun buildSdp(
        serverIp: String
    ): String {

        val vps: ByteArray?
        val sps: ByteArray?
        val pps: ByteArray?
        val sampleRate: Int
        val channels: Int
        val configHex: String

        synchronized(parameterLock) {

            vps =
                cachedVps?.copyOf()

            sps =
                cachedSps?.copyOf()

            pps =
                cachedPps?.copyOf()

            sampleRate =
                audioSampleRate

            channels =
                audioChannels

            configHex =
                audioSpecificConfig.joinToString("") {
                    "%02X".format(
                        it.toInt() and 0xFF
                    )
                }
        }

        val base64 =
            Base64.getEncoder()

        return buildString {

            append("v=0\r\n")
            append("o=- 0 0 IN IP4 $serverIp\r\n")
            append("s=Xiaovv $name\r\n")
            append("c=IN IP4 $serverIp\r\n")
            append("t=0 0\r\n")
            append("a=control:*\r\n")

            append("m=video 0 RTP/AVP $VIDEO_PAYLOAD_TYPE\r\n")
            append("a=rtpmap:$VIDEO_PAYLOAD_TYPE H265/90000\r\n")
            append("a=framerate:$fps\r\n")

            if (
                vps != null &&
                sps != null &&
                pps != null
            ) {

                append("a=fmtp:$VIDEO_PAYLOAD_TYPE ")
                append("sprop-vps=${base64.encodeToString(vps)};")
                append("sprop-sps=${base64.encodeToString(sps)};")
                append("sprop-pps=${base64.encodeToString(pps)}\r\n")
            }

            append("a=control:trackID=0\r\n")

            /*
             * RFC 3640 : AAC MPEG4-GENERIC, un Access Unit par paquet RTP.
             */
            append("m=audio 0 RTP/AVP $AUDIO_PAYLOAD_TYPE\r\n")
            append(
                "a=rtpmap:$AUDIO_PAYLOAD_TYPE " +
                        "MPEG4-GENERIC/$sampleRate/$channels\r\n"
            )
            append(
                "a=fmtp:$AUDIO_PAYLOAD_TYPE " +
                        "streamtype=5;" +
                        "profile-level-id=1;" +
                        "mode=AAC-hbr;" +
                        "config=$configHex;" +
                        "sizelength=13;" +
                        "indexlength=3;" +
                        "indexdeltalength=3\r\n"
            )
            append("a=control:trackID=1\r\n")
        }
    }

    fun addListener(
        listener: AccessUnitListener
    ) {

        listeners.addIfAbsent(
            listener
        )
    }

    fun removeListener(
        listener: AccessUnitListener
    ) {

        listeners.remove(
            listener
        )
    }

    fun addAudioListener(
        listener: AudioAccessUnitListener
    ) {

        audioListeners.addIfAbsent(
            listener
        )
    }

    fun removeAudioListener(
        listener: AudioAccessUnitListener
    ) {

        audioListeners.remove(
            listener
        )
    }

    fun listenerCount(): Int {
        return listeners.size
    }

    fun audioListenerCount(): Int {
        return audioListeners.size
    }

    fun consumerCount(): Int {
        return consumers.get()
    }

    fun addDemandListener(
        listener: DemandListener
    ) {

        demandListeners.addIfAbsent(
            listener
        )

        /*
         * Donne immédiatement l'état courant au nouveau listener.
         */
        listener.onDemandChanged(
            consumers.get()
        )
    }

    fun removeDemandListener(
        listener: DemandListener
    ) {

        demandListeners.remove(
            listener
        )
    }

    /**
     * Appelé par RtspServer uniquement lorsqu'une session passe
     * réellement en PLAY. Un SETUP ou un DESCRIBE seul ne démarre
     * donc pas la caméra.
     */
    internal fun acquireConsumer() {

        if (!active.get()) {
            return
        }

        val count =
            consumers.incrementAndGet()

        if (count == 1) {

            log.info(
                "[{}] demande RTSP active",
                name
            )

        } else {

            log.debug(
                "[{}] {} consommateurs RTSP",
                name,
                count
            )
        }

        notifyDemandChanged(
            count
        )
    }

    /**
     * Libère une session PLAY. Le compteur ne peut jamais devenir
     * négatif, même si un client ferme brutalement sa socket.
     */
    internal fun releaseConsumer() {

        while (true) {

            val current =
                consumers.get()

            if (current <= 0) {
                return
            }

            val next =
                current - 1

            if (
                consumers.compareAndSet(
                    current,
                    next
                )
            ) {

                if (next == 0) {

                    log.info(
                        "[{}] aucun consommateur RTSP",
                        name
                    )

                } else {

                    log.debug(
                        "[{}] {} consommateur(s) RTSP restant(s)",
                        name,
                        next
                    )
                }

                notifyDemandChanged(
                    next
                )

                return
            }
        }
    }

    private fun notifyDemandChanged(
        count: Int
    ) {

        for (listener in demandListeners) {

            try {

                listener.onDemandChanged(
                    count
                )

            } catch (e: Exception) {

                log.warn(
                    "Erreur listener de demande RTSP '{}' : {}",
                    name,
                    e.message
                )
            }
        }
    }

    override fun close() {

        if (!active.getAndSet(false)) {
            return
        }

        listeners.clear()
        audioListeners.clear()

        val previousConsumers =
            consumers.getAndSet(0)

        if (previousConsumers > 0) {
            notifyDemandChanged(0)
        }

        demandListeners.clear()

        snapshotExecutor.shutdownNow()

        synchronized(parameterLock) {
            cachedVps = null
            cachedSps = null
            cachedPps = null
        }
    }
}
