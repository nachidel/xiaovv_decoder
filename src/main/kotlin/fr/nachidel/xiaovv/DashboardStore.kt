package fr.nachidel.xiaovv

import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.UUID

internal class DashboardForbidden : RuntimeException("Accès à cet élément non autorisé")
internal class DashboardMissing : RuntimeException("Élément introuvable")
internal class DashboardConflict : RuntimeException("Cet élément a été modifié ; actualiser la liste avant de réessayer")

internal fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { c -> when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\")
        '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    } }
    append('"')
}

data class DashboardItem(val id: String, val kind: String, val owner: String, val revision: Long,
                         val fields: Map<String, String>, val importKey: String = "") {
    val common get() = owner.isEmpty()
    fun visibleTo(user: UserAccount) = common || owner == user.username || user.role == UserRole.ADMIN
    fun editableBy(user: UserAccount) = user.role == UserRole.ADMIN || (!common && owner == user.username)
    fun toJson(user: UserAccount): String {
        // Une URL commune peut contenir une clé Jeedom : l'utilisateur reçoit uniquement l'ID exécutable.
        val visibleFields = if (editableBy(user)) fields else fields - "url"
        val values = visibleFields.map { (key, value) -> "${jsonString(key)}:${jsonString(value)}" }
        return "{" + listOf("\"id\":${jsonString(id)}", "\"kind\":${jsonString(kind)}",
            "\"scope\":${jsonString(if (common) "common" else "personal")}", "\"owner\":${jsonString(owner)}",
            "\"revision\":$revision", "\"editable\":${editableBy(user)}").plus(values).joinToString(",") + "}"
    }
}

/** Définitions communes/personnelles et disposition par compte, hors du paquet déployé. */
class DashboardStore(val file: File) {
    private var sequence = 0L
    private var items = emptyMap<String, DashboardItem>()
    private var layouts = emptyMap<String, Map<String, String>>()
    init { load() }

    @Synchronized fun list(owner: String) = items.values.filter { it.common || it.owner == owner }.sortedBy { it.revision }
    @Synchronized fun layout(owner: String) = layouts[owner].orEmpty()

    @Synchronized fun put(user: UserAccount, values: Map<String, String>): DashboardItem {
        val id = values["id"].orEmpty()
        val old = if (id.isEmpty()) null else items[id] ?: throw DashboardMissing()
        if (old != null && !old.editableBy(user)) throw DashboardForbidden()
        if (old != null && values["revision"]?.toLongOrNull() != old.revision) throw DashboardConflict()
        val scope = values["scope"] ?: "personal"
        require(scope in setOf("common", "personal")) { "Partage invalide" }
        if (scope == "common" && user.role != UserRole.ADMIN) throw DashboardForbidden()
        val owner = if (scope == "common") "" else values["owner"]?.ifBlank { null } ?: user.username
        if (owner.isNotEmpty()) require(owner.matches(Regex("[a-z0-9][a-z0-9_.-]{2,31}"))) { "Utilisateur invalide" }
        if (owner != user.username && owner.isNotEmpty() && user.role != UserRole.ADMIN) throw DashboardForbidden()
        val kind = values["kind"] ?: old?.kind ?: "action"
        require(kind in setOf("action", "info")) { "Type d'élément invalide" }
        if (old != null) require(kind == old.kind) { "Le type d'élément ne peut pas changer" }
        val fields = validate(kind, values)
        val importId = values["importId"].orEmpty()
        require(importId.length <= 100 && importId.all { it.isLetterOrDigit() || it in "_-" }) { "Identifiant d'import invalide" }
        val importKey = if (importId.isEmpty()) old?.takeIf { it.owner == owner }?.importKey.orEmpty() else "${user.username}:$kind:$importId"
        if (old == null && importKey.isNotEmpty()) items.values.firstOrNull { it.importKey == importKey }?.let { return it }
        require(items.size < 4000 || old != null) { "Nombre maximal d'éléments atteint" }
        require(items.values.count { it.owner == owner } < 200 || old?.owner == owner) { "Nombre maximal d'éléments pour ce tableau atteint" }
        val item = DashboardItem(old?.id ?: UUID.randomUUID().toString(), kind, owner, ++sequence, fields, importKey)
        persist(items + (item.id to item), layouts)
        return item
    }

    @Synchronized fun delete(user: UserAccount, id: String, revision: Long?) {
        val item = items[id] ?: throw DashboardMissing()
        if (!item.editableBy(user)) throw DashboardForbidden()
        if (revision != item.revision) throw DashboardConflict()
        persist(items - id, layouts)
    }

    @Synchronized fun transfer(user: UserAccount, id: String, target: String, mode: String, revision: Long?): DashboardItem {
        val old = items[id] ?: throw DashboardMissing()
        if (!old.editableBy(user)) throw DashboardForbidden()
        if (revision != old.revision) throw DashboardConflict()
        require(mode in setOf("copy", "move")) { "Transfert invalide" }
        require(!(old.common && mode == "move")) { "Un élément commun peut être copié, pas déplacé" }
        require(target.matches(Regex("[a-z0-9][a-z0-9_.-]{2,31}"))) { "Destinataire invalide" }
        require(items.values.count { it.owner == target } < 200) { "Tableau du destinataire plein" }
        require(mode == "move" || items.size < 4000) { "Nombre maximal d'éléments atteint" }
        val item = old.copy(id = if (mode == "copy") UUID.randomUUID().toString() else old.id,
            owner = target, revision = ++sequence, importKey = "")
        // Lors d'un déplacement, les anciennes préférences de masquage ne suivent pas l'élément.
        val updatedLayouts = if (mode == "move") layouts.mapValues { (_, layout) ->
            layout + ("hidden" to layout["hidden"].orEmpty().split(',').filter { it != id }.joinToString(","))
        } else layouts
        persist(items + (item.id to item), updatedLayouts)
        return item
    }

    @Synchronized fun action(user: UserAccount, id: String): DashboardItem {
        val item = items[id] ?: throw DashboardMissing()
        if (!item.visibleTo(user)) throw DashboardForbidden()
        require(item.kind == "action" && item.fields["enabled"] == "true") { "Bouton désactivé ou invalide" }
        return item
    }

    @Synchronized fun saveLayout(owner: String, values: Map<String, String>) {
        val allowed = setOf("selected", "order", "sizes", "wallOnly", "controlOrder", "hidden", "audioVolume", "audioMuted")
        val updated = values.filterKeys { it in allowed }
        require(updated.values.all { it.length <= 32768 } && updated.values.sumOf { it.length } <= 65536) { "Disposition trop volumineuse" }
        persist(items, layouts + (owner to (layouts[owner].orEmpty() + updated)))
    }

    @Synchronized fun removeUser(username: String) {
        if (layouts.containsKey(username) || items.values.any { it.owner == username })
            persist(items.filterValues { it.owner != username }, layouts - username)
    }

    private fun validate(kind: String, values: Map<String, String>): Map<String, String> {
        fun text(key: String, max: Int, default: String = "", required: Boolean = false): String {
            val value = (values[key] ?: default).trim()
            require(value.length <= max && (!required || value.isNotEmpty()) && '\u0000' !in value) { "Champ $key invalide" }
            return value
        }
        val result = mutableMapOf("name" to text("name", 80, required = true), "enabled" to (values["enabled"] ?: "true"))
        require(result["enabled"] in setOf("true", "false")) { "Disponibilité invalide" }
        if (kind == "action") {
            val url = text("url", 4096, required = true)
            val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("URL invalide") }
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "URL HTTP/HTTPS invalide" }
            val method = text("method", 8, "GET").uppercase()
            require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Méthode HTTP invalide" }
            result += mapOf("url" to url, "method" to method)
        } else {
            val server = text("serverId", 64, required = true)
            require(server.matches(Regex("[A-Za-z0-9_-]+"))) { "Serveur MQTT invalide" }
            val topic = text("topic", 512, required = true)
            require('#' !in topic && '+' !in topic) { "Le topic MQTT doit être exact" }
            val type = text("valueType", 10, "number")
            require(type in setOf("number", "text", "boolean")) { "Type de valeur invalide" }
            val decimals = text("decimals", 4, "auto")
            require(decimals in setOf("auto", "0", "1", "2", "3")) { "Décimales invalides" }
            val stale = text("staleSeconds", 12, "300").toLongOrNull()
            require(stale != null && stale in 0..31_536_000) { "Délai de péremption invalide" }
            result += mapOf("serverId" to server, "topic" to topic, "valueType" to type, "decimals" to decimals,
                "staleSeconds" to stale.toString(), "unit" to text("unit", 32), "measureType" to text("measureType", 64),
                "jsonPath" to text("jsonPath", 512))
        }
        return result.toMap()
    }

    private fun load() {
        if (!file.exists()) return
        val props = Properties().apply { file.inputStream().use { load(it) } }
        require(props.getProperty("format") == "1") { "Fichier dashboard invalide" }
        sequence = props.getProperty("sequence", "0").toLong()
        val ids = props.stringPropertyNames().filter { it.startsWith("item.") }.map { it.split('.')[1] }.toSet()
        items = ids.associateWith { id ->
            require(UUID.fromString(id).toString() == id) { "Identifiant dashboard invalide" }
            val prefix = "item.$id."
            val values = props.stringPropertyNames().filter { it.startsWith(prefix + "field.") }
                .associate { it.removePrefix(prefix + "field.") to props.getProperty(it) }
            val kind = props.getProperty(prefix + "kind")
            require(kind in setOf("action", "info")) { "Type dashboard invalide" }
            DashboardItem(id, kind, props.getProperty(prefix + "owner"), props.getProperty(prefix + "revision").toLong(),
                validate(kind, values), props.getProperty(prefix + "import", ""))
        }
        val owners = props.stringPropertyNames().filter { it.startsWith("layout.") }.map { it.removePrefix("layout.").substringBeforeLast('.') }.toSet()
        layouts = owners.associateWith { owner -> props.stringPropertyNames().filter { it.startsWith("layout.") && it.removePrefix("layout.").substringBeforeLast('.') == owner }
            .associate { it.removePrefix("layout.$owner.") to props.getProperty(it) } }
    }

    private fun persist(updated: Map<String, DashboardItem>, updatedLayouts: Map<String, Map<String, String>>) {
        val target = file.absoluteFile.toPath()
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".dashboard-", ".tmp")
        try {
            val props = Properties().apply {
                setProperty("format", "1"); setProperty("sequence", sequence.toString())
                updated.forEach { (id, item) ->
                    val prefix = "item.$id."
                    setProperty(prefix + "kind", item.kind); setProperty(prefix + "owner", item.owner)
                    setProperty(prefix + "revision", item.revision.toString()); setProperty(prefix + "import", item.importKey)
                    item.fields.forEach { (key, value) -> setProperty(prefix + "field.$key", value) }
                }
                updatedLayouts.forEach { (owner, values) -> values.forEach { (key, value) -> setProperty("layout.$owner.$key", value) } }
            }
            Files.newOutputStream(temporary).use { props.store(it, "Xiaovv dashboard") }
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
            items = updated; layouts = updatedLayouts
        } finally { Files.deleteIfExists(temporary) }
    }
}
