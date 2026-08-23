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
- The Today screen opens on a seven-day outlook. It says whether today is one to watch, how
  many of the six days after it are, and circles each day a pressure event touches — the same
  mark a high-risk day wears on the calendar, under the same "High risk" legend. Days worth
  looking at are set in bold and the quiet ones recede, so the answer is there before the
  strip is read. Days the forecast has not reached are dimmed rather than shown as quiet.
- The Today screen counts how long you have gone without symptoms, and remembers your
  longest symptom-free streak and the dates it ran between.

### Changed
- The 7-day chart draws the daily minimum and maximum as two lines with the range shaded
  between them, curved the same way the hourly ranges curve. The shading alone muddied against
  a risk window drawn over it; the lines keep an edge to read where the two overlap.
- The Pressure screen absorbs the alert screen. Its chart now shades the risk window of every
  current pressure event, and the card beneath lists those events in the compact form the past
  ones used to take. Tapping an alert notification, or the chevron on the Today banner, opens
  this screen rather than a page of its own.
- An event outside the range the chart is set to is listed as "not in view" rather than left
  unexplained: alerts are found up to seven days out, while the 24 hrs range only reaches
  twelve hours ahead, and even the 7 days range stops four and a half days out.
- The Alerts card lists at most three events at once, and says how many it is not showing.
  Three is how many colours the palette holds, and a colour is how a row is matched to its
  shading on the chart; a fourth row would have to borrow one, and two events wearing the
  same colour is worse than a fourth row left off.
- The chart card is headed "Pressure" and carries the current reading beside the heading, the
  way the Today screen states it, and both cards on the screen now head at the same size.
- Calendar days at high risk are now drawn as a circle instead of carrying a small trend
  arrow. The arrows told you which way the pressure was moving, which people read as decoration
  rather than a warning; the shape says the thing that matters, which is that the day is one to
  watch. The day number sits centred in its shape, and the legend gains a "High risk" entry in
  place of the "Drop" and "Rise" ones.

### Removed
- The barometric pressure card on the Today screen, replaced by the outlook above. It drew the
  same chart the Pressure screen draws, which is where the range chips and the list of current
  events are.
- The seven-day symptom strip on the Today screen, replaced by the streak count above. The
  same seven days are still on the Calendar screen.
- The up and down pressure arrows on the Calendar screen.
- The "Last 3 events" card on the Pressure screen. It described events already over and
  outside every chart range, where the alerts that replace it are the ones still worth acting
  on.
- The separate pressure alert screen, merged into the Pressure screen above.

### Fixed
- The Today banner announced an event that had already finished, headed "Next", while the
  event genuinely coming went unmentioned — a pressure event stays current for a day after it
  ends, and the banner took the earliest of those rather than the soonest still to come. It
  now heads the next event under way or ahead, and stays away when there is none. The day it
  passed through keeps its mark on the outlook and the calendar, and the Pressure screen goes
  on showing the window you have just come through.
- A pressure event that ended exactly at midnight marked the following day as one to watch,
  even though the event never ran into it.
- Opening the app from an alert notification and then moving to another tab no longer snaps
  back to Pressure when the screen is rotated.
- The Alerts card said it had found nothing "in the next 7 days" when it also covers events
  that finished within the last day. It now names both bounds.
- Tapping a chart range chip selected it immediately rather than after the database had been
  read again — which it no longer is, because the range does not change what is queried.

### Internal
- `ChartWindow` and `ChartStep` hold the pressure chart's anchoring and the mapping from an
  instant to a chart x-value. The Pressure screen builds the same window to decide which
  alerts its chart can reach, so the shading and the "not in view" hint can never disagree.
  They sit in `domain` and not beside the chart: both are pure `java.time` value types, and a
  ViewModel working out what its chart can reach should not import the view layer to do it.
  Covered by 13 unit tests.
- `PressureChart` takes that window and the alerts to shade rather than a step in hours, and
  draws each risk band clipped to the plot area and beneath the daily range. `AlertDetailChart`
  went with the screen it served. It takes one sorted `readings` list rather than a historical
  and a forecast one, which it only ever concatenated — the split into two line series is by
  chart index, and the sortedness the interpolation depends on was an unstated invariant of
  how the caller happened to split.
- `PressureViewModel` detects through `PressureAlertUseCase`, as the Today screen and the
  notification scheduler already did, instead of running `AlertDetector` over a window of its
  own; it no longer reads thirty days of history for the removed card. The selected range no
  longer sits upstream of the query, so changing it neither re-reads the database nor re-runs
  detection. Covered by 6 unit tests, one of which pins the deliberate gap between the seven
  days detection reaches and the four and a half the widest chart range draws.
- The alert notification opens `MainActivity` with `EXTRA_OPEN_TAB` instead of a second
  activity, naming a `MainActivity.Tab` rather than a nav route — the notification layer no
  longer reaches into the nav graph. The name is checked against that enum before it is used
  (the launcher activity is exported, so anything on the device can name a destination), and
  it is honoured only on a fresh launch, not on an instance rebuilt around a restored back
  stack.
- A `FOUR_EVENTS` mock scenario, one event past the three the Alerts card lists, so the cap
  and its "N more events not shown" line are covered by a journey test rather than by
  inspection. Its deltas are all 12 hPa or more so the count holds at every sensitivity, and
  every event fits the widest chart range so the cap is the only reason a row goes unshaded.
- `AlertWindow.direction` is a `PressureDirection` rather than a `"drop"`/`"rise"` string,
  with its user-facing wording in `format` beside the severity labels. The literal spellings
  survive as `wireName` at the three boundaries that have to keep matching what an earlier
  install wrote: the `notified_alerts` column, the notification worker's input data, and the
  unique work name and notification id built from it.
- `AlertColors` reduces to one hue per alert now that no alert card needs a container colour.
- `DayOutlook` and `OutlookRisk` hold what the Today screen says about a day, built from the
  alerts the shared use case already found. A day is only called clear once the readings cover
  it to midnight, so a short forecast reads as unknown rather than quiet. Covered by 7 unit
  tests. `AlertDetector.daysTouched` is extracted from `eventDays` so the calendar and the
  outlook decide which days an event touches in one place.
- `TodayUiState` drops `readings` and `currentPressure` with the card that drew them, and its
  `alertWindows` becomes `pendingAlerts` — filtered where `now` is already known rather than
  in a composable with no clock. Covered by a Today ViewModel test and by three new
  `PressureAlertUseCase` tests pinning the relevance window itself, which nothing had.
- `RELEVANCE_HOURS` says why a finished event is kept: the Pressure screen's history half and
  the Today outlook's day marks both want one, while anything announcing to the user does not.
  It previously justified itself by naming the screens that read it.
- `DayMarker` takes a `DayEmphasis`, which decides whether the day number leans on the day's
  risk for its weight. The outlook strip asks for `ByRisk`; the calendar keeps the `Uniform`
  default, because its numbers are how a specific day is found and a quiet day there has to
  stay as legible as a busy one.
- `HighRiskLegendSwatch` and `LEGEND_SWATCH_SIZE` move to `DayMarker.kt` beside the ring and
  the width they are built from, so the outlook's legend and the calendar's are one drawing
  rather than two that have to be kept in step.
- `PressureChart` strokes the daily range's edges with Vico's own `DefaultPointConnector`,
  the connector its line spec uses, rather than a cubic of its own — the curvature depends on
  the chart bounds, so an approximation would not match the line beside it.
- The daily range gets its own `ChartRangeLine` colour instead of the measured line's.
  Sharing it put a terracotta band a shade away from the second alert's orange, so with the
  risk shading now drawn on the same chart the two read as one thing; blue is the hue the
  alert palette leaves free.
- `SymptomFreeStreak` domain type holds the streak maths, covered by 11 unit tests.
- `AlertDetector.eventDaysByDirection` becomes `eventDays`, returning a `Set<LocalDate>`. The
  per-day "which direction held this day longest" tie-break existed only to choose an arrow,
  so direction no longer reaches the calendar at all.
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
