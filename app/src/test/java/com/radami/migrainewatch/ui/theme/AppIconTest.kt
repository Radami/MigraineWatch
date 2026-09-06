package com.radami.migrainewatch.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * That the app's mark is the same mark wherever it is drawn.
 *
 * It is described three times over and cannot be described once: a vector drawable cannot
 * include another, and the three copies are not interchangeable anyway — the launcher
 * foreground carries the mark in colour with its cream dot, the themed layer needs the stroke
 * alone because the dot silhouettes into a bump at the trough, and the notification icon is
 * refitted to a 24dp canvas with the dot dropped entirely.
 *
 * What the copies do have to agree on is the geometry the two 108dp layers share and the colour
 * the brand is. Those are what "keep the two in step" asked a reader to do by hand, and what
 * this checks instead.
 */
class AppIconTest {

    private companion object {
        const val FOREGROUND = "ic_launcher_foreground.xml"
        const val MONOCHROME = "ic_launcher_monochrome.xml"

        /** The mark's own path, named so the two files can be compared on it by name. */
        const val MARK = "mark"

        val PATH_DATA = Regex("""android:name="(\w+)"\s+android:pathData="([^"]+)"""")
        val STROKE_WIDTH = Regex("""android:strokeWidth="([\d.]+)"""")
        val STROKE_COLOR = Regex("""android:strokeColor="(#[0-9A-Fa-f]+)"""")

        /**
         * Unit tests run from the module directory. Resolved rather than assumed so a build that
         * changes that says so plainly instead of failing on a missing path.
         */
        fun drawable(name: String): String {
            val file = File("src/main/res/drawable/$name")
            assertTrue(
                "Expected to find $name at ${file.absolutePath} — is the test working " +
                    "directory still the app module?",
                file.isFile
            )
            return file.readText()
        }

        fun pathNamed(source: String, name: String): String =
            PATH_DATA.findAll(source).single { it.groupValues[1] == name }.groupValues[2]
    }

    /**
     * The themed layer is the launcher layer's stroke with the colour thrown away, so the two
     * have to trace the same line. Drifting apart would show as a home screen whose icon changes
     * shape when the wallpaper theme is turned on.
     */
    @Test
    fun `the themed icon traces the same mark as the launcher icon`() {
        val foreground = drawable(FOREGROUND)
        val monochrome = drawable(MONOCHROME)

        assertEquals(pathNamed(foreground, MARK), pathNamed(monochrome, MARK))
        assertEquals(
            STROKE_WIDTH.find(foreground)!!.groupValues[1],
            STROKE_WIDTH.find(monochrome)!!.groupValues[1]
        )
    }

    /**
     * The launcher icon is where the brand colour is drawn largest, and play-assets/make_icon.py
     * renders the store listing straight from this file. A wordmark and an app icon in two
     * different terracottas is the kind of drift nobody notices until they are side by side.
     */
    @Test
    fun `the launcher icon is drawn in the brand terracotta`() {
        val declared = STROKE_COLOR.find(drawable(FOREGROUND))!!.groupValues[1]

        val expected = "#%06X".format(Locale.ROOT, BrandTerracottaLight.toArgb() and 0xFFFFFF)
        assertEquals(expected, declared.uppercase(Locale.ROOT))
    }
}
