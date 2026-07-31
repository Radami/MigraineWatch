package com.radami.migrainewatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.radami.migrainewatch.data.model.NotifiedAlert
import java.time.Instant

@Dao
interface NotifiedAlertDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: NotifiedAlert)

    /**
     * Warnings *delivered* from [since] onwards. The filter is on the delivery time, not on
     * the event's start: an event that is already underway is recorded with a start that is
     * hours old the moment it is written, so a start-based lookback drops the record almost
     * immediately and the event is announced again on the next refresh.
     */
    @Query("SELECT * FROM notified_alerts WHERE notifiedDateTime >= :since ORDER BY notifiedDateTime")
    suspend fun getNotifiedSince(since: Instant): List<NotifiedAlert>

    @Query("DELETE FROM notified_alerts WHERE notifiedDateTime < :before")
    suspend fun deleteOlderThan(before: Instant)

    /**
     * Forgets every delivered warning. Debug-only: it makes an event announceable again, which
     * is what lets the same mock scenario be tested more than once.
     */
    @Query("DELETE FROM notified_alerts")
    suspend fun deleteAll()
}
