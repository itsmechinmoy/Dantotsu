package ani.dantotsu.settings.saving.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCheckTest {

    private fun entry(type: String, value: String) = """"k":{"type":"$type","value":$value}"""

    private fun backup(vararg entries: String, location: String = "General") =
        """{"$location":{${entries.joinToString(",")}}}"""

    private fun invalid(json: String) = assertTrue(json, BackupCheck.of(json) is BackupCheck.Invalid)

    private fun valid(json: String) = assertEquals(json, BackupCheck.Valid, BackupCheck.of(json))

    @Test
    fun `a normal backup passes`() {
        valid(
            backup(
                entry("kotlin.Boolean", "true"),
                """"i":{"type":"kotlin.Int","value":3}""",
                """"s":{"type":"kotlin.String","value":"x"}""",
                """"f":{"type":"kotlin.Float","value":1.5}""",
                """"l":{"type":"kotlin.Long","value":9223372036854775807}""",
                """"h":{"type":"java.util.HashSet","value":["a","b"]}"""
            )
        )
        valid(backup(entry("kotlin.String", "\"v\""), location = "source_123"))
    }

    @Test
    fun `broken json is rejected`() {
        invalid("")
        invalid("""{"General":{""")
        invalid("""[]""")
        invalid("""{}""")
    }

    @Test
    fun `broken structure is rejected`() {
        invalid("""{"General":[]}""")
        invalid("""{"General":{"k":true}}""")
        invalid("""{"General":{"k":{"type":"kotlin.Boolean"}}}""")
    }

    @Test
    fun `values that don't match their type are rejected`() {
        invalid(backup(entry("java.util.ArrayList", "[]")))
        invalid(backup(entry("kotlin.Boolean", "\"yes\"")))
        invalid(backup(entry("kotlin.Int", "\"3\"")))
        invalid(backup(entry("kotlin.Int", "9999999999")))
        invalid(backup(entry("kotlin.Long", "\"x\"")))
        invalid(backup(entry("kotlin.String", "3")))
        invalid(backup(entry("kotlin.Float", "\"1.5\"")))
        invalid(backup(entry("java.util.HashSet", "\"a\"")))
        invalid(backup(entry("java.util.HashSet", "[1]")))
        invalid(backup(entry("kotlin.String", "null")))
    }

    @Test
    fun `locations removed in an update are skipped, not rejected`() {
        valid("""{"General":{${entry("kotlin.Boolean", "true")}},"OldLocation":{"k":"anything"}}""")
    }
}
