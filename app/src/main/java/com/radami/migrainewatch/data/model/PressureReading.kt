package com.radami.migrainewatch.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "pressure_readings")
data class PressureReading(
    @PrimaryKey val dateTime: Instant,
    val pressureMsl: Float,
    val surfacePressure: Float,
    val fetchedDateTime: Instant
)
