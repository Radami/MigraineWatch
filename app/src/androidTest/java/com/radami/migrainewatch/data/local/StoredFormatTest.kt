package com.radami.migrainewatch.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radami.migrainewatch.data.model.NotifiedAlert
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.di.DatabaseModule
import com.radami.migrainewatch.domain.Severity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant

/**
 * Pins how rows sit on disk, read and written with plain SQLite rather than through Room.
 * Every install already holds rows in this shape, so whatever builds the database has to keep
 * reading and writing exactly these columns, types and encodings, in a file of this name.
 */
@RunWith(AndroidJUnit4::class)
class StoredFormatTest {

    private companion object {
        /** The file existing installs keep their data in. */
        const val DATABASE_NAME = "migraine_watch.db"

        /** Quarter past six UTC and a quarter second, so millisecond precision is pinned too. */
        const val READING_AT_MILLIS = 1787552100250L
        const val FETCHED_AT_MILLIS = 1787554800000L

        const val ENTRY_DATE = "2026-08-24"
        const val ENTRY_CREATED_MILLIS = 1787601600125L
        const val ENTRY_UPDATED_MILLIS = 1787607000000L

        const val ALERT_START_MILLIS = 1787616000000L
        const val ALERT_END_MILLIS = 1787688000000L
        const val ALERT_NOTIFIED_MILLIS = 1787572800000L

        const val PRESSURE_MSL = 1013.25f
        const val SURFACE_PRESSURE = 870.5f
        const val THRESHOLD_HPA = 6f
        const val RELIEF_PERCENT = 50
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val reading = PressureReading(
        dateTime = Instant.fromEpochMilliseconds(READING_AT_MILLIS),
        pressureMsl = PRESSURE_MSL,
        surfacePressure = SURFACE_PRESSURE,
        fetchedDateTime = Instant.fromEpochMilliseconds(FETCHED_AT_MILLIS)
    )

    private val entry = SymptomEntry(
        date = LocalDate.parse(ENTRY_DATE),
        severity = Severity.MIGRAINE,
        triggers = listOf("Stress", "Weather"),
        durationBucket = "4-12h",
        reliefPercent = RELIEF_PERCENT,
        medication = "Ibuprofen",
        notes = "Woke with it",
        createdAt = Instant.fromEpochMilliseconds(ENTRY_CREATED_MILLIS),
        updatedAt = Instant.fromEpochMilliseconds(ENTRY_UPDATED_MILLIS)
    )

    private val alert = NotifiedAlert(
        startDateTime = Instant.fromEpochMilliseconds(ALERT_START_MILLIS),
        endDateTime = Instant.fromEpochMilliseconds(ALERT_END_MILLIS),
        direction = "drop",
        thresholdHpa = THRESHOLD_HPA,
        notifiedDateTime = Instant.fromEpochMilliseconds(ALERT_NOTIFIED_MILLIS)
    )

    @Before
    fun startEmpty() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    /** Runs [block] against the database as the app builds it, then closes it. */
    private fun <T> withAppDatabase(block: suspend (AppDatabase) -> T): T {
        val db = DatabaseModule.provideDatabase(context)
        try {
            return runBlocking { block(db) }
        } finally {
            db.close()
        }
    }

    /** Opens the same file with the platform's SQLite, bypassing Room entirely. */
    private fun <T> withRawDatabase(block: (SQLiteDatabase) -> T): T {
        val path = context.getDatabasePath(DATABASE_NAME).path
        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    /** Every column of the single row in [table], keyed by name, as SQLite holds it. */
    private fun SQLiteDatabase.singleRow(table: String): Map<String, Any?> =
        rawQuery("SELECT * FROM $table", null).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            cursor.columnNames.associateWith { name ->
                val index = cursor.getColumnIndexOrThrow(name)
                when (cursor.getType(index)) {
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                    android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                    else -> null
                }
            }
        }

    /** Creates the schema the way the app does, so raw inserts land in the real tables. */
    private fun createSchema() = withAppDatabase { it.symptomEntryDao().getEarliestDate() }

    @Test
    fun aReadingIsStoredAsEpochMillisecondsAndRealNumbers() {
        withAppDatabase { it.pressureReadingDao().insertHistorical(listOf(reading)) }

        val row = withRawDatabase { it.singleRow("pressure_readings") }

        assertEquals(
            mapOf(
                "dateTime" to READING_AT_MILLIS,
                "pressureMsl" to PRESSURE_MSL.toDouble(),
                "surfacePressure" to SURFACE_PRESSURE.toDouble(),
                "fetchedDateTime" to FETCHED_AT_MILLIS
            ),
            row
        )
    }

    @Test
    fun aLoggedDayIsStoredWithAnISODateASeverityNameAndJoinedTriggers() {
        withAppDatabase { it.symptomEntryDao().upsert(entry) }

        val row = withRawDatabase { it.singleRow("symptom_entries") }

        assertEquals(
            mapOf(
                "date" to ENTRY_DATE,
                "severity" to "MIGRAINE",
                "triggers" to "Stress,Weather",
                "durationBucket" to "4-12h",
                "reliefPercent" to RELIEF_PERCENT.toLong(),
                "medication" to "Ibuprofen",
                "notes" to "Woke with it",
                "createdAt" to ENTRY_CREATED_MILLIS,
                "updatedAt" to ENTRY_UPDATED_MILLIS
            ),
            row
        )
    }

    @Test
    fun aDeliveredWarningIsStoredWithItsWindowInEpochMilliseconds() {
        withAppDatabase { it.notifiedAlertDao().insert(alert) }

        val row = withRawDatabase { it.singleRow("notified_alerts") }

        assertEquals(
            mapOf(
                "startDateTime" to ALERT_START_MILLIS,
                "endDateTime" to ALERT_END_MILLIS,
                "direction" to "drop",
                "thresholdHpa" to THRESHOLD_HPA.toDouble(),
                "notifiedDateTime" to ALERT_NOTIFIED_MILLIS
            ),
            row
        )
    }

    @Test
    fun aReadingStoredInTheEstablishedShapeIsReadBack() {
        createSchema()
        withRawDatabase { db ->
            db.insertOrThrow("pressure_readings", null, ContentValues().apply {
                put("dateTime", READING_AT_MILLIS)
                put("pressureMsl", PRESSURE_MSL)
                put("surfacePressure", SURFACE_PRESSURE)
                put("fetchedDateTime", FETCHED_AT_MILLIS)
            })
        }

        val readBack = withAppDatabase {
            it.pressureReadingDao().getReadingsInRange(reading.dateTime, reading.dateTime).first()
        }

        assertEquals(listOf(reading), readBack)
    }

    @Test
    fun aLoggedDayStoredInTheEstablishedShapeIsReadBack() {
        createSchema()
        withRawDatabase { db ->
            db.insertOrThrow("symptom_entries", null, ContentValues().apply {
                put("date", ENTRY_DATE)
                put("severity", "MIGRAINE")
                put("triggers", "Stress,Weather")
                put("durationBucket", "4-12h")
                put("reliefPercent", RELIEF_PERCENT)
                put("medication", "Ibuprofen")
                put("notes", "Woke with it")
                put("createdAt", ENTRY_CREATED_MILLIS)
                put("updatedAt", ENTRY_UPDATED_MILLIS)
            })
        }

        val readBack = withAppDatabase { it.symptomEntryDao().getByDate(entry.date) }

        assertEquals(entry, readBack)
    }

    @Test
    fun aDeliveredWarningStoredInTheEstablishedShapeIsReadBack() {
        createSchema()
        withRawDatabase { db ->
            db.insertOrThrow("notified_alerts", null, ContentValues().apply {
                put("startDateTime", ALERT_START_MILLIS)
                put("endDateTime", ALERT_END_MILLIS)
                put("direction", "drop")
                put("thresholdHpa", THRESHOLD_HPA)
                put("notifiedDateTime", ALERT_NOTIFIED_MILLIS)
            })
        }

        val readBack = withAppDatabase {
            it.notifiedAlertDao().getNotifiedSince(alert.notifiedDateTime)
        }

        assertEquals(listOf(alert), readBack)
    }
}
