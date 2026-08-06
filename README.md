# Siroha Flash Tool (Android app)

A native Android port of the [SirohaFlashTool](.) Termux/bash script — Material 3,
Material You dynamic color, works Android 10 (API 29) through Android 16, and
runs with **either root or Shizuku** (no root) as the privilege backend.

## What's actually implemented

- Material3 + dynamic color theme (`ui/theme/Theme.kt`) — Material You on
  Android 12+, a static brand palette fallback on 10/11.
- Root backend via [libsu](https://github.com/topjohnwu/libsu) and a
  **Shizuku backend** via a bound `UserService` (AIDL) — see
  `core/RootShellExecutor.kt` / `core/ShizukuShellExecutor.kt`. The app tries
  root first, then Shizuku, automatically (`core/ExecutorProvider.kt`).
- The four `qdl` binaries from `bin/<abi>/qdl` are bundled as
  `jniLibs/<abi>/libqdl.so` — this is the standard trick for shipping a raw
  executable in an Android app so it survives Android 10+'s W^X restrictions
  on app-private storage, without needing a runtime `chmod`.
- **QDL Flash (EDL 9008)** screen — pick a firehose loader + rawprogram/patch
  XML via the system file picker, streams `qdl`'s live output into the Logs
  screen.
- **Bypass UBL — Redmi 4A (rolex)** screen — one-tap version of flash.sh's
  menu 7, using the bundled `bypass-ubl/Redmi4A-rolex/*` assets.
- **Logs screen** — every operation writes structured, timestamped log lines
  to an in-app buffer AND a `.log` file, which you can share/export via the
  system share sheet (`androidx.core.content.FileProvider`).
- CI workflow (`.github/workflows/android-build.yml`) that builds debug +
  release, runs unit tests and lint, and **requires zero repository secrets**
  (release is signed with the debug keystore committed at
  `app/debug.keystore` — fine for testing, replace before a real Play Store
  release).

## Known gaps — please read before calling this "done"

The original tool bundled a lot more than `qdl`. I did not fabricate support
for pieces the zip didn't actually include a binary for, rather than silently
pretending they work:

- **Fastboot Flash / GSI ROM / FRP Remove** menus: the original `flash.sh`
  shells out to a `fastboot` binary, but **no fastboot binary was in the
  uploaded zip** (only the four `qdl` EDL binaries were). `core/FlashOperations.kt`
  has an explicit, honest stub (`removeFrp()`) that fails loudly instead of
  silently no-opping. To finish this: bundle a `fastboot` executable per ABI
  the same way `qdl` is bundled (`jniLibs/<abi>/libfastboot.so`) and wire up
  the equivalent calls.
- **MiTool** (`mitool/*.py`): these are Python scripts. Running them natively
  on Android needs an embedded Python runtime — [Chaquopy](https://chaquo.com/chaquopy/)
  is the standard choice, but it's a separate Gradle plugin with its own
  setup and wasn't added here to keep the build you're getting buildable
  without extra unverified config. The scripts are preserved in
  `mitool_reference/` (not compiled into the app) so nothing is lost.
- I could **not compile or run this project** in the environment that
  generated it (no internet access, no Android SDK). Everything here is
  written carefully and should build, but the GitHub Actions run is your
  real first compile — see "Getting the log back to me" below.

## Getting the log back to me

GitHub Actions can't push anything into this chat by itself — there's no
live connection between your repo and this conversation. After a run:

1. Open the Actions run → download the **build-log** artifact (or read the
   step summary at the bottom of the run page).
2. Paste `full-build-log.txt`'s contents (or just the error) back into the
   chat and I'll fix it.

## Local setup

1. This repo ships `gradle/wrapper/gradle-wrapper.properties` but not the
   wrapper jar/`gradlew` scripts (they couldn't be generated offline in the
   sandbox that built this). Two ways to get them:
   - **Push to GitHub and let CI do it**: the first Actions run generates
     `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` with a
     real `gradle wrapper` invocation, then commits them straight back to
     your repo (uses the workflow's built-in `GITHUB_TOKEN` — no secret you
     need to add). Pull the branch afterward and they'll be there.
   - **Or just open the project in Android Studio** (Koala/2024.1+) — it
     generates the same files locally on first sync.
2. Run on a device/emulator running Android 10–16.
3. For root testing: grant the app superuser access in Magisk/KernelSU/APatch.
4. For Shizuku testing: install [Shizuku](https://shizuku.rikka.app/), start
   it (wireless-debugging pairing on Android 11+, or one `adb` command from a
   PC), then grant this app permission from Settings → "Use Shizuku".

## Project layout

```
app/src/main/
├── jniLibs/<abi>/libqdl.so       ← qdl binaries (renamed from bin/<abi>/qdl)
├── assets/bypass-ubl/...         ← Redmi 4A firehose loader + XML maps
├── aidl/.../IShellService.aidl   ← Shizuku UserService contract
└── java/com/siroha/flashtool/
    ├── core/                     ← shell execution, binary/asset management
    ├── data/                     ← LogRepository
    └── ui/{theme,navigation,screens}/
```
