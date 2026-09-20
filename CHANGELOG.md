# Changelog (m-escu fork)

All notable changes made in this fork on top of
[RohitKushvaha01/TaskManager](https://github.com/RohitKushvaha01/TaskManager).
Every release below is the CI-built debug APK attached to the matching
GitHub Actions run (no store releases). Format roughly follows
[Keep a Changelog](https://keepachangelog.com/), newest first.

## [Unreleased] — Phase 6: test & docs infrastructure

- Instrumented-test CI job: API 34 emulator on GitHub Actions runs the
  `:main` androidTest suite on every push (first real Android-runtime
  coverage: Room/SQLite, activity lifecycle, degraded no-daemon launch).
- New instrumented tests: `BatteryDatabaseTest` (history round trip,
  ascending `since()`, prune window, clear-all, -1 sentinel survival) and
  `AppLaunchSmokeTest` (launch + 4 s startup soak with no daemon/root).
- New JVM unit tests: `WidgetStatsTest` (locks down the vendor-current sign
  conventions that caused the fork13/fork16 bugs) and
  `LocaleCompletenessTest` (every default string key must exist in all 5
  locales — the fork keeps them in lockstep by hand; now enforced by CI).
- Fixed the never-executed library androidTest template (wrong package
  assertion), removed the dead app-module androidTest stub.
- README rewritten for the fork; this CHANGELOG added.

## [1.15.0-fork17] (76)

- Live graphs unified: the battery and net live charts now poll at
  Settings -> Graph -> update delay — the same knob the CPU/RAM/GPU charts
  already used — instead of hardcoded 1 s loops.
- Unplug artifact fixed: fuel gauges need a measurement cycle after the
  charger detaches; the first reading across a charging-state transition
  still reflects the previous state (charging at 1.5 A briefly showed as
  -1.5 A drain). The live current series now holds for 3 s after a
  transition instead of plotting it.

## [1.14.0-fork16] (75)

- Live battery graphs (net-tab pattern): Live/24h/7d/30d chips, Live
  default, 1 Hz sliding windows for level (%) and signed current (mA).
- History current-curve fix: the daemon passes the vendor-raw current sign
  (charging reads NEGATIVE on some devices), so the old "< 0 = unknown"
  check flattened every charging sample on those devices; the charging
  flag now decides direction, -1 stays the only unknown sentinel.
- "Reset graph data" also blanks the live windows (Vico-safe: no new
  producer in a composed slot).

## [1.13.0-fork15] (74)

- Battery threshold alerts: warn (notification/toast/both) when drain
  current or temperature stays above a configurable threshold for a chosen
  duration; repeats while sustained, evaluated by the live monitor.
- Net tab defaults to Live per-app rates (24 h totals became opt-in).
- Battery graph reset crash fixed (Vico forbids producer swaps in a
  composed slot — the whole chart subtree is rebuilt via key() instead).
- Widget text layout rework: centered columns, label above value,
  auto-shrinking text — no more clipping/overlap on the RAM column.

## [1.12.0-fork14] (73)

- Battery graph reset ("Reset graph data" + confirm dialog).
- Permanent notification self-restores at app launch (not only on toggle).
- New "Start at boot" setting (boot receiver respects both prefs).
- Per-app lifetime-average CPU% (chip + sortable + info card) alongside
  the instantaneous CPU%.
- "Confirm stop" reworked into a real master switch for the kill dialog
  regardless of the configured default action.

## [1.11.0-fork13] (72)

- Battery charging current no longer N/A on vendors that read negative
  while charging (+/- formatting shared with the widget).
- Net tab fixed: the rates chart received no data (wrong screen-index
  gate) and the per-app list showed period totals only — a Live chip now
  shows real per-app transfer rates (3 s diffs, 10 min rebase).
- Widget refresh interval actually worked now (the scheduler cancelled
  the PendingIntent it had just re-armed — alarms were silently dropped).
- QS tile and permanent notification decoupled: widget 1 Hz pushes are
  gated on an active tile session.
- Settings "Widget" renamed "Widget and notification"; system-app kill
  button enabled by default; sort by CPU time added.

## [1.10.0-fork12] (71)

- Color-coded process list, kill-flow rework (default action + forced
  confirm for system apps), battery temperature, signed current on the
  widget, standalone permanent notification surviving reboot.

## Earlier fork rounds (4–11) — screens & monitor build-out

- Battery screen: daemon BATTERY_PING stats card, health/cycles/design
  capacity, Room-backed history with a 30-day rolling window and 24 h /
  7 d / 30 d period views, 1-minute sampling while open.
- Network screen: per-interface live rates, per-app traffic totals via
  NetworkStatsManager with root/Shizuku usage-access self-grant, public
  non-reflective querySummary API.
- Home-screen widget: dual mode (ephemeral updates vs 1 Hz live
  foreground-service mode), configurable non-live refresh interval,
  QS tile for live mode, boot self-heal.
- Process features: CPU% vs CPU-time toggles, PSS/RSS, per-core usage,
  process info polling via id-correlated requests.

## [fork1 – fork3] — fork foundation

- Monetization stripped (AdMob, billing, play services, INTERNET
  permission): the app is 100% offline; rebranded to com.mescu.taskmanager;
  signing fixed; About screen made network-free with local attribution.
- Daemon protocol v2: HELLO handshake (protocol/caps/version), request-id
  correlation replacing racy type-matching, background CPU sampler,
  windowed per-process CPU deltas, std::regex removed from hot paths.
- Daemon features: KILL_GRACEFUL (SIGTERM -> SIGKILL with grace period,
  PID-recycling safe), CORE_PING (per-core usage/frequency), BATTERY_PING
  (power_supply fallback chain, mA/µA heuristic), PSS_PING (smaps_rollup),
  SUBSCRIBE/UNSUBSCRIBE push mode, ERROR responses for unknown commands.
- Kotlin: typed DaemonClient (caps-aware kill policy), shared kill
  dialogs, daemon status page, legacy-daemon banner.
- Test/CI foundation: host-side daemon integration suite (protocol tests
  against real /proc), GitHub Actions with daemon-tests + android-build
  jobs and a debug APK artifact.
