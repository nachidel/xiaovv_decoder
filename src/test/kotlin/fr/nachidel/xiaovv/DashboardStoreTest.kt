package fr.nachidel.xiaovv

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DashboardStoreTest {
    private val admin = UserAccount("admin", UserRole.ADMIN)
    private val alice = UserAccount("alice", UserRole.USER)
    private val bob = UserAccount("bob.test", UserRole.USER)
    private fun store() = DashboardStore(File(Files.createTempDirectory("xiaovv-dashboard-test-").toFile(), "dashboard.properties"))
    private fun button(name: String, scope: String = "personal") = mapOf("kind" to "action", "name" to name,
        "scope" to scope, "url" to "http://127.0.0.1/action?key=fixture-secret", "method" to "POST")

    @Test fun `les elements communs et personnels persistent avec leur proprietaire`() {
        val store = store()
        val common = store.put(admin, button("Éclairage", "common"))
        val personal = store.put(alice, button("Personnel"))
        store.saveLayout("bob.test", mapOf("sizes" to "{\"test\":{\"width\":250}}", "hidden" to common.id))
        store.saveLayout("bob", mapOf("hidden" to personal.id))
        val loaded = DashboardStore(store.file)
        assertEquals(setOf(common.id, personal.id), loaded.list("alice").map { it.id }.toSet())
        assertEquals(listOf(common.id), loaded.list("bob.test").map { it.id })
        assertEquals(common.id, loaded.layout("bob.test")["hidden"])
        assertEquals(personal.id, loaded.layout("bob")["hidden"])
        assertEquals(1, loaded.layout("bob").size)
        assertFalse(common.toJson(alice).contains("fixture-secret"))
        assertTrue(common.toJson(admin).contains("fixture-secret"))
        assertFailsWith<DashboardForbidden> { loaded.put(alice, button("Intrus", "common")) }
        assertFailsWith<DashboardForbidden> { loaded.put(bob, button("Intrus") + mapOf("id" to personal.id, "revision" to personal.revision.toString())) }
        assertFailsWith<DashboardForbidden> { loaded.action(bob, personal.id) }
        assertEquals(common.id, loaded.action(bob, common.id).id)
    }

    @Test fun `copie deplacement conflit et import repetable`() {
        val store = store()
        val personal = store.put(alice, button("À transférer") + ("importId" to "ancienne-tuile"))
        assertEquals(personal.id, store.put(alice, button("À transférer") + ("importId" to "ancienne-tuile")).id)
        val copy = store.transfer(alice, personal.id, bob.username, "copy", personal.revision)
        assertNotEquals(personal.id, copy.id)
        assertEquals(1, store.list("alice").size)
        assertEquals(1, store.list(bob.username).size)
        val move = store.transfer(alice, personal.id, bob.username, "move", personal.revision)
        assertEquals(personal.id, move.id)
        assertTrue(store.list("alice").isEmpty())
        assertEquals(2, store.list(bob.username).size)
        assertFailsWith<DashboardForbidden> { store.delete(alice, move.id, move.revision) }
        assertFailsWith<DashboardConflict> { store.delete(bob, move.id, personal.revision) }
        val reimport = store.put(alice, button("À transférer") + ("importId" to "ancienne-tuile"))
        assertNotEquals(move.id, reimport.id)
        assertEquals("alice", reimport.owner)
        assertFailsWith<IllegalArgumentException> { store.put(alice, button("Invalide") + ("url" to "file:///private")) }
        store.delete(bob, move.id, move.revision)
        store.removeUser(bob.username)
        assertTrue(DashboardStore(store.file).list(bob.username).isEmpty())
    }
}
