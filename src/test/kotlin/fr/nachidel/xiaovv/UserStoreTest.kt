package fr.nachidel.xiaovv

import java.nio.file.Files
import kotlin.test.*

class UserStoreTest {
    private val password = "un mot de passe de test"
    private fun store() = UserStore(Files.createTempDirectory("xiaovv-users-test-").resolve("users.properties").toFile())

    @Test fun `les comptes et permissions persistent sans mot de passe en clair`() {
        val users = store()
        users.setup("Admin", password)
        users.create("alice", password, UserRole.USER, setOf("salon"))
        users.create("bob", password, UserRole.USER)
        val content = users.file.readText()
        assertFalse(content.contains(password))
        val loaded = UserStore(users.file)
        assertEquals("admin", loaded.authenticate(" ADMIN ", password)?.username)
        assertNull(loaded.authenticate("alice", "incorrect"))
        assertNull(loaded.authenticate("inconnu", password))
        assertTrue(loaded.account("admin")!!.canUseCamera("nouvelle-camera"))
        assertTrue(loaded.account("alice")!!.canUseCamera("salon"))
        assertFalse(loaded.account("alice")!!.canUseCamera("entree"))
        assertFalse(loaded.account("bob")!!.canUseCamera("salon"))
        val properties = java.util.Properties().apply { users.file.inputStream().use { load(it) } }
        assertNotEquals(properties.getProperty("user.alice").substringAfter(":"), properties.getProperty("user.bob").substringAfter(":"))
    }

    @Test fun `un administrateur est toujours conserve et la mise a jour preserve les droits`() {
        val users = store()
        users.setup("admin", password)
        assertFailsWith<IllegalArgumentException> { users.delete("admin") }
        assertFailsWith<IllegalArgumentException> { users.update("admin", null, UserRole.USER) }
        assertFailsWith<IllegalStateException> { users.setup("autre", password) }
        users.create("alice", password, UserRole.USER, setOf("salon"))
        users.update("alice", "un autre mot de passe", UserRole.USER)
        assertNull(users.authenticate("alice", password))
        assertEquals(setOf("salon"), users.authenticate("alice", "un autre mot de passe")!!.cameras)
        users.update("alice", null, UserRole.USER, setOf("entree"))
        assertEquals(setOf("entree"), UserStore(users.file).account("alice")!!.cameras)
        users.create("autre", password, UserRole.ADMIN)
        users.delete("admin")
        assertNotNull(UserStore(users.file).authenticate("autre", password))
    }

    @Test fun `les comptes invalides et un fichier corrompu sont refuses`() {
        val users = store()
        assertFailsWith<IllegalArgumentException> { users.setup("admin", "court") }
        assertFalse(users.file.exists())
        users.setup("admin", password)
        assertFailsWith<IllegalArgumentException> { users.create("../evil", password, UserRole.USER) }
        assertFailsWith<IllegalArgumentException> { users.create("admin", password, UserRole.USER) }
        assertFailsWith<IllegalArgumentException> { users.create("alice", password, UserRole.USER, setOf("../salon")) }
        users.file.writeText("format=1\nuser.broken=invalid\n")
        assertFailsWith<IllegalArgumentException> { UserStore(users.file) }
        assertTrue(users.file.readText().contains("broken"))
    }
}
