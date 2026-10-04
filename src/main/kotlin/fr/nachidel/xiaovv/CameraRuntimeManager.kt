package fr.nachidel.xiaovv

import fr.nachidel.xiaovv.camera.CameraSupervisor
import fr.nachidel.xiaovv.logging.logger
import fr.nachidel.xiaovv.rtsp.RtspServer
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class CameraRuntimeReloadResult(
    val added: List<String>,
    val updated: List<String>,
    val removed: List<String>,
    val unchanged: List<String>
)

class CameraRuntimeManager(
    private val rtspServer: RtspServer
) : Closeable {

    private val log =
        logger<CameraRuntimeManager>()

    private val lock =
        ReentrantLock()

    private val supervisors =
        ConcurrentHashMap<String, CameraSupervisor>()

    fun initialize(
        cameras: Collection<CameraConfig>
    ) {

        lock.withLock {

            check(
                supervisors.isEmpty()
            ) {
                "Le gestionnaire des caméras est déjà initialisé"
            }

            for (
            camera in
            cameras
            ) {
                addCamera(
                    camera
                )
            }
        }
    }

    fun startAll() {

        snapshot().forEach { supervisor ->
            supervisor.start()
        }
    }

    fun snapshot():
            List<CameraSupervisor> {

        return supervisors
            .values
            .sortedBy {
                it.config.id
            }
    }

    fun get(
        cameraId: String
    ): CameraSupervisor? {
        return supervisors[cameraId]
    }

    fun reload(
        desiredCameras: Collection<CameraConfig>
    ): CameraRuntimeReloadResult {

        return lock.withLock {

            val desiredById =
                desiredCameras.associateBy {
                    it.id
                }

            require(
                desiredById.size ==
                        desiredCameras.size
            ) {
                "Plusieurs caméras utilisent le même identifiant"
            }

            val currentById =
                supervisors.toMap()

            val unchanged =
                currentById
                    .filter { (id, supervisor) ->

                        desiredById[id] ==
                                supervisor.config
                    }
                    .keys
                    .sorted()

            val changed =
                currentById
                    .keys
                    .intersect(
                        desiredById.keys
                    )
                    .filter { id ->

                        desiredById[id] !=
                                currentById[id]?.config
                    }
                    .sorted()

            val removed =
                currentById
                    .keys
                    .minus(
                        desiredById.keys
                    )
                    .sorted()

            val added =
                desiredById
                    .keys
                    .minus(
                        currentById.keys
                    )
                    .sorted()

            /*
             * On retire d'abord les anciennes instances modifiées :
             * cela libère également leur nom de stream RTSP si celui-ci
             * reste identique dans la nouvelle configuration.
             */
            for (
            id in
            removed + changed
            ) {
                removeCamera(
                    id
                )
            }

            for (
            id in
            changed + added
            ) {

                val config =
                    desiredById[id]
                        ?: continue

                addCamera(
                    config
                )

                supervisors[id]
                    ?.start()
            }

            if (
                added.isNotEmpty() ||
                changed.isNotEmpty() ||
                removed.isNotEmpty()
            ) {

                log.info(
                    "Rechargement caméras appliqué : +{} / ~{} / -{}",
                    added.size,
                    changed.size,
                    removed.size
                )
            }

            CameraRuntimeReloadResult(
                added =
                    added,

                updated =
                    changed,

                removed =
                    removed,

                unchanged =
                    unchanged
            )
        }
    }

    private fun addCamera(
        camera: CameraConfig
    ) {

        check(
            supervisors[camera.id] == null
        ) {
            "La caméra '${camera.id}' existe déjà"
        }

        val stream =
            rtspServer.createStream(
                name =
                    camera.streamName,
                cameraId = camera.id
            )

        try {

            val supervisor =
                CameraSupervisor(
                    config =
                        camera,

                    rtspStream =
                        stream
                )

            val previous =
                supervisors.putIfAbsent(
                    camera.id,
                    supervisor
                )

            check(
                previous == null
            ) {
                "La caméra '${camera.id}' existe déjà"
            }

        } catch (e: Exception) {

            try {
                rtspServer.removeStream(
                    camera.streamName
                )
            } catch (_: Exception) {
            }

            throw e
        }
    }

    private fun removeCamera(
        cameraId: String
    ) {

        val supervisor =
            supervisors.remove(
                cameraId
            )
                ?: return

        val streamName =
            supervisor.config.streamName

        try {
            supervisor.close()
        } catch (e: Exception) {

            log.warn(
                "[{}] erreur pendant l'arrêt du superviseur : {}",
                cameraId,
                e.message
            )
        }

        try {
            rtspServer.removeStream(
                streamName
            )
        } catch (e: Exception) {

            log.warn(
                "[{}] erreur pendant la suppression du stream RTSP /{} : {}",
                cameraId,
                streamName,
                e.message
            )
        }
    }

    override fun close() {

        lock.withLock {

            supervisors
                .keys
                .toList()
                .forEach { id ->
                    removeCamera(
                        id
                    )
                }
        }
    }
}
