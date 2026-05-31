package com.example.migrainetracker.data.local

import androidx.room.TypeConverter
import com.example.migrainetracker.data.model.Severity
import java.time.Instant
import java.time.LocalDate

class Converters {
    @TypeConverter fun instantToLong(v: Instant): Long = v.toEpochMilli()
    @TypeConverter fun longToInstant(v: Long): Instant = Instant.ofEpochMilli(v)

    @TypeConverter fun localDateToString(v: LocalDate): String = v.toString()
    @TypeConverter fun stringToLocalDate(v: String): LocalDate = LocalDate.parse(v)

    @TypeConverter fun listToString(v: List<String>): String = v.joinToString(",")
    @TypeConverter fun stringToList(v: String): List<String> =
        if (v.isEmpty()) emptyList() else v.split(",")

    @TypeConverter fun severityToString(v: Severity): String = v.name
    @TypeConverter fun stringToSeverity(v: String): Severity = Severity.valueOf(v)
}
