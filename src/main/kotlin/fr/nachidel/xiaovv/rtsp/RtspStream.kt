package fr.nachidel.xiaovv.rtsp

import fr.nachidel.xiaovv.h265.H265AnnexB
import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.v380.V380MediaDecoder
import fr.nachidel.xiaovv.v380.V380Stream
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

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

    private val listeners =
        CopyOnWriteArrayList<AccessUnitListener>()

    private val audioListeners =
        CopyOnWriteArrayList<AudioAccessUnitListener>()

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

    override fun close() {

        if (!active.getAndSet(false)) {
            return
        }

        listeners.clear()
        audioListeners.clear()

        synchronized(parameterLock) {
            cachedVps = null
            cachedSps = null
            cachedPps = null
        }
    }
}
