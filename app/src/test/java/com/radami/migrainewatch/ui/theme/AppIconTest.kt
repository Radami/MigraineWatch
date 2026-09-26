package com.radami.migrainewatch.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Checks the app mark stays consistent across its three separate drawable copies (a vector
 * drawable can't include another). They must agree on the shared 108dp geometry and brand
 * color, which used to be kept in sync by hand.
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

    /** The themed layer is the launcher stroke with color dropped; they must trace the same line. */
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
     * play-assets/make_icon.py renders the store listing straight from this file, so a color
     * drift here would put a mismatched terracotta on the store listing.
     */
    @Test
    fun `the launcher icon is drawn in the brand terracotta`() {
        val declared = STROKE_COLOR.find(drawable(FOREGROUND))!!.groupValues[1]

        val expected = "#%06X".format(Locale.ROOT, BrandTerracottaLight.toArgb() and 0xFFFFFF)
        assertEquals(expected, declared.uppercase(Locale.ROOT))
    }
}
