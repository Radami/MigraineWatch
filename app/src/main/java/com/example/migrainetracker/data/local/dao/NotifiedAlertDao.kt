package com.example.migrainetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.migrainetracker.data.model.NotifiedAlert
import java.time.Instant

@Dao
interface NotifiedAlertDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: NotifiedAlert)

    /**
     * Records from [since] onwards. The decider only cares about recent history — an event
     * from last month can no longer collide with anything in the forecast.
     */
    @Query("SELECT * FROM notified_alerts WHERE startDateTime >= :since ORDER BY startDateTime")
    suspend fun getNotifiedSince(since: Instant): List<NotifiedAlert>

    @Query("DELETE FROM notified_alerts WHERE startDateTime < :before")
    suspend fun deleteOlderThan(before: Instant)

    /**
     * Forgets every delivered warning. Debug-only: it makes an event announceable again, which
     * is what lets the same mock scenario be tested more than once.
     */
    @Query("DELETE FROM notified_alerts")
    suspend fun deleteAll()
}
