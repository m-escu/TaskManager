# TaskManager (m-escu fork)

**Task Manager** is a tool for Android inspired by the GNOME system monitor,
originally by [RohitKushvaha01](https://github.com/Rohitkushvaha01).
This is the personal fork maintained by **m-escu** — monetization-free,
100% offline, with a rewritten daemon protocol, battery/network monitoring,
a dual-mode home-screen widget and threshold alerts.

[![CI](https://github.com/m-escu/TaskManager/actions/workflows/android-ci.yml/badge.svg)](https://github.com/m-escu/TaskManager/actions/workflows/android-ci.yml)
[<img src="https://shields.rbtlog.dev/simple/com.rk.taskmanager" alt="RB shield">](https://shields.rbtlog.dev/com.rk.taskmanager)

> [!IMPORTANT]
> Task Manager requires Shizuku/root to work.

# What this fork changes

- **No monetization, no network.** AdMob, billing, play services and the
  INTERNET permission are stripped — every network-touching code path was
  removed, not just disabled. All data (battery history, traffic stats)
  stays on the device.
- **Daemon protocol v2** (`taskmanagerd`): HELLO handshake with
  capability/version negotiation, request-id correlation (replaces the old
  racy type-matching), windowed per-process CPU deltas, graceful kill
  (SIGTERM → SIGKILL with a configurable grace period, PID-recycling safe),
  per-core stats, battery/power_supply probing, PSS via smaps_rollup,
  push subscriptions.
- **Battery monitoring**: live level/current/temperature stats, live 1 Hz
  sliding-window graphs, a 30-day local history with 24 h/7 d/30 d views,
  reset, and threshold alerts (drain current / temperature, notification
  or toast, with sustain windows).
- **Network monitoring**: per-interface live rates, per-app traffic totals
  and live per-app transfer rates (usage access is self-granted via
  root/Shizuku when available).
- **Home-screen widget + QS tile**: dual mode — ephemeral updates on a
  configurable interval, or a 1 Hz live foreground-service mode controlled
  by a Quick Settings tile; centered, auto-shrinking text layout.
- **Process management**: color-coded list, terminate vs force-kill with
  confirm dialogs, CPU% / CPU-time / lifetime-average sorting, PSS/RSS,
  per-core usage.
- **Kill policy UX**, daemon status page, legacy-daemon banner,
  start-at-boot, standalone permanent notification.
- **Tests & CI**: daemon host-integration suite, JVM unit tests
  (sign-convention regression net, locale completeness), and emulator
  instrumented tests (Room history round trips, launch smoke) — all run on
  every push.

Locales: English, Русский, 中文, Türkçe, Português (BR) — kept in
lockstep by a CI-enforced completeness test.

# Download

- **This fork**: grab the debug APK from the latest green
  [Actions run](https://github.com/m-escu/TaskManager/actions?query=is%3Asuccess)
  (artifact `TaskManager-debug-apk`).
- **Original app**: available on
  [Google Play](https://play.google.com/store/apps/details?id=com.rk.taskmanager).

# Building

```bash
# JDK 21 + Android SDK (with NDK & CMake for the daemon) required
./gradlew :app:assembleDebug          # APK
./gradlew :main:testDebugUnitTest     # JVM unit tests
./gradlew :main:connectedDebugAndroidTest   # instrumented tests (device/emulator)
bash taskmanagerd/host_tests/build_host.sh && python3 taskmanagerd/host_tests/test_daemon.py
```

# Screenshots
<div>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/01.jpg" width="32%" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/02.jpg" width="32%" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/03.jpg" width="32%" />
</div>
<div>
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/04.jpg" width="32%" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/05.jpg" width="32%" />
  <img src="/fastlane/metadata/android/en-US/images/phoneScreenshots/06.jpg" width="32%" />
</div>

# Credits

- [Rohitkushvaha01](https://github.com/Rohitkushvaha01) — original author
  ([Play Store](https://play.google.com/store/apps/details?id=com.rk.taskmanager))
- m-escu — fork maintainer; see [CHANGELOG.md](CHANGELOG.md) for everything
  this fork changes.

## Find this app useful? :heart:
Support the original by giving it a star :star:
