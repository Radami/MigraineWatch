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

### Changed
- Calendar days at high risk are now drawn as a circle instead of carrying a small trend
  arrow. The arrows told you which way the pressure was moving, which people read as decoration
  rather than a warning; the shape says the thing that matters, which is that the day is one to
  watch. The day number sits centred in its shape, and the legend gains a "High risk" entry in
  place of the "Drop" and "Rise" ones.

### Removed
- The seven-day symptom strip on the Today screen, replaced by the streak count above. The
  same seven days are still on the Calendar screen.
- The up and down pressure arrows on the Calendar screen.

### Fixed
- A pressure event that ended exactly at midnight marked the following day as one to watch,
  even though the event never ran into it.

### Internal
- `SymptomFreeStreak` domain type holds the streak maths, covered by 11 unit tests.
- `AlertDetector.eventDaysByDirection` becomes `eventDays`, returning a `Set<LocalDate>`. The
  per-day "which direction held this day longest" tie-break existed only to choose an arrow,
  so the `"drop"`/`"rise"` strings no longer reach the calendar. They still carry the alert
  screens and the notification payloads, which is the last of them.
- `eventDays` trims an end day the alert only touches at its first instant. The old
  dominant-direction map kept such a day with a zero-second overlap, which was easy to miss
  behind a small arrow and is not behind a full circle.
- `DayMarker` takes a `DayRisk` rather than `eventDirection: String?`, and picks between
  `SeverityShape` and the new `HighRiskShape`. Its fixed-height trend slot is gone, so the day
  number is simply centred. A high-risk day is always ringed, filled or not — without it a
  filled circle reads as a severity chip that happens to be round. Exactly one ring is drawn:
  today's 2 dp `primary` outranks the `outline` used for risk. That risk ring's width is
  public, because the legend draws the same ring and the two have to match to mean anything.
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
