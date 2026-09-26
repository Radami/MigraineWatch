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
     * Warnings *delivered* from [since] onwards. Filters on delivery time, not event start,
     * since an already-underway event's start could be hours old and drop out too soon.
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
