package com.radami.migrainewatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.radami.migrainewatch.data.model.NotifiedAlert
import kotlin.time.Instant

@Dao
interface NotifiedAlertDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: NotifiedAlert)

    /**
     * Warnings *delivered* from [since] onwards. Filters on delivery time, not event start,
     * since an already-underway event's start could be hours old and drop out too soon.
     */
    @Query("SELECT * FROM notified_alerts WHERE notifiedDateTime >= :since ORDER BY notifiedDateTime")
    suspend fun getNotifiedSince(since: Instant): List<NotifiedAlert>

    @Query("DELETE FROM notified_alerts WHERE notifiedDateTime < :before")
    suspend fun deleteOlderThan(before: Instant)

    /**
     * Forgets warnings queued ahead and not yet due: rows dated after [now] for a [direction]
     * event starting from [from] up to [until]. Rows already due are history and stay, since
     * they are what stops an event being announced twice.
     */
    @Query(
        "DELETE FROM notified_alerts WHERE direction = :direction " +
            "AND startDateTime >= :from AND startDateTime < :until AND notifiedDateTime > :now"
    )
    suspend fun deleteQueuedAhead(direction: String, from: Instant, until: Instant, now: Instant)

    /**
     * Forgets every delivered warning. Debug-only: it makes an event announceable again, which
     * is what lets the same mock scenario be tested more than once.
     */
    @Query("DELETE FROM notified_alerts")
    suspend fun deleteAll()
}
