package com.example.migrainetracker.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.migrainetracker.data.local.dao.NotifiedAlertDao
import com.example.migrainetracker.data.model.NotifiedAlert
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.domain.AlertNotificationDecider
import com.example.migrainetracker.domain.AlertWindow
import com.example.migrainetracker.domain.PressureAlertUseCase
import com.example.migrainetracker.notifications.AlertNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Instant
import kotlin.math.abs

/**
 * Fires at the lead time worked out by [com.example.migrainetracker.domain.AlertNotificationScheduler]
 * and posts the warning for one event.
 */
@HiltWorker
class AlertNotificationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val alertUseCase: PressureAlertUseCase,
    private val notifiedAlertDao: NotifiedAlertDao,
    private val userPreferences: UserPreferences,
    private val notifier: AlertNotifier
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val alert = inputData.toAlertWindow() ?: return Result.failure()

        // The switch may have been turned off after this was scheduled.
        if (!userPreferences.settings.first().notificationsEnabled) return Result.success()

        // The scheduler cancels events that leave the forecast, but up to an hour can pass
        // between reconciles, so confirm the event is still there before waking the user.
        val now = Instant.now()
        val stillForecast = alertUseCase.currentAlerts(now).any { it.isSameEventAs(alert) }
        if (!stillForecast) return Result.success()

        val alreadySent = notifiedAlertDao.getNotifiedSince(now.minus(AlertNotificationDecider.SAME_EVENT_TOLERANCE))
            .any { it.direction == alert.direction }
        if (alreadySent) return Result.success()

        if (!notifier.notify(alert)) return Result.retry()

        notifiedAlertDao.insert(
            NotifiedAlert(
                startDateTime = alert.start,
                direction = alert.direction,
                thresholdHpa = userPreferences.settings.first().alertThresholdHpa,
                notifiedDateTime = now
            )
        )
        return Result.success()
    }

    private fun AlertWindow.isSameEventAs(other: AlertWindow): Boolean {
        if (direction != other.direction) return false
        val gap = abs(start.toEpochMilli() - other.start.toEpochMilli())
        return gap <= AlertNotificationDecider.SAME_EVENT_TOLERANCE.toMillis()
    }

    companion object {
        private const val KEY_START_EPOCH = "startEpoch"
        private const val KEY_END_EPOCH = "endEpoch"
        private const val KEY_DELTA = "delta"
        private const val KEY_DIRECTION = "direction"

        fun inputDataFor(alert: AlertWindow) = workDataOf(
            KEY_START_EPOCH to alert.start.epochSecond,
            KEY_END_EPOCH to alert.end.epochSecond,
            KEY_DELTA to alert.delta,
            KEY_DIRECTION to alert.direction
        )

        private fun androidx.work.Data.toAlertWindow(): AlertWindow? {
            val start = getLong(KEY_START_EPOCH, -1L)
            val end = getLong(KEY_END_EPOCH, -1L)
            val direction = getString(KEY_DIRECTION)
            if (start < 0 || end < 0 || direction == null) return null

            return AlertWindow(
                start = Instant.ofEpochSecond(start),
                end = Instant.ofEpochSecond(end),
                delta = getFloat(KEY_DELTA, 0f),
                direction = direction
            )
        }
    }
}
