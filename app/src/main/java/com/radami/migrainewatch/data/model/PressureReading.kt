package com.radami.migrainewatch.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.radami.migrainewatch.domain.PressureSample
import java.time.Instant

@Entity(tableName = "pressure_readings")
data class PressureReading(
    @PrimaryKey val dateTime: Instant,
    val pressureMsl: Float,
    val surfacePressure: Float,
    val fetchedDateTime: Instant
)

/** What detection needs from a stored reading. */
fun PressureReading.toPressureSample(): PressureSample = PressureSample(dateTime, pressureMsl)
