package com.radami.migrainewatch.data.model

import com.radami.migrainewatch.domain.DeliveredWarning
import com.radami.migrainewatch.domain.PressureDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.time.toKotlinInstant

class NotifiedAlertTest {

    private companion object {
        val START: Instant = Instant.parse("2026-07-25T12:00:00Z")
        val END: Instant = Instant.parse("2026-07-26T08:00:00Z")
    }

    private fun row(direction: String) = NotifiedAlert(
        startDateTime = START,
        endDateTime = END,
        direction = direction,
        thresholdHpa = 6f,
        notifiedDateTime = START
    )

    @Test
    fun `a stored warning becomes the window and direction it announced`() {
        assertEquals(
            DeliveredWarning(
                start = START.toKotlinInstant(),
                end = END.toKotlinInstant(),
                direction = PressureDirection.RISE
            ),
            row(PressureDirection.RISE.wireName).toDeliveredWarning()
        )
    }

    /** Rows outlive the build that wrote them; a direction this build can't read matches nothing. */
    @Test
    fun `a stored warning with an unreadable direction is dropped`() {
        assertNull(row("sideways").toDeliveredWarning())
    }
}
