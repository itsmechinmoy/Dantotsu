package ani.dantotsu.media.anime.selector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.regex.Pattern

/**
 * Android compiles java.util.regex with ICU, which rejects a `}` that doesn't close a
 * `{n,m}` quantifier; the JVM reads it as a literal. One such pattern made
 * ReleaseTitleNormalizer fail to initialize on the phone, so every release card fell back to
 * its raw text, while every JVM test passed. This checks every Regex/Pattern constant of the
 * picker classes for that.
 */
class IcuRegexCompatTest {

    /** index of a lone `}` outside a character class, or -1 */
    private fun loneCloseBrace(p: String): Int {
        var i = 0
        var classDepth = 0
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> {
                    // \p{L} / \P{Lu}: a property name, not a quantifier
                    if (i + 2 < p.length && (p[i + 1] == 'p' || p[i + 1] == 'P') && p[i + 2] == '{') {
                        val end = p.indexOf('}', i + 3)
                        i = if (end < 0) p.length else end + 1
                        continue
                    }
                    if (i + 1 < p.length && p[i + 1] == 'Q') {
                        val end = p.indexOf("\\E", i + 2)
                        i = if (end < 0) p.length else end + 2
                        continue
                    }
                    i += 2
                    continue
                }
                classDepth > 0 && c == '[' -> classDepth++
                classDepth > 0 && c == ']' -> classDepth--
                classDepth > 0 -> Unit
                c == '[' -> {
                    classDepth = 1
                    // a ']' right after '[' or '[^' is a literal member
                    var j = i + 1
                    if (j < p.length && p[j] == '^') j++
                    if (j < p.length && p[j] == ']') j++
                    i = j
                    continue
                }
                c == '{' -> {
                    val end = p.indexOf('}', i)
                    if (end > 0 && Regex("""\d+(,\d*)?""").matches(p.substring(i + 1, end))) {
                        i = end + 1
                        continue
                    }
                }
                c == '}' -> return i
            }
            i++
        }
        return -1
    }

    @Test
    fun checker_finds_the_device_failure() {
        // the pattern from the device log (ICU reported "near index 42"): the last '}'
        val device = """\[[\s._|\-]*]|\([\s._|\-]*\)|\{[\s._|\-]*}"""
        assertEquals(device.length - 1, loneCloseBrace(device))
        assertEquals(-1, loneCloseBrace("""\[[\s._|\-]*\]|\([\s._|\-]*\)|\{[\s._|\-]*\}"""))
        assertEquals(-1, loneCloseBrace("""a{2,3}[}{]x{4}\Q}\E"""))
        assertEquals(-1, loneCloseBrace("""[^\p{L}\p{N}]+(?![\p{L}])\P{Lu}"""))
    }

    @Test
    fun picker_patterns_compile_under_icu_rules() {
        val pkg = "ani.dantotsu.media.anime.selector"
        val dir = File("src/main/java/" + pkg.replace('.', '/'))
        assertTrue("source dir not found: ${dir.absolutePath}", dir.isDirectory)
        val bad = mutableListOf<String>()
        var checked = 0
        for (src in dir.listFiles { f -> f.extension == "kt" }!!.sorted()) {
            val cls = runCatching { Class.forName("$pkg.${src.nameWithoutExtension}") }.getOrNull() ?: continue
            for (f in cls.declaredFields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                val pattern = when {
                    f.type == Regex::class.java -> runCatching { f.isAccessible = true; (f.get(null) as? Regex)?.pattern }.getOrNull()
                    f.type == Pattern::class.java -> runCatching { f.isAccessible = true; (f.get(null) as? Pattern)?.pattern() }.getOrNull()
                    else -> null
                } ?: continue
                checked++
                val at = loneCloseBrace(pattern)
                if (at >= 0) bad += "${cls.simpleName}.${f.name} index $at: $pattern"
            }
        }
        assertTrue("no patterns found", checked > 50)
        assertTrue("ICU rejects a lone '}':\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
