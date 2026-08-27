# Changelog

Every change that ships in a release, newest first.

Entries are written for users — see [Cutting a release](README.md#cutting-a-release) for how a
version is put together. While a version is in progress, `## [Unreleased]` also carries an
**Internal** section for refactors, tests and build changes; it is a working note for whoever
is in the code, and it is dropped when the version is cut. What a released version keeps in
its place is the Play "What's new" text it actually shipped with, so the store listing can be
read back later without digging through the Play console.

Each released version is tagged in git (`v1.2`), and the heading records the `versionCode`
Play actually keys on, because the store shows users the `versionName` and nothing else.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html) loosely: the patch number is
for fixes alone, the minor number for anything users would notice.

## [Unreleased]

## [1.2] — 2026-08-27 · versionCode 3

### Added
- The Today screen opens on a seven-day outlook: whether today is one to watch, how many of
  the six days after it are, and a circle on each day a pressure event touches.
- The Today screen counts how long you have gone without symptoms, and remembers your longest
  streak and the dates it ran between.

### Changed
- The 7-day chart draws the daily minimum and maximum as two lines with the range shaded
  between them.
- The Pressure screen absorbs the alert screen: its chart shades every current event's risk
  window and the card beneath lists them.
- An event outside the range the chart is set to is listed as "not in view" rather than left
  unexplained.
- The Alerts card lists at most three events and says how many it is holding back.
- The chart card is headed "Pressure" with the current reading beside it, and both cards on
  the screen now head at the same size.
- Calendar days at high risk are drawn as a circle instead of a trend arrow, with a "High
  risk" legend entry in place of "Drop" and "Rise".
- The Today banner says only that risk is elevated and when — "Starts Saturday 14:00", or
  "Underway since Saturday 14:00" once you are in it.
- The whole alert banner is the tap target rather than its chevron, and each day in the
  outlook strip is one too.
- An event is named the same way everywhere it appears: "Pressure rise (8.2 hPa in 24h)".
- Relief on the log entry screen is picked from 0 / 25 / 50 / 75 / 100 % chips rather than a
  slider, and tapping the chosen one clears it.
- Section headings read the same on every screen, Settings included.

### Removed
- The barometric pressure card on the Today screen, replaced by the outlook above.
- The seven-day symptom strip on the Today screen, replaced by the streak count; the same
  seven days are still on the Calendar screen.
- The up and down pressure arrows on the Calendar screen.
- The "Last 3 events" card on the Pressure screen, replaced by the alerts still worth acting on.
- The separate pressure alert screen, merged into the Pressure screen above.

### Fixed
- The Today banner announced an event that had already finished, headed "Next", while the
  event genuinely coming went unmentioned.
- The banner announced an event already under way as the next one coming, at a start time that
  had passed.
- A pressure event that ended exactly at midnight marked the following day as one to watch.
- Opening the app from an alert notification and then moving to another tab no longer snaps
  back to Pressure when the screen is rotated.
- The Alerts card said it had found nothing "in the next 7 days" when it also covers events
  that finished within the last day.
- Tapping a chart range chip now selects it immediately rather than after a database read.
- An event running longer than a day was reported at a swing no 24-hour period in it ever
  reached, so it could vanish when the sensitivity was raised past that figure.
- The last day of the seven-day outlook was dimmed as having no forecast even with a full
  seven days in hand.

### Play "What's new"

```
Today now opens on a seven-day outlook: whether today is one to watch, how many of the next six are, and a circle on every day a pressure event touches. It also counts how long you have gone without symptoms.

The Pressure screen shades each event's risk window on the chart and lists the events beneath it, replacing the separate alert screen. High-risk days are circled on the calendar, and the Today banner says when an event starts or that you are already in it.
```

## [1.1] — 2026-08-06 · versionCode 2

### Fixed
- Notification permission is now requested when the app first starts, so pressure alerts
  arrive for people who never opened Settings.
- Dates and times always display in English. On a device set to another language, parts of
  the app disagreed with each other — a chart axis reading "Sat" under a heading reading
  "Samstag".

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

[Unreleased]: https://github.com/Radami/MigraineWatch/compare/v1.2...HEAD
[1.2]: https://github.com/Radami/MigraineWatch/compare/v1.1...v1.2
[1.1]: https://github.com/Radami/MigraineWatch/compare/v1.0...v1.1
[1.0]: https://github.com/Radami/MigraineWatch/releases/tag/v1.0
