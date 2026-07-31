package com.radami.migrainewatch.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

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
