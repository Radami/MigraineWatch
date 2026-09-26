# MigraineWatch — Design & Implementation Spec

This is the technical source of truth for how the app works: the domain rules, the data flow,
and the reasoning behind the non-obvious decisions in the code. `README.md` covers building,
testing and releasing; this covers what the app actually does and why.

It is derived from the code itself — largely from comments that used to carry this reasoning
inline. When code and this document disagree, treat that as a bug in one of them and fix
whichever is wrong.

## Contents

- [Concept](#concept)
- [Architecture](#architecture)
- [Domain model](#domain-model)
- [Data layer](#data-layer)
- [Background work](#background-work)
- [Notifications](#notifications)
- [UI layer](#ui-layer)
- [Testing strategy](#testing-strategy)
- [Key invariants](#key-invariants)

## Concept

MigraineWatch is for people whose migraines are triggered by barometric pressure changes. It:

1. Tracks hourly barometric pressure at the user's location (via [Open-Meteo](https://open-meteo.com/)).
2. Detects pressure swings large enough to plausibly trigger a migraine ("events").
3. Warns the user ahead of an event with a push notification.
4. Lets the user log daily symptoms, so they can compare their own pattern against the
   pressure history.

There is no server component. Everything — fetching, detection, scheduling, notifying,
symptom logging — runs on-device.

## Architecture

```
ui/             Compose screens (today, pressure, calendar, settings, onboarding) and
                navigation. One ViewModel per screen.
domain/         Pure business logic: alert detection, notification decisions, chart
                windowing, day outlook, symptom streaks. No Android dependencies.
format/         How dates, pressure events and severities are spelled for the user.
data/           Room database, DataStore preferences, Retrofit APIs, repositories.
notifications/  Alert notifications and the permission state machine they depend on.
workers/        WorkManager jobs: hourly fetch, per-event notification delivery.
di/             Hilt modules.
```

The `domain` layer is deliberately free of Android types so its rules can be unit tested
without a device, and so two different callers (a screen and a background worker) are
guaranteed to agree, because they call the same function rather than each reimplementing the
rule.

Built with Jetpack Compose, Hilt, Room, DataStore, WorkManager, Retrofit + kotlinx
serialization, and [Vico](https://github.com/patrykandpatrick/vico) for charts.

## Domain model

### Pressure readings

A `PressureReading` is one hourly sample: `dateTime`, `pressureMsl` (mean sea level, what
detection uses), `surfacePressure`, and `fetchedDateTime` (when it was retrieved — used to
tell a stale forecast from a fresh one). Readings are stored keyed by `dateTime`, spanning
both history (actual past pressure) and forecast (predicted future pressure) in one table.

### Alert detection — `AlertDetector`

This is the core algorithm. Given a series of readings and a threshold in hPa, it finds every
"pressure event": a swing large enough that a user with that sensitivity should be told.

An `AlertWindow` is one event: `start`, `end` (the real extremes — when pressure last turned,
each side), `delta` (the qualifying swing), and `direction` (`DROP` or `RISE`).

Detection runs in four steps:

1. **Slide a 24-hour window** across every reading. For each window, take the difference
   between its max and min pressure. Keep the window if that difference clears the threshold.
   Direction is decided by which extreme comes first (peak before trough = a drop), not by
   comparing the window's first/last reading — that's fragile against noisy data and can
   produce duplicate, contradictorily-labelled windows for the same event.
2. **Merge overlapping windows sharing a direction** into one continuous event. A drop
   immediately followed by a rise stays two events — they're physiologically distinct.
3. **Pin start/end to the real pressure extremes** inside the merged window, so the displayed
   times are when pressure actually peaked/troughed, not the sliding-window boundaries. The
   event's `delta` stays the largest *24-hour* swing found in step 1 — not the swing between
   the pinned extremes, which can span a much wider merged window (50+ hours) and would report
   a number no threshold was ever tested against.
4. **Collapse any windows that still overlap** after pinning (possible with irregular data) so
   one physical event is never reported twice.

`AlertDetector.daysTouched(alert, zone)` and `eventDays(...)` answer "which calendar days does
this event touch" — used by the calendar and the day outlook. An event ending exactly at
midnight doesn't count as touching that day (nothing to show).

### Sensitivity — `AlertSensitivity`

Three presets, not a free value — anything outside this range is either constant noise or
never fires:

| Preset | Threshold |
|---|---|
| HIGH | 6 hPa |
| MEDIUM (default) | 8 hPa |
| LOW | 10 hPa |

Sensitivity runs opposite to the threshold: HIGH warns on the *smallest* drop.

### The shared alert use case — `PressureAlertUseCase`

The single definition of "which events count right now". Both the Today screen and the
notification scheduler go through this so they can never disagree (a banner claiming 3 events
while a notification announces a 4th would be worse than either being simply wrong).

Constants that matter:

- `RELEVANCE_HOURS = 24` — a finished event still counts as current for this long. Both
  screens look backward as well as forward (the Pressure chart's history half, the outlook's
  whole-day claim), so an event doesn't vanish the instant it ends. It does *not* license a
  *notification* — `alertsIn` still returns finished events, and callers that warn (the Today
  banner, the notification decider) filter those out themselves.
- `DETECTION_HISTORY_HOURS = 72` — how far back detection reads, deliberately more than the
  relevance window. An event already underway has its peak in the past; detecting inside a
  window that starts at `now - 24h` would clip the start to the window edge, so the reported
  start (and thus the event's identity — its work name, its notification id) would creep
  forward on every refresh, making one continuous event look like a new one each time.
- `FORECAST_DAYS = 7` — Open-Meteo's forecast horizon.
- `READING_INTERVAL_HOURS = 1` — a reading represents the hour it opens, not an instant.
  `coverageEnd()` is the last reading's time *plus* this interval — otherwise a 7-day hourly
  series (ending 23:00 on day 7) would look one hour short of actually covering day 7.

### Day outlook — `DayOutlook` / `OutlookRisk`

Answers "is this day worth watching?" for each of the next 7 days:

- `Elevated` — a qualifying event touches the day (regardless of direction or how much of the
  day it covers — the calendar marks a day as watched or not, nothing finer).
- `Clear` — forecast covers the whole day and nothing touches it.
- `Unknown` — the forecast doesn't reach the end of the day yet, so an event could still be
  hiding in hours with no data. A day is never called `Clear` on partial coverage.

A touched day is `Elevated` regardless of whether coverage reaches its end — the event is
already known and nothing later can un-know it.

### Symptom-free streaks — `SymptomFreeStreak`

Tracks how long since the last logged symptom (`MILD`/`AURA`/`MIGRAINE` — `CLEAR` doesn't
break a streak, and neither does a day the user never opened the app on; the log is opt-in, so
absence of a log isn't punished as a symptom day).

A "streak" is the run of days *strictly between* two events — two events on consecutive days
leave a streak of 0. `longest` also considers the run currently in progress (so a record being
set right now shows up immediately, not only once the next event ends it), measured from the
already-elapsed day count rather than "today", so a bogus future-dated entry can't produce a
negative run.

### Notification decisions — `AlertNotificationDecider`

Pure logic (no clock injection needed beyond what's passed in) deciding which events deserve a
notification and when:

- `LEAD_TIME = 12 hours` — how far ahead of an event a warning fires.
- `NOTIFICATION_LOOKBACK = 7 days` — how far back delivered warnings are checked for
  duplicates. Must outlast the longest event ever announced (a drop spread over two days is
  still one event on day two), or the record ages out and re-announces it.
- `AlertPhase`: `AHEAD` (not started — full lead time, or "now" if the lead time has already
  passed by the time the forecast surfaced it) vs `UNDERWAY` (already running — notify
  immediately, worded as a heads-up rather than a warning).
- Matching a delivered notification to a live event is by **overlap**, not by exact time match
  (`isSameEvent`): same direction, and the windows share at least a moment. A refreshed
  forecast that stretches or shifts an event by an hour is still the same event. This is the
  *same* rule `AlertDetector` uses to decide what one event is, deliberately, so the two layers
  can't disagree about identity.
- `decide()` returns the **complete** set of what should be scheduled — anything currently
  scheduled but missing from the result is cancelled. This is what makes a sensitivity change
  work in both directions: raise the threshold and an event drops its warning; lower it and a
  previously-too-small event gains one.

### Scheduling — `AlertNotificationScheduler` / `AlertReconcileMonitor`

`AlertNotificationScheduler.reconcile()` is a full rebuild, not an append: every call
recomputes the whole set of warnings that *should* be pending (via the decider) and makes
WorkManager's queue match it exactly — cancelling stale work, enqueueing (with `REPLACE`) new
or moved work. This single method serves three triggers: a refreshed forecast, a changed
sensitivity, and app start.

`AlertReconcileMonitor` watches `PressureRepository.refreshState` for `Updated` and reconciles
automatically. This exists because a fetch can be triggered from several places (a screen
opening, the hourly worker, a location change inside the repository itself), and having each
caller remember to reconcile afterward is exactly the arrangement that once let a location
change (Berlin → Kathmandu) leave stale warnings queued for the old city's weather until the
worker's next hourly tick. `PressureFetchWorker` *also* reconciles after its own fetch — the
two overlap deliberately, because a background run must not report itself finished before the
queue reflects what it fetched.

## Data layer

### Room schema

Three entities: `pressure_readings`, `symptom_entries`, `notified_alerts`. Current version: 3
(bump on every schema change, and export the schema — see `AppDatabase.kt`).

`notified_alerts` stores the **whole window** (start and end), not just the start, because
matching against a live forecast is by overlap (see above). Migration 2→3 added `endDateTime`;
rows from before that migration have their window collapsed onto their start (the honest
approximation — it still overlaps any forecast covering that moment).

### `PressureRepository` — fetch and refresh semantics

This is the most intricate file in the codebase; the design choices are load-bearing.

**Refresh joining.** Multiple callers can trigger a refresh concurrently (Today screen on
open, Pressure screen when stale, the hourly worker) — `refresh()` joins an already-running
fetch rather than starting a second one, because two independent fetches racing each other
write in whatever order they finish, and the series that lands last isn't necessarily the one
fetched last.

**`RefreshState`** is how a screen with an empty table tells *why* it's empty:
`InFlight` (a fetch is running or none has finished), `Updated` (fetched and stored),
`NoReadings` (fetch succeeded but carried nothing — e.g. a bad response; distinct from
`Updated` because Room delivers a write to observers several hops after the fetch returns, so
an empty table behind `Updated` usually just means "not landed yet"), `NoLocation` (nothing to
fetch for), `Failed`.

**Two refresh modes:**
- `KeepHistory` — the ordinary refresh. Keeps stored history, replaces the forecast.
- `ReplaceEverything` — triggered by a location change. The stored series describes a place
  the user has left, so it's all discarded rather than merged. This can't rely on
  `INSERT...REPLACE` overwriting old rows, because both weather APIs report hourly *local*
  time — moving between two cities whose local-hour grids don't align (e.g. a 45-minute
  offset) means old and new readings never collide on timestamp and would otherwise sit
  interleaved in the same table forever.

**Location changes are detected inside the repository** (`observeLocationChanges`), not
triggered by whoever writes the new location — the stored series belongs to "a place", so
noticing the place changed is the repository's job, not every caller's. Onboarding (no
location → first location) is handled by the same collector.

**Gap-filling.** The forecast endpoint returns 30 days of history for free
(`FORECAST_HISTORY_DAYS`). On first use, or after being offline a while, `gapFillIfNeeded`
backfills further back from the archive endpoint (`INITIAL_BACKFILL_DAYS = 60`). This is
skipped entirely on a location change — old history is irrelevant to the new place, and gets
deleted anyway.

**Cancellation safety.** Fetches use `catchingFailures` (a `runCatching` that rethrows
`CancellationException`) throughout. A superseded fetch (e.g. cancelled by a location change)
must unwind rather than be caught as an ordinary failure — if caught, the coroutine would
proceed to its own database write *after* the replacement fetch had already written, silently
resurrecting stale data for the wrong location.

**Empty responses are never written.** Both `storeForecast` and any write path treat "readings
came back empty" as a no-op on the stored data rather than clearing it — writing an empty
result would erase the forecast (or, on a move, the whole table) and leave the app worse off
than before it asked.

### `UserPreferences` (DataStore)

Falls back to defaults on a read failure, and **keeps retrying** rather than letting the flow
terminate (`retryWhen`, not `catch`) — a terminated flow silently stops every collector
forever, which for the location-change watcher inside `PressureRepository` means refetch-on-move
quietly stops working until the app restarts. Retries every 10s on `IOException` only; anything
else is a real bug and is left to surface.

## Background work

### `PressureFetchWorker`

Runs hourly (`REFRESH_INTERVAL_HOURS = 1` — matches Open-Meteo's own publish cadence;
WorkManager won't go below 15 min anyway). Each run: refresh the repository, then reconcile
notifications — even on a fetch failure, because the clock has moved regardless of whether new
data arrived, and an event that has since ended needs its stale warning pruned even from a run
that fetched nothing (e.g. a device that was offline for a day).

`runNow()` enqueues a one-off fetch immediately — used when the app opens, since the periodic
schedule doesn't fire until a full interval after being enqueued.

### `AlertNotificationWorker`

Fires at the delay `AlertNotificationScheduler` computed. Before posting, it **revalidates**:

1. Notifications still enabled? (Could've been switched off after scheduling.)
2. Event still in the live forecast? (The scheduler cancels events that drop out, but up to an
   hour can pass between reconciles.)
3. Already sent (matched by overlap, not by direction alone — an unrelated drop announced this
   morning must not silence a genuinely new one tonight)?
4. Re-derives `AlertPhase` from the current clock rather than trusting what the scheduler
   computed — work can run hours late, by which point an "ahead" event may have started.

Only on an actual successful post is a `NotifiedAlert` row written — a notification lost to a
revoked permission is retried, not silently recorded as delivered.

## Notifications

### Permission state — `NotificationPermissionDecider` / `NotificationPermissionMonitor`

Three states: `GRANTED`, `REQUESTABLE` (never asked — the runtime dialog will still work),
`BLOCKED` (nothing in-app can fix it; needs the system settings screen).

The decision order matters: "owed the dialog" (`REQUESTABLE`) is checked *before* "notifications
disabled" — a fresh Android 13 install reports notifications as off purely because the
permission hasn't been granted yet, and treating that as "user switched it off" would send
every new user to system settings instead of the in-app runtime prompt that would have just
worked.

### `AlertNotifier`

Posts to the tray; tapping opens the Pressure screen (not the specific alert — the screen
re-derives its own alert list from the same shared detection, so there's no way for a passed
copy to disagree with what the screen would compute anyway).

Notification IDs are stable per event (hashed on start-minute + direction's *wire name*, not
the Kotlin enum's hash code, which differs across processes) so a re-posted warning replaces
the old tray entry instead of stacking a duplicate.

## UI layer

### Navigation

Bottom tabs: **Today**, **Pressure**, **Calendar**, **Settings**, plus **Onboarding** (first
run) and **LogEntry** (modal-ish, reached from the FAB or a calendar day). Switching tabs
preserves each tab's back stack and scroll position (`saveState`/`restoreState`).

### Today screen

The landing screen: a 7-day outlook strip, a banner for the nearest live event, and the
symptom-free streak.

`OutlookGap` explains an empty outlook without guessing — it's read from `RefreshState` plus
whether any readings exist, rather than inferred from data shape, since an empty table looks
identical whether a fetch is in flight, failed, or simply hasn't been asked for anything. A
forecast that covers today but doesn't yet stretch a full week is *not* a gap — that's normal;
the outlook strip just fades its tail (`OutlookRisk.Unknown` days), rather than reporting a
failure.

The banner leads with the earliest still-live event — the one underway if there is one, since
that's the event the user is actually in the middle of.

### Pressure screen

The chart plus a list of events. Three time ranges (`TimeRange`): 24 hrs / 48 hrs / 7 days,
each pairing a `ChartStep` (how much time one point covers) with a `ChartRendering` (`Line` or
`MinMaxBand`). A min/max band is only meaningful over a long enough step to have moved
(collapses to a thick line at 3-hour resolution), so only the 7-day/daily range uses it.

### Chart windowing — `ChartWindow` / `ChartStep`

The single definition of where a moment in time lands on the chart's x-axis. The chart always
draws exactly 8 points (`POINT_INDICES = 0..7`): 3 steps of history before "now" (index 3,
`ANCHOR_INDEX`), 4 steps ahead. The step size is the only thing that changes between ranges;
the point *count* never does.

This lives in `domain`, not beside the composable, because callers outside the chart (a
ViewModel deciding which alerts the current chart window can even show) need the exact same
math — re-deriving it from a step count in the view layer would both duplicate it and make it
untestable.

Sub-day steps snap "now" down to the step boundary; the daily step instead snaps to local noon,
so the "now" marker sits near the current day's label rather than drifting toward the next
day's during the morning.

### Chart rendering pipeline

Split cleanly into two files by testability:

- **`PressureChartData.kt`** — pure functions, fully unit tested, no `Canvas`. Computes what to
  draw: `stepRanges` (min/max per step), `renderingFor` (falls back from `MinMaxBand` to `Line`
  if there's too little data for a real band — fewer than 2 steps with a range), `seriesEdges`
  (both renderings produce a *pair* of series — a line is modeled as a band whose edges
  coincide — which is what lets Vico animate smoothly between a line and a band rather than
  cross-fading two different shapes), and `edgeOffsetSampler` (how far the series would
  continue past the last real point, out to the plot edge — purely cosmetic, since both chart
  layouts reserve a margin around the actual data for axis labels).

- **`PressureChartOverlay.kt`** — the `Canvas`-based half: risk shading behind the line,
  min/max band fill, the plot-edge overhang, and the dashed "now" line. Uses Vico's
  `Decoration` API. Notably, `TweeningLineChart` captures Vico's own in-progress tween
  positions each frame and publishes them, because a `Decoration` only ever sees the *settled*
  destination model, not the frames of animation leading to it — without this capture, the
  overlay's shading would freeze at the old position and jump to the new one, rather than
  travelling with the line.

  The risk-shading fade is deliberately decoupled from the line's own tween: since the risk
  windows are time-based rather than point-based, they can't be smoothly interpolated the way
  points can, so instead of jumping instantly to a new width/position they fade out and back in
  across the tween's back half.

### Calendar & severity model

`Severity` enum (`CLEAR, MILD, AURA, MIGRAINE`) is declared in worsening order deliberately —
every UI list of severities (legend, entry picker) presents them in declaration order, so
adding a new severity later must preserve the progression.

`DayMarker` is the calendar's per-day visual: shape communicates pressure risk (rounded square
= normal, circle = high-risk — a 50%-corner rounded square *is* a circle, which is what lets
the shape *morph* between the two rather than swap), fill color communicates logged severity,
and a colored ring communicates "today" (heavier ring, always wins visual priority over the
high-risk ring if both apply — by the time a day is drawn as a circle it's already said
"high risk"; today's ring is the more useful thing to also show).

### Settings screen

Sensitivity picker, notification toggle (tracks *intent*, separate from whether the OS will
actually deliver — see permission state above), and a debug-only section (hidden behind
`BuildConfig.DEBUG`) for forcing a fetch, previewing the next alert notification without
recording it as sent, and clearing notification history (so a scenario can be re-tested
without waiting out the dedup window).

## Testing strategy

See `README.md` for how to run the suites. Design-relevant points:

- **`MockDataInterceptor`** generates deterministic pressure curves (no noise) sized precisely
  relative to the sensitivity presets, so a scenario's alert count is guaranteed rather than
  probabilistic. `THREE_EVENTS` is laid out so the three sensitivity presets each peel off a
  different subset (High shows all 3, Medium 2, Low 1); `FOUR_EVENTS` exceeds the Alerts card's
  display cap (one more event than the alert palette has colors for) at every sensitivity, to
  exercise the "N more" truncation independent of threshold.
- Detection, notification decisions, chart data math, and the ViewModels are all pure/JVM
  testable because they live in `domain`/`ui/components` data files with no Android
  dependencies — the `Canvas`-drawing half of the chart is the one part that isn't.

## Key invariants

A checklist of rules that, if broken, tend to reintroduce bugs that were specifically fixed
before:

- **One shared detection path.** Anything that lists or warns about events must go through
  `PressureAlertUseCase` — never re-run `AlertDetector` independently. Two independent call
  sites will eventually disagree.
- **Event identity is overlap, not exact time.** `AlertDetector`'s merge step and
  `AlertNotificationDecider.isSameEvent` must keep using the same definition of "same event",
  or a refreshed forecast can silently duplicate or drop a warning.
- **`reconcile()` must stay a full rebuild, not an append.** It's what makes a sensitivity
  change (or an event dropping out of the forecast) correctly *remove* stale warnings, not just
  add new ones.
- **Never write an empty fetch result over stored data.** An empty/failed response must leave
  the database untouched.
- **A location change replaces the whole table**, never relies on row-level overwrite —
  different cities' local-hour grids don't align.
- **Direction is persisted by wire name (`"drop"`/`"rise"`), never by enum ordinal or `name`.**
  Rows and scheduled work outlive app updates; renaming a Kotlin constant must never orphan
  stored data.
- **`RefreshState`/`OutlookGap` distinctions exist because an empty UI state is ambiguous** —
  don't collapse them back into a single "loading" boolean; the whole point is telling the user
  *why* there's nothing to show.
