package com.radami.migrainewatch.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.radami.migrainewatch.domain.Severity
import com.radami.migrainewatch.domain.SymptomDay
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

@Entity(tableName = "symptom_entries")
data class SymptomEntry(
    @PrimaryKey val date: LocalDate,
    val severity: Severity,
    val triggers: List<String>,
    val durationBucket: String?,
    val reliefPercent: Int?,
    val medication: String?,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

/** What streak counting needs from a logged day. */
fun SymptomEntry.toSymptomDay(): SymptomDay = SymptomDay(date, severity)
