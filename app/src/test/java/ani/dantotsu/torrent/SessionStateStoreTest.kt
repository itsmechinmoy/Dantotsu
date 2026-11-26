package ani.dantotsu.torrent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): Pair<SessionStateStore, File> {
        val file = File(tmp.newFolder("state"), "torrent/session.state")
        return SessionStateStore(file) to file
    }

    @Test
    fun saveAndLoadRoundTripsAndOverwritesWithoutTempFile() {
        val (store, file) = newStore()
        val first = byteArrayOf(1, 2, 3, 4)

        assertTrue(store.save(first))
        assertArrayEquals(first, store.load())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())

        val second = byteArrayOf(9, 8, 7)
        assertTrue(store.save(second))
        assertArrayEquals(second, store.load())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun handlesMissingEmptyOversizedAndDiscardedState() {
        val (store, file) = newStore()

        assertNull(store.load())
        assertFalse(store.save(null))
        assertFalse(store.save(ByteArray(0)))
        assertFalse(store.save(ByteArray(SessionStateStore.MAX_BYTES.toInt() + 1)))

        // Empty file on disk is discarded and ignored.
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
        assertNull(store.load())
        assertFalse(file.exists())

        // Explicit discard removes saved state.
        assertTrue(store.save(byteArrayOf(5, 6)))
        store.discard()
        assertNull(store.load())
        assertFalse(file.exists())
    }

    @Test
    fun loadParsedReturnsValueAndDiscardsCorruptInput() {
        val (store, file) = newStore()
        val payload = "d3:dhtd2:id4:testee".toByteArray()

        assertTrue(store.save(payload))
        val parsed = store.loadParsed { String(it) }
        assertEquals("d3:dhtd2:id4:testee", parsed)
        assertTrue(file.exists())

        var sawCorrupt = false
        val failed = store.loadParsed<String>(onCorrupt = { sawCorrupt = true }) {
            throw IllegalStateException("corrupt bencode")
        }
        assertNull(failed)
        assertTrue(sawCorrupt)
        assertFalse(file.exists())
        assertNull(store.load())
    }
}
