package com.radami.migrainewatch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.radami.migrainewatch.coroutines.catchingFailures
import com.radami.migrainewatch.data.local.AppDatabase
import com.radami.migrainewatch.data.local.DATABASE_NAME
import com.radami.migrainewatch.data.local.buildAppDatabase
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.GeocodingApi
import com.radami.migrainewatch.data.remote.KtorGeocodingApi
import com.radami.migrainewatch.data.remote.KtorOpenMeteoApi
import com.radami.migrainewatch.data.remote.KtorOpenMeteoArchiveApi
import com.radami.migrainewatch.data.remote.openMeteoHttpClient
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertReconcileMonitor
import com.radami.migrainewatch.domain.AlertReconciler
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.logging.Logger
import com.radami.migrainewatch.notifications.IosNotificationCenter
import com.radami.migrainewatch.notifications.LocalNotificationAlertQueue
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** The settings file, named as Android's DataStore delegate names it. */
private const val PREFERENCES_FILE_NAME = "user_prefs.preferences_pb"

private const val TAG = "IosAppGraph"

/**
 * Whether a refresh fetches even when the stored forecast is still fresh. Cases in upper snake
 * case, which Swift sees as `.always` and `.whenStale`; camel-cased cases would reach it lowercased.
 */
enum class RefreshPolicy {

    /** A background refresh or an explicit check: iOS grants the former rarely, so each one fetches. */
    ALWAYS,

    /**
     * The app coming to the foreground, which happens far more often than Open-Meteo publishes.
     * Fetches only once the forecast has aged, as Android's Pressure screen does.
     */
    WHEN_STALE
}

/**
 * The shared data layer wired up for iOS, standing in for the Hilt modules that do this on
 * Android. Create one for the app's lifetime: it owns the database and the settings file, and
 * DataStore allows only one instance per file in a process.
 *
 * @param directory where the database and settings live.
 */
class IosAppGraph(private val directory: String) {

    /** Keeps everything in the app's Application Support. Swift can't see a default argument, hence this. */
    constructor() : this(applicationSupportDirectory())

    /** Outlives every screen, like Android's application scope; see [PressureRepository]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = openMeteoHttpClient(Darwin.create())

    val database: AppDatabase by lazy {
        buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(name = pathTo(DATABASE_NAME)),
            BundledSQLiteDriver()
        )
    }

    val userPreferences: UserPreferences by lazy {
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) {
            pathTo(PREFERENCES_FILE_NAME).toPath()
        }
        UserPreferences(dataStore)
    }

    val geocodingApi: GeocodingApi by lazy { KtorGeocodingApi(httpClient) }

    val pressureRepository: PressureRepository by lazy {
        PressureRepository(
            dao = database.pressureReadingDao(),
            forecastApi = KtorOpenMeteoApi(httpClient),
            archiveApi = KtorOpenMeteoArchiveApi(httpClient),
            prefs = userPreferences,
            scope = scope
        )
    }

    val symptomRepository: SymptomRepository by lazy { SymptomRepository(database.symptomEntryDao()) }

    val alertUseCase: PressureAlertUseCase by lazy {
        PressureAlertUseCase(pressureRepository, userPreferences)
    }

    val alertReconciler: AlertReconciler by lazy {
        val notifiedAlertDao = database.notifiedAlertDao()
        val queue = LocalNotificationAlertQueue(IosNotificationCenter(), notifiedAlertDao, userPreferences)
        AlertReconciler(queue, alertUseCase, notifiedAlertDao, userPreferences)
    }

    private val alertReconcileMonitor: AlertReconcileMonitor by lazy {
        AlertReconcileMonitor(pressureRepository, userPreferences, alertReconciler, scope)
    }

    /**
     * Starts what runs for the app's whole life, as Android's application does at launch: the
     * watch that rebuilds queued warnings whenever a series lands or the alert settings change.
     * Call once, when the app finishes launching.
     */
    fun start() {
        alertReconcileMonitor.start()
    }

    /**
     * One refresh, run the way Android's fetch worker runs it: fetch, then reconcile even when
     * the fetch failed or was skipped, since the clock has moved regardless.
     *
     * @return whether the app is now up to date, for the background task to report.
     */
    suspend fun refreshAndReconcile(policy: RefreshPolicy): Boolean {
        // Swift sees only cancellation from a suspend call; any other throw would crash the app.
        val upToDate = catchingFailures { fetchFor(policy) }
            .onFailure { Logger.e(TAG, "Refresh failed", it) }
            .getOrDefault(false)

        alertReconciler.reconcile()
        return upToDate
    }

    /** @return whether the stored forecast is current once this is done. */
    private suspend fun fetchFor(policy: RefreshPolicy): Boolean {
        if (policy == RefreshPolicy.WHEN_STALE && !pressureRepository.isForecastStale()) return true

        return when (pressureRepository.refresh()) {
            RefreshState.Updated, RefreshState.NoReadings, RefreshState.NoLocation -> true
            RefreshState.Failed, RefreshState.InFlight -> false
        }
    }

    /** Stops background work and releases the database and network client. */
    fun close() {
        scope.cancel()
        httpClient.close()
        database.close()
    }

    private fun pathTo(fileName: String): String = "$directory/$fileName"
}

@OptIn(ExperimentalForeignApi::class)
/**
 * Where iOS expects an app's own data: backed up like Documents, but never shown to the user in
 * the Files app or offered for sharing, as Documents is meant to be. Created on first use, since
 * unlike Documents it doesn't exist until an app asks for it.
 */
private fun applicationSupportDirectory(): String = memScoped {
    // Without a place to keep its data the app can't run, so this fails loudly, with iOS's reason.
    val error = alloc<ObjCObjectVar<NSError?>>()
    val applicationSupport = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = error.ptr
    )
    checkNotNull(applicationSupport?.path) {
        "The app has no Application Support directory: ${error.value?.localizedDescription}"
    }
}
