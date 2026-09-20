# TaskManager (m-escu fork)

**Task Manager** is a tool for Android inspired by the GNOME system monitor,
originally by [RohitKushvaha01](https://github.com/Rohitkushvaha01).
This is the personal fork maintained by **m-escu** — monetization-free,
100% offline, with a rewritten daemon protocol, battery/network monitoring,
a dual-mode home-screen widget and threshold alerts.

[![CI](https://github.com/m-escu/TaskManager/actions/workflows/android-ci.yml/badge.svg)](https://github.com/m-escu/TaskManager/actions/workflows/android-ci.yml)

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
- **Battery monitoring**: live level/current/temperature stats, live
  sliding-window graphs (level + signed current, Live default), a 30-day
  local history with 24 h/7 d/30 d views, reset, and threshold alerts
  (drain current / temperature, notification or toast, with sustain
  windows). Every live chart in the app — CPU, RAM, GPU, network,
  battery — polls at the same Settings -> Graph -> update delay knob.
- **Network monitoring**: per-interface live rates, per-app traffic totals
  and live per-app transfer rates (usage access is self-granted via
  root/Shizuku when available).
- **Home-screen widget + QS tile**: dual mode — ephemeral updates on a
  configurable interval, or a 1 Hz live session toggled by a Quick
  Settings tile; centered, auto-shrinking text layout. The tile is the
  only Android-12-legal way to start that service from outside the app
  (widget/boot receivers may not call startForegroundService), and the
  service it controls is also what evaluates the battery alerts in the
  background — a tile session or the permanent notification keeps them
  armed.
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

# License

This fork is distributed under the same [Apache License 2.0](LICENSE) as the
upstream project.

- Upstream **Task Manager** © [Rohitkushvaha01](https://github.com/Rohitkushvaha01);
  the `LICENSE` file is carried forward verbatim, and the original author is
  credited above and in the app's About screen.
- Modifications and new components in this fork (daemon protocol v2, battery
  and network monitoring, the widget and QS tile, threshold alerts, tests)
  © m-escu.
- The previously paywalled functionality was **independently re-implemented**
  for this fork. Upstream's monetization layer (AdMob, billing, Play
  services) was never part of the public source — it was merged in from a
  separate closed component at upstream build time and is entirely absent
  here. The fork opens no sockets (no INTERNET permission;
  `ACCESS_NETWORK_STATE` only reads network state).
- Not affiliated with, endorsed by, or sponsored by the upstream author;
  the upstream name is used solely for attribution.
- All changes relative to upstream are documented in
  [CHANGELOG.md](CHANGELOG.md). No `NOTICE` file exists upstream, so none
  is required.

## Find this app useful? :heart:
Support the original by giving it a star :star:
