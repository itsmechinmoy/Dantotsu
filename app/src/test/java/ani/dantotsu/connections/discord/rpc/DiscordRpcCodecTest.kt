package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class DiscordRpcCodecTest {

    private fun parse(frame: String): JsonObject =
        Json.parseToJsonElement(frame).jsonObject

    private fun presence(name: String = "My Anime") = DiscordPresence(name)

    private fun DiscordPresence(name: String) = DiscordPresence(name = name, type = DiscordActivityType.WATCHING)

    @Test
    fun setActivity_encodesWatchingTypeAndNumericAppId() {
        val obj = parse(DiscordRpcCodec.encodeSetActivity(presence("My Anime"), 1234, 1163925779692912771L))
        assertEquals("SET_ACTIVITY", obj["cmd"]?.jsonPrimitive?.content)
        val args = obj["args"]!!.jsonObject
        assertEquals(1234, args["pid"]?.jsonPrimitive?.int)
        val act = args["activity"]!!.jsonObject
        assertEquals(1163925779692912771L, act["application_id"]?.jsonPrimitive?.long)
        assertEquals("My Anime", act["name"]?.jsonPrimitive?.content)
        assertEquals(3, act["type"]?.jsonPrimitive?.int)
        assertNotNull(obj["nonce"]?.jsonPrimitive?.content)
    }

    @Test
    fun setActivity_omitsNullOptionals() {
        val act = parse(DiscordRpcCodec.encodeSetActivity(presence("X"), 1, 1L))["args"]!!.jsonObject["activity"]!!.jsonObject
        assertFalse(act.containsKey("details"))
        assertFalse(act.containsKey("state"))
        assertFalse(act.containsKey("timestamps"))
        assertFalse(act.containsKey("assets"))
        assertFalse(act.containsKey("buttons"))
    }

    @Test
    fun setActivity_limitsButtonsToTwo() {
        val p = DiscordPresence(
            name = "X",
            buttons = listOf(
                DiscordPresenceButton("A", "https://a.example"),
                DiscordPresenceButton("B", "https://b.example"),
                DiscordPresenceButton("C", "https://c.example"),
            ).take(2),
        )
        val act = parse(DiscordRpcCodec.encodeSetActivity(p, 1, 1L))["args"]!!.jsonObject["activity"]!!.jsonObject
        assertEquals(2, act["buttons"]!!.jsonArray.size)
    }

    @Test
    fun clear_encodesNullActivity() {
        val obj = parse(DiscordRpcCodec.encodeClear(99))
        assertEquals("SET_ACTIVITY", obj["cmd"]?.jsonPrimitive?.content)
        val args = obj["args"]!!.jsonObject
        assertTrue(args["activity"] is JsonNull)
        assertEquals(99, args["pid"]?.jsonPrimitive?.int)
    }

    @Test
    fun inbound_setActivityResponse_isActivityResponse() {
        val f = DiscordRpcCodec.parseInbound("""{"cmd":"SET_ACTIVITY","nonce":"abc"}""")
        assertTrue(f.isActivityResponse)
        assertEquals("abc", f.nonce)
        assertFalse(f.isReady)
        assertFalse(f.isError)
    }

    @Test
    fun inbound_setActivityError_isErrorNotActivityResponse() {
        // A rejected SET_ACTIVITY (cmd=SET_ACTIVITY, evt=ERROR, nonce) must classify as an ERROR and
        // NOT as a success activity response, so the controller cannot mis-ACK a rejection.
        val f = DiscordRpcCodec.parseInbound(
            """{"cmd":"SET_ACTIVITY","evt":"ERROR","nonce":"abc","data":{"code":123,"message":"x"}}"""
        )
        assertTrue(f.isError)
        assertFalse(f.isActivityResponse)
        assertEquals(123, f.errorCode)
    }

    @Test
    fun inbound_ready() {
        val f = DiscordRpcCodec.parseInbound("""{"evt":"READY","cmd":"DISPATCH","data":{}}""")
        assertTrue(f.isReady)
        assertFalse(f.isActivityResponse)
    }

    @Test
    fun inbound_errorBounded() {
        val big = "x".repeat(500)
        val f = DiscordRpcCodec.parseInbound("""{"evt":"ERROR","data":{"code":123,"message":"$big"}}""")
        assertTrue(f.isError)
        assertEquals(123, f.errorCode)
        assertTrue((f.errorMessage?.length ?: 0) <= 200)
    }

    @Test
    fun neverEmitsTokenFields() {
        val frame = DiscordRpcCodec.encodeSetActivity(presence("X"), 1, 1L)
        assertFalse(frame.contains("token", ignoreCase = true))
        assertFalse(frame.contains("authorization", ignoreCase = true))
    }

    // ── Field bounds + escaping ──────────────────────────────────────

    @Test
    fun details_truncatedToBounds() {
        val long = "d".repeat(300)
        val p = DiscordPresence(name = "X", details = long)
        val act = parse(DiscordRpcCodec.encodeSetActivity(p, 1, 1L))["args"]!!.jsonObject["activity"]!!.jsonObject
        val out = act["details"]?.jsonPrimitive?.content ?: ""
        assertTrue("len=${out.length}", out.length <= DiscordRpcCodec.DETAILS_MAX)
        assertTrue(out.startsWith("d".repeat(10)))
    }

    @Test
    fun name_truncatedToBounds() {
        val long = "n".repeat(500)
        val act = parse(DiscordRpcCodec.encodeSetActivity(DiscordPresence(long), 1, 1L))["args"]!!.jsonObject["activity"]!!.jsonObject
        val out = act["name"]?.jsonPrimitive?.content ?: ""
        assertTrue("len=${out.length}", out.length <= DiscordRpcCodec.NAME_MAX)
    }

    @Test
    fun escaping_quotesBackslashNewlineEmojiPreservedExceptControl() {
        val raw = """He said "hi"\ backslash"""
        val withNewline = "$raw\nemoji😀"
        val p = DiscordPresence(name = withNewline)
        val frame = DiscordRpcCodec.encodeSetActivity(p, 1, 1L)
        // Reparse: kotlinx escaped the string, so the decoded value equals the sanitized input
        // (control char / newline stripped, quotes/backslash/emoji preserved).
        val act = parse(frame)["args"]!!.jsonObject["activity"]!!.jsonObject
        val out = act["name"]?.jsonPrimitive?.content ?: ""
        assertEquals(withNewline.replace("\n", ""), out)
    }

    // ── Malformed inbound safety ──────────────────────────────────

    @Test
    fun malformed_nonJson_doesNotThrow() {
        val f = DiscordRpcCodec.parseInbound("this is not json")
        assertEquals(DiscordRpcCodec.InboundFrame.EMPTY, f)
    }

    @Test
    fun malformed_topLevelArray_doesNotThrow() {
        val f = DiscordRpcCodec.parseInbound("[1,2,3]")
        assertEquals(DiscordRpcCodec.InboundFrame.EMPTY, f)
    }

    @Test
    fun malformed_cmdAsObject_doesNotThrow() {
        val f = DiscordRpcCodec.parseInbound("""{"cmd":{"x":1},"evt":"READY"}""")
        assertTrue(f.isReady)
        assertNull(f.command)
    }

    @Test
    fun malformed_evtAsArray_doesNotThrow() {
        val f = DiscordRpcCodec.parseInbound("""{"evt":[],"cmd":"SET_ACTIVITY"}""")
        assertTrue(f.isActivityResponse)
        assertNull(f.event)
    }

    @Test
    fun malformed_nonceAsObject_doesNotThrow() {
        val f = DiscordRpcCodec.parseInbound("""{"nonce":{"a":1},"cmd":"SET_ACTIVITY"}""")
        assertTrue(f.isActivityResponse)
        assertNull(f.nonce)
    }

    @Test
    fun malformed_errorDataNull_doesNotThrow() {
        val f = DiscordRpcCodec.parseInbound("""{"evt":"ERROR","data":null}""")
        assertTrue(f.isError)
        assertNull(f.errorCode)
        assertNull(f.errorMessage)
    }

    @Test
    fun malformed_errorDataString_doesNotThrow() {
        val f = DiscordRpcCodec.parseInbound("""{"evt":"ERROR","data":"boom"}""")
        assertTrue(f.isError)
        assertNull(f.errorCode)
    }

    @Test
    fun malformed_errorCodeStringAndMessageObject_doesNotThrow() {
        val f = DiscordRpcCodec.parseInbound("""{"evt":"ERROR","data":{"code":"x","message":{"a":1}}}""")
        assertTrue(f.isError)
        assertNull(f.errorCode)
        assertNull(f.errorMessage)
    }

    // ── inbound log strings are control-sanitized, not just truncated ────────

    @Test
    fun inbound_nonceControlChars_areStripped() {
        val f = DiscordRpcCodec.parseInbound("""{"cmd":"SET_ACTIVITY","nonce":"a\nb\tc"}""")
        assertEquals("abc", f.nonce)
        assertFalse(f.nonce!!.contains("\n"))
    }

    @Test
    fun inbound_errorMessage_controlCharsStripped_andBounded() {
        val big = "x".repeat(500)
        val f = DiscordRpcCodec.parseInbound("""{"evt":"ERROR","data":{"code":1,"message":"line1\nline2 $big"}}""")
        assertTrue((f.errorMessage?.length ?: 0) <= 200)
        assertFalse(f.errorMessage!!.contains("\n"))
    }

    // ── required name never falls back to unsanitized input ──────────────────

    @Test
    fun nameOnlyControlChars_usesFallback() {
        val act = parse(DiscordRpcCodec.encodeSetActivity(DiscordPresence(name = "\u0007\u0001"), 1, 1L))["args"]!!.jsonObject["activity"]!!.jsonObject
        assertEquals(DiscordRpcCodec.FALLBACK_NAME, act["name"]?.jsonPrimitive?.content)
    }

    // ── code-point-safe truncation preserves surrogate pairs ─────────────────

    @Test
    fun truncation_preservesSurrogatePairAtBoundary() {
        val emoji = "\uD83D\uDE00" // 😀 (single code point, 2 UTF-16 units)
        val long = emoji + "x".repeat(200)
        val act = parse(DiscordRpcCodec.encodeSetActivity(DiscordPresence(name = long), 1, 1L))["args"]!!.jsonObject["activity"]!!.jsonObject
        val out = act["name"]!!.jsonPrimitive.content
        assertTrue("out=$out", out.startsWith(emoji)) // leading emoji survived
        assertTrue("cp=${out.codePointCount(0, out.length)}", out.codePointCount(0, out.length) <= DiscordRpcCodec.NAME_MAX)
    }
}
