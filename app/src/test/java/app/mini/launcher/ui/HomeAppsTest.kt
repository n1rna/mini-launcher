package app.mini.launcher.ui

import app.mini.launcher.settings.Settings
import app.mini.launcher.settings.SortMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAppsTest {
    private val apps = listOf("Camera", "Chrome", "Maps", "Spotify", "Telegram", "X") // alphabetical, like the repo
    private val scores = mapOf("X" to 9.0, "Telegram" to 7.0, "Spotify" to 5.0, "Maps" to 1.0)
    private fun pick(s: Settings) = pickHomeApps(apps, { it }, { it }, scores, s)

    @Test fun `pinned come first in user order and use up slots`() {
        val r = pick(Settings(homeAppCount = 4, pinned = listOf("Maps", "Camera")))
        assertEquals(listOf("Maps", "Camera"), r.pinned)
        assertEquals(listOf("X", "Telegram"), r.auto)
    }

    @Test fun `pinned apps are not duplicated in the most-used part`() {
        val r = pick(Settings(homeAppCount = 3, pinned = listOf("X")))
        assertEquals(listOf("Telegram", "Spotify"), r.auto)
    }

    @Test fun `more pins than slots shows all pins and no auto apps`() {
        val r = pick(Settings(homeAppCount = 2, pinned = listOf("Maps", "Camera", "Chrome")))
        assertEquals(3, r.pinned.size)
        assertEquals(emptyList<String>(), r.auto)
    }

    @Test fun `uninstalled pins are skipped`() {
        val r = pick(Settings(homeAppCount = 3, pinned = listOf("Gone", "Maps")))
        assertEquals(listOf("Maps"), r.pinned)
        assertEquals(listOf("X", "Telegram"), r.auto)
    }

    @Test fun `no history pads alphabetically`() {
        val r = pickHomeApps(apps, { it }, { it }, emptyMap(), Settings(homeAppCount = 2))
        assertEquals(listOf("Camera", "Chrome"), r.auto)
    }

    @Test fun `A-Z mode sorts only the auto part`() {
        val r = pick(Settings(homeAppCount = 4, pinned = listOf("Spotify"), sortMode = SortMode.MOST_USED_AZ))
        assertEquals(listOf("Spotify"), r.pinned)
        assertEquals(listOf("Maps", "Telegram", "X"), r.auto)
    }
}
