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

Then launch **Migraine Watch** from the launcher, or:

```bash
adb shell am start -n com.radami.migrainewatch/.MainActivity
```

On first launch the app asks for a location — either from GPS or by searching for a
city. After that it opens on the Today screen. A `PressureFetchWorker` refreshes the
data hourly in the background.

### Mock data

`MockDataInterceptor` can serve the pressure endpoints instead of the network, so the app
has realistic data without waiting on a real weather pattern. It is off by default. Turn it
on per developer in `local.properties`, which is untracked:

```properties
useMockData=true
```

or for a single build:

```bash
./gradlew installDebug -PuseMockData=true
```

Release builds ignore both and always call the real API. Tests ignore both too — they
install their own client (`di/FakeHttpClientModule`) and always get mock data, so pointing
your own build at Open-Meteo never turns the suite red.

Choose the pattern it generates in `data/remote/mock/MockDataInterceptor.kt`:

```kotlin
var currentScenario: Scenario = Scenario.THREE_EVENTS  // THREE_EVENTS | TWO_EVENTS | NO_EVENTS
```

Each scenario is named for what its forecast contains, and the sizes are exact — the curves
carry no noise, so a scenario behaves the same at every location:

| scenario | events | alerts at High / Medium / Low |
| --- | --- | --- |
| `THREE_EVENTS` | 12 hPa drop, 9 hPa rise, 7 hPa drop | 3 / 2 / 1 |
| `TWO_EVENTS` | 9 hPa drop, 9 hPa rise | 2 / 2 / 0 |
| `NO_EVENTS` | none | 0 / 0 / 0 |

`THREE_EVENTS` is the default: its events are laid out back to back inside the window the
alert detail chart draws, and each sensitivity preset drops one of them. City search always
goes to the real geocoding service — it is never mocked. Release builds use the live API
regardless of these values.

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

## Cutting a release

### Version numbers

Both sit in `defaultConfig` in `app/build.gradle.kts`:

```kotlin
versionCode = 2
versionName = "1.1"
```

They are not two spellings of the same thing:

| | `versionCode` | `versionName` |
| --- | --- | --- |
| type | integer | free-form string |
| read by | Play and Android | people |
| shown | nowhere user-facing | store listing, system app info |
| must change | every upload | only when you want it to |

`versionCode` is the identity of the upload. Play refuses a bundle carrying a code it has
already seen, and Android compares codes to decide whether an install is an upgrade — so it
has to strictly increase. Gaps are harmless. Bump it for every build that leaves the machine,
including one that only fixes the upload before it.

`versionName` is a label. Nothing enforces it and nothing compares it; two uploads may share
one. Change it when the difference is worth telling users about.

So a re-upload that fixes a broken release is `versionCode = 2` with `versionName` left alone,
while a real release moves both.

### The changelog

`CHANGELOG.md` records what each version contains. It is written **as the work lands**, in the
same commit as the change itself — reconstructed from the git log at release time, entries end
up describing commits rather than describing what changed for the user, and the store listing
is the only place most users ever read about this app.

Everything in progress goes under `## [Unreleased]`. Entries above that section's `### Internal`
heading are for users and are what the store text is written from; refactors, tests and build
changes go under `Internal` and never leave the repository. If a change has no user-visible
effect it belongs under `Internal`; if it has one, say what a user would notice rather than what
the code now does.

At release time the `## [Unreleased]` heading becomes the version being cut —
`## [1.2] — 2026-09-01 · versionCode 3` — a fresh empty `## [Unreleased]` opens above it, and
the link definitions at the bottom of the file get the new tag.

### Signing

The upload key is read from `keystore.properties` at the repo root. It is untracked, so it has
to exist on whichever machine builds the release:

```properties
storeFile=/home/you/keys/migrainewatch-upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Without it the build still **succeeds** and produces an *unsigned* bundle. That is deliberate —
the signing config is only declared when the file is present, so a fresh clone or CI fails on
whatever it was actually doing rather than on a missing keystore. The cost is that a forgotten
keystore surfaces at upload time, not build time, so it is worth confirming:

```bash
unzip -l app/build/outputs/bundle/release/app-release.aab | grep META-INF
# META-INF/UPLOAD.SF and META-INF/UPLOAD.RSA — present means signed
```

### Building

```bash
./gradlew testDebugUnitTest    # not enforced, just cheap
./gradlew bundleRelease
```

What you upload is:

```
app/build/outputs/bundle/release/app-release.aab
```

`assembleRelease` also exists and leaves an `app-release.apk` under `outputs/apk/release/`.
That one is for putting a release build on a device by hand — Play wants the `.aab` and
generates per-device APKs from it itself.

Release builds always call the live Open-Meteo API. `USE_MOCK_DATA` is hard-coded false for
the release build type, so neither `local.properties` nor `-PuseMockData=true` can reach one.

### Tagging

Every upload gets an annotated tag on the commit it was built from, so a bug report naming a
version can be traced to the exact code that user is running:

```bash
git tag -a v1.2 -m "Release 1.2 (versionCode 3)"
git push origin main v1.2
```

Annotated rather than lightweight — the tag then carries its own date and author, and
`git describe` will use it. If a fix lands after tagging, that is a new version, not a re-tag:
Play has already tied that `versionCode` to a specific bundle, and moving a pushed tag makes
the two disagree about what shipped.

`v1.0` and `v1.1` were tagged after the fact, from the release history rather than at upload:

| Tag | `versionCode` | Commit | |
| --- | --- | --- | --- |
| `v1.0` | 1 | `e09dc6a` | Fix minor Play build issues |
| `v1.1` | 2 | `6ac19b3` | Exclude play assets from github |

### Release notes

Play's "What's new" box takes 500 characters per language, so it is a **trim** of the version's
changelog section rather than a copy of it: keep the user-facing entries, drop everything under
`Internal`, lead with the change people will actually notice, and drop fixes nobody reported.

### Store assets

`play-assets/` holds what the listing needs. The two graphics are generated rather than drawn,
so they can be rebuilt from the launcher icon if the brand moves:

```bash
python3 play-assets/make_feature_graphic.py   # 1024x500 feature graphic
python3 play-assets/make_icon.py              # 512x512 listing icon
```

Both need `numpy` and `Pillow`. The screenshots alongside them are captured from a device.

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
