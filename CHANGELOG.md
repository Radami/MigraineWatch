# Changelog

Every change that ships in a release, newest first.

Entries above **Internal** are written for users and are the source for the Play Store
"What's new" text — see [Cutting a release](README.md#cutting-a-release) for how a version is
put together. Entries under **Internal** never reach the store listing; they are here so a
release can be reconstructed later without reading the whole git log.

Each released version is tagged in git (`v1.1`), and the heading records the `versionCode`
Play actually keys on, because the store shows users the `versionName` and nothing else.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html) loosely: the patch number is
for fixes alone, the minor number for anything users would notice.

## [Unreleased]

### Added
- The Today screen counts how long you have gone without symptoms, and remembers your
  longest symptom-free streak and the dates it ran between.

### Removed
- The seven-day symptom strip on the Today screen, replaced by the streak count above. The
  same seven days are still on the Calendar screen.

### Internal
- `SymptomFreeStreak` domain type holds the streak maths, covered by 11 unit tests.
- `TodayViewModel` reads the full symptom history rather than a seven-day window, and no
  longer carries `weekEntries` or `pressureEventDays`.
- `Severity.isSymptomEvent` replaces scattered `!= CLEAR` checks.
- Severity presentation moved off the enum, matching how the alert sensitivity presets are
  handled: the spelling to `format/SeverityFormat.kt`, the colour to `ui/theme/SeverityColors.kt`.
  Together they replace three copies of `toColor()` and two hand-written label lists.
- Two compact date formats added to `AppDateFormats`.
- Tests run against a new `sandbox` build type, installed under its own application id.
  `connectedAndroidTest` uninstalls the app under test when it finishes, so while the tests
  shared an id with the debug build every run deleted the log accumulated while developing.
  Task names follow the build type: `testSandboxUnitTest`, `connectedSandboxAndroidTest`.

## [1.1] — 2026-08-06 · versionCode 2

### Fixed
- Notification permission is now requested when the app first starts, so pressure alerts
  arrive for people who never opened Settings.
- Dates and times always display in English. On a device set to another language, parts of
  the app disagreed with each other — a chart axis reading "Sat" under a heading reading
  "Samstag".

### Internal
- All display date formats routed through `AppDateFormats`, and the display locale pinned in
  `AppLocale`, so the language of a date can only be changed for all of them at once.
- Landing page and privacy policy added under `docs/`, published for the Play listing.
- Play Store listing preparation: README expanded, `play-assets/` kept out of the repository.

## [1.0] — 2026-08-01 · versionCode 1

First public release.

### Added
- Today screen: current barometric pressure for your location, a chart covering three days
  back and four days ahead, and a banner when a pressure swing large enough to matter is
  coming.
- Pressure screen: seven-day history with a min/max band, and the three most recent alerts.
- Calendar screen: log a day as clear, mild, aura or migraine, with triggers, duration,
  relief, medication and notes; month view with per-severity statistics.
- Alert detail screen: the pressure curve behind an alert, for up to three overlapping events.
- Notifications ahead of a pressure alert, at one of three sensitivity settings.
- Onboarding that asks for a location and notification permission on first run.

[Unreleased]: https://github.com/Radami/MigraineWatch/compare/v1.1...HEAD
[1.1]: https://github.com/Radami/MigraineWatch/compare/v1.0...v1.1
[1.0]: https://github.com/Radami/MigraineWatch/releases/tag/v1.0
