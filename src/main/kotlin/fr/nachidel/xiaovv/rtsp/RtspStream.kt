package fr.nachidel.xiaovv.rtsp

import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.v380.V380MediaDecoder
import fr.nachidel.xiaovv.v380.V380Stream
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import fr.nachidel.xiaovv.h265.H265AnnexB

class RtspStream(
    val name: String,
    val fps: Int = 20
) : V380Stream.FrameListener, Closeable {

    private val log =
        logger<RtspStream>()

    private val active =
        AtomicBoolean(true)

    /*
     * ============================================================
     * LISTENERS RTSP
     * ============================================================
     */

    fun interface AccessUnitListener {

        fun onAccessUnit(
            frame: V380MediaDecoder.DecodedVideoFrame,
            nals: List<H265AnnexB.NalUnit>
        )
    }

    private val listeners =
        CopyOnWriteArrayList<AccessUnitListener>()

    /*
     * ============================================================
     * VPS / SPS / PPS
     * ============================================================
     */

    private val parameterLock =
        Any()

    private var cachedVps: ByteArray? =
        null

    private var cachedSps: ByteArray? =
        null

    private var cachedPps: ByteArray? =
        null

    /*
     * ============================================================
     * FRAMES V380
     * ============================================================
     */

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
                    "Erreur listener RTSP '{}' : {}",
                    name,
                    e.message
                )
            }
        }
    }

    /*
     * ============================================================
     * PARAMETER SETS
     * ============================================================
     */

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
     * SDP
     * ============================================================
     */

    fun buildSdp(
        serverIp: String
    ): String {

        val vps: ByteArray?
        val sps: ByteArray?
        val pps: ByteArray?

        synchronized(parameterLock) {

            vps =
                cachedVps?.copyOf()

            sps =
                cachedSps?.copyOf()

            pps =
                cachedPps?.copyOf()
        }

        val base64 =
            Base64.getEncoder()

        return buildString {

            append("v=0\r\n")

            append(
                "o=- 0 0 IN IP4 $serverIp\r\n"
            )

            append(
                "s=Xiaovv $name\r\n"
            )

            append(
                "c=IN IP4 $serverIp\r\n"
            )

            append(
                "t=0 0\r\n"
            )

            append(
                "a=control:*\r\n"
            )

            append(
                "m=video 0 RTP/AVP 96\r\n"
            )

            append(
                "a=rtpmap:96 H265/90000\r\n"
            )

            append(
                "a=framerate:$fps\r\n"
            )

            if (
                vps != null &&
                sps != null &&
                pps != null
            ) {

                append(
                    "a=fmtp:96 "
                )

                append(
                    "sprop-vps=${base64.encodeToString(vps)};"
                )

                append(
                    "sprop-sps=${base64.encodeToString(sps)};"
                )

                append(
                    "sprop-pps=${base64.encodeToString(pps)}\r\n"
                )
            }

            append(
                "a=control:trackID=0\r\n"
            )
        }
    }

    /*
     * ============================================================
     * LISTENERS
     * ============================================================
     */

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

    fun listenerCount(): Int {

        return listeners.size
    }

    override fun close() {

        if (
            !active.getAndSet(false)
        ) {
            return
        }

        listeners.clear()

        synchronized(parameterLock) {

            cachedVps = null
            cachedSps = null
            cachedPps = null
        }
    }
}