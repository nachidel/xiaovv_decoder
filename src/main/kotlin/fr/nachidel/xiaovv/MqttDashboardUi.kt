package fr.nachidel.xiaovv

import java.nio.charset.StandardCharsets

/**
 * Charge l'extension JavaScript du dashboard depuis les resources.
 */
object MqttDashboardUi {

    private val script: String by lazy {
        val input =
            MqttDashboardUi::class.java
                .getResourceAsStream(
                    "/mqtt-dashboard.js"
                )
                ?: error(
                    "Resource /mqtt-dashboard.js introuvable."
                )

        input.use {
            String(
                it.readAllBytes(),
                StandardCharsets.UTF_8
            )
        }
    }

    fun javascript(): String =
        script
}
