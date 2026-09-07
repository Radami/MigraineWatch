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
 * What the chart puts in its own place when it has nothing to plot.
 *
 * The decisions behind it are covered by PressureChartTest without a canvas; these two need one,
 * because what is being checked is that the stand-in is actually composed and actually laid out
 * where the chart would have been.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureChartEmptyStateTest {

    private companion object {
        const val EMPTY_MESSAGE = "No readings in this range"
        const val HOUR = 3600L

        /**
         * The chart is given less width than the surrounding card has, so a stand-in that
         * ignored the modifier would be laid out by the card instead and come out wider. Equal
         * widths would let a dropped modifier pass unnoticed.
         */
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
                    // Fills whatever it is put inside, so its measured width reports which
                    // container that turned out to be.
                    Text(EMPTY_MESSAGE, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    /**
     * The card used to draw nothing here — chips over blank space, which reads as a chart that
     * failed to render rather than as data that is missing.
     */
    @Test
    fun `readings that do not reach the window put the stand-in on screen`() {
        setChart(staleReadings())

        composeTestRule.onNodeWithText(EMPTY_MESSAGE).assertExists()
    }

    /**
     * And it takes the width the caller asked the chart for. A composable has to apply the
     * modifier it was handed on every path it can leave by; dropping it on this one leaves the
     * message sitting at its own intrinsic width rather than filling the card.
     */
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
