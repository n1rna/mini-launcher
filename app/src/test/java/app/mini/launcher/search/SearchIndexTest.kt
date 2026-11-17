package app.mini.launcher.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchIndexTest {

    private fun index(vararg labels: String) = SearchIndex(labels.associateWith { it })

    @Test fun `ub is ambiguous, tub opens YouTube`() {
        val idx = index("YouTube", "Uber", "Maps")
        assertEquals(setOf("YouTube", "Uber"), idx.search("ub").toSet())
        assertNull(idx.resolve("ub"))
        assertEquals("YouTube", idx.resolve("tub"))
    }

    @Test fun `hint is the shortest unique substring, preferring the label start`() {
        val idx = index("YouTube", "Uber", "Maps")
        assertEquals("y", idx.hint("YouTube"))   // 'y' is unique and at the start
        assertEquals("m", idx.hint("Maps"))
        assertEquals("r", idx.hint("Uber"))      // u, b, e are shared with YouTube; 'r' is unique
    }

    @Test fun `hint prefers word starts over mid-word`() {
        val idx = index("Play Store", "Plex")
        assertEquals("s", idx.hint("Play Store"))
        assertEquals("x", idx.hint("Plex"))
    }

    @Test fun `hint avoids substrings spanning two words when an in-word one exists`() {
        val idx = index("Google Maps", "Gmail", "Google Photos", "Google Play Store")
        assertEquals("ap", idx.hint("Google Maps"))   // not "em" (googlE Maps)
        assertEquals("h", idx.hint("Google Photos"))
    }

    @Test fun `full label opens app even if it is contained in another (exact rule)`() {
        val idx = index("Maps", "Google Maps")
        assertEquals("Maps", idx.resolve("maps"))
        assertEquals("maps", idx.hint("Maps"))
        assertEquals("g", idx.hint("Google Maps"))
    }

    @Test fun `exact rule does not fire when another app continues past the query`() {
        val idx = index("Chrome", "Chrome Beta")
        assertNull(idx.resolve("chrome"))
        assertNull(idx.hint("Chrome"))
        assertEquals("b", idx.hint("Chrome Beta"))
    }

    @Test fun `duplicate labels never auto-launch`() {
        val idx = SearchIndex(mapOf("a" to "Phone", "b" to "Phone", "c" to "Camera"))
        assertNull(idx.hint("a"))
        assertNull(idx.resolve("phone"))
    }

    @Test fun `typed hint never opens another app on the way`() {
        val labels = listOf("YouTube", "YouTube Music", "Uber", "Telegram", "Teams", "Tether", "Twitter")
        val idx = index(*labels.toTypedArray())
        for (l in labels) {
            val h = idx.hint(l) ?: continue
            for (j in 1 until h.length) {
                val r = idx.resolve(h.substring(0, j))
                assert(r == null) { "typing '$h' for $l opened $r at '${h.substring(0, j)}'" }
            }
            assertEquals(l, idx.resolve(h))
        }
    }

    @Test fun `normalization ignores case, accents, spaces and punctuation`() {
        val idx = index("Café Délice", "Wallet")
        assertEquals("Café Délice", idx.resolve("cafede"))
    }

    @Test fun `ranking prefers prefix, then word start, then usage`() {
        val idx = index("Teams", "Hotels", "Outlook", "Tetris")
        assertEquals(listOf("Teams", "Tetris", "Hotels"), idx.search("te"))
        val byUsage = idx.search("te") { if (it == "Tetris") 10.0 else 0.0 }
        assertEquals(listOf("Tetris", "Teams", "Hotels"), byUsage)
    }

    @Test fun `match range maps back to the original label`() {
        val idx = index("Google Maps")
        assertEquals(7..9, idx.matchRange("Google Maps", "map"))
    }
}
