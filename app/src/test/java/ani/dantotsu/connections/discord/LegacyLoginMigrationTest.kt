package ani.dantotsu.connections.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyLoginMigrationTest {

    private class FakeStore(override val hadLegacyLogin: Boolean, var presence: Boolean) : LegacyLoginMigration.Store {
        override var migrated = false
        override fun enablePresence() { presence = true }
    }

    @Test
    fun legacyLoginTurnsPresenceOn() {
        val store = FakeStore(hadLegacyLogin = true, presence = false)
        LegacyLoginMigration.run(store)
        assertTrue(store.presence)
        assertTrue(store.migrated)
    }

    @Test
    fun noLegacyLoginLeavesPresenceAlone() {
        val store = FakeStore(hadLegacyLogin = false, presence = false)
        LegacyLoginMigration.run(store)
        assertEquals(false, store.presence)
        assertTrue(store.migrated)
    }

    @Test
    fun secondRunDoesNothing() {
        val store = FakeStore(hadLegacyLogin = true, presence = false)
        LegacyLoginMigration.run(store)
        store.presence = false // user turned it off afterwards
        LegacyLoginMigration.run(store)
        assertEquals(false, store.presence)
    }
}
