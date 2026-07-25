# MigraineWatch

An Android app for people whose migraines track the weather. It charts barometric
pressure for your location, flags the pressure swings large enough to be worth
knowing about, and lets you log symptoms day by day so the two can be compared.

Pressure data comes from [Open-Meteo](https://open-meteo.com/) (forecast, archive and
geocoding endpoints — no API key required).

## Requirements

| | |
|---|---|
| JDK | 17 or newer |
| Android SDK | compileSdk 36.1, minSdk 26 |
| Emulator / device | API 26+ |

The Gradle wrapper pins the Gradle version, so `./gradlew` is all you need — no local
Gradle install. Point the build at your SDK with `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Running the app

```bash
# Build and install on the connected device or running emulator
./gradlew installDebug

# Or just build the APK (app/build/outputs/apk/debug/)
./gradlew assembleDebug
```

Then launch **MigraineWatch** from the launcher, or:

```bash
adb shell am start -n com.example.migrainetracker/.MainActivity
```

On first launch the app asks for a location — either from GPS or by searching for a
city. After that it opens on the Today screen. A `PressureFetchWorker` refreshes the
data hourly in the background.

### Mock data

Debug builds serve the pressure endpoints from `MockDataInterceptor` instead of the
network, so the app has realistic data without waiting on a real weather pattern. It is
controlled by two switches. Turn it off in `di/NetworkModule.kt`:

```kotlin
const val USE_MOCK_DATA = true   // set to false to hit the real Open-Meteo API
```

and choose the pattern it generates in `data/remote/mock/MockDataInterceptor.kt`:

```kotlin
var currentScenario: Scenario = Scenario.NORMAL   // NORMAL | STORM | CALM
```

`NORMAL` generates several alert-worthy events, `STORM` forces a single ~10 hPa drop, and
`CALM` produces no events at all. City search always goes to the real geocoding service —
it is never mocked. Release builds use the live API regardless of these values.

## Running the tests

### Unit tests (JVM, no device needed)

```bash
./gradlew testDebugUnitTest
```

Report: `app/build/reports/tests/testDebugUnitTest/index.html`

Covers the alert detection logic, `TodayViewModel`, `PressureRepository`, and three
end-to-end user journeys that drive the real Compose UI under Robolectric.

Useful variants:

```bash
# One class
./gradlew testDebugUnitTest --tests "*AlertDetectorTest"

# One test method
./gradlew testDebugUnitTest --tests "*TodayViewModelTest.initial state is loading"

# Ignore up-to-date checks
./gradlew testDebugUnitTest --rerun-tasks
```

### Instrumented tests (device or emulator required)

```bash
adb devices                      # confirm one is attached
./gradlew connectedDebugAndroidTest
```

Report: `app/build/reports/androidTests/connected/debug/index.html`

Covers the Room DAO against a real in-memory database, and navigation between the alert
banner, the alert detail screen and the bottom bar.

### Everything

```bash
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

### Notes on the test setup

- Tests that launch the app seed their own preferences and pick a mock scenario **before**
  launching the activity, so they start as a returning user with deterministic data.
  Instrumented preferences survive between runs, which is why the values are pinned rather
  than assumed.
- `HiltTestApplication` replaces the app's `Application` class, so tests initialize
  WorkManager themselves via `WorkManagerTestInitHelper`.
- City search is served by a fake in `FakeGeocodingModule`, which replaces `GeocodingModule`
  so no test depends on the live geocoding service.

## Architecture

```
ui/          Compose screens (today, pressure, calendar, settings, alert, onboarding)
             and navigation, one ViewModel per screen
domain/      AlertDetector — finds qualifying pressure events in a series of readings
data/        Room database, DataStore preferences, Retrofit APIs, repositories
workers/     PressureFetchWorker — hourly background refresh
di/          Hilt modules
```

Built with Jetpack Compose, Hilt, Room, DataStore, WorkManager, Retrofit + kotlinx
serialization, and [Vico](https://github.com/patrykandpatrick/vico) for the charts.
