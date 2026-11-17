package app.mini.launcher.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperMathTest {

    @Test fun `landscape image is cropped to a centered portrait slice`() {
        // 3840x2160 photo onto a 1280x2856 phone: full height, centered width
        val (l, t, r, b) = centerCrop(3840, 2160, 1280, 2856)
        assertEquals(0, t); assertEquals(2160, b)
        assertEquals(968, r - l)            // 2160 * 1280/2856
        assertEquals(3840 - r, l)           // centered
    }

    @Test fun `tall image is cropped vertically`() {
        val (l, t, r, b) = centerCrop(1000, 4000, 1280, 2856)
        assertEquals(0, l); assertEquals(1000, r)
        assertEquals(2231, b - t)
        assertEquals(884, t)                // (4000 - 2231) / 2, centered
    }

    @Test fun `matching aspect keeps the whole image`() {
        assertEquals(listOf(0, 0, 1280, 2856), centerCrop(1280, 2856, 1280, 2856))
    }

    @Test fun `folder advances one photo per day and wraps`() {
        assertEquals(listOf(0, 1, 2, 0), (30L..33L).map { pickIndex(it, 0, 3) })
        assertEquals(pickIndex(31, 0, 5), pickIndex(30, 1, 5)) // "Change now" = jump to tomorrow's
    }
}
