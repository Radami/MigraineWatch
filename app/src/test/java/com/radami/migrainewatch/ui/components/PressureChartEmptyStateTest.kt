package com.radami.migrainewatch.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.domain.ChartStep
import com.radami.migrainewatch.domain.ChartWindow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Checks the empty-state stand-in is actually composed and laid out in the chart's place.
 * The logic behind it is covered elsewhere without a canvas; this needs a real render.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureChartEmptyStateTest {

    private companion object {
        const val EMPTY_MESSAGE = "No readings in this range"
        const val HOUR = 3600L

        /** Chart width < card width, so a stand-in ignoring its modifier would come out wider. */
        val CARD_WIDTH = 300.dp
        val CHART_WIDTH = 200.dp

        val NOW: Instant = Instant.parse("2026-08-22T14:37:00Z")
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Hourly readings that stop a full day before the window opens. */
    private fun staleReadings(): List<PressureReading> = (24..48).map { hoursAgo ->
        val at = Instant.ofEpochSecond(NOW.epochSecond - hoursAgo * HOUR)
        PressureReading(at, 1013f, 1003f, NOW)
    }.reversed()

    private fun setChart(readings: List<PressureReading>) {
        composeTestRule.setContent {
            Box(Modifier.width(CARD_WIDTH)) {
                PressureChart(
                    readings = readings,
                    window = ChartWindow.around(NOW, ChartStep.ThreeHours),
                    modifier = Modifier.width(CHART_WIDTH)
                ) {
                    // Fills its container, so measured width reveals which one it landed in.
                    Text(EMPTY_MESSAGE, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    /** Used to draw nothing here, which read as a broken chart rather than missing data. */
    @Test
    fun `readings that do not reach the window put the stand-in on screen`() {
        setChart(staleReadings())

        composeTestRule.onNodeWithText(EMPTY_MESSAGE).assertExists()
    }

    /** The stand-in must honor the modifier passed to the chart on every render path. */
    @Test
    fun `the stand-in is laid out under the modifier the chart was given`() {
        setChart(staleReadings())

        composeTestRule.onNodeWithText(EMPTY_MESSAGE).assertWidthIsEqualTo(CHART_WIDTH)
    }

    @Test
    fun `readings that reach the window draw the chart instead`() {
        val covering = (-12..12).map { hours ->
            val at = Instant.ofEpochSecond(NOW.epochSecond + hours * HOUR)
            PressureReading(at, 1013f + hours, 1003f + hours, NOW)
        }

        setChart(covering)

        composeTestRule.onNodeWithText(EMPTY_MESSAGE).assertDoesNotExist()
    }
}
