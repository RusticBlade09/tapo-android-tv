package za.munnik.tapoatv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Auto-delete of saved files: oldest first, until ours are under the limit and the box has room; never the newest. */
class TrimTest {
    private val mb = 1L shl 20
    private fun n(sizesMb: List<Long>, freeMb: Long) = SavedFiles.howMany(sizesMb.map { it * mb }, freeMb * mb, 500 * mb, 300 * mb)

    @Test fun underLimits() = assertEquals(0, n(listOf(100, 100, 100), 1000))
    @Test fun tooMuchOfOurs() = assertEquals(1, n(listOf(200, 150, 100, 100), 1000)) // 550 MB → 350 MB after the oldest
    @Test fun boxLow() = assertEquals(2, n(listOf(50, 60, 70, 80), 200)) // 200 → 250 → 310 free
    @Test fun keepsNewest() = assertEquals(2, n(listOf(10, 10, 10), 0))
    @Test fun nothingSaved() = assertEquals(0, n(emptyList(), 0))
    @Test fun timeFromName() = assertEquals("2026-11-11_19-35-45",
        SavedFiles.stamp().format(java.util.Date(SavedFiles.timeOf("CAMERA 1_2026-11-11_19-35-45.mp4")!! * 1000)))
    @Test fun noTimeInName() = assertEquals(null, SavedFiles.timeOf("holiday.jpg"))
    @Test fun cameraName() = assertEquals("CAMERA 1", SavedFiles.camOf("CAMERA 1_2026-11-11_19-35-45.ts"))
}
