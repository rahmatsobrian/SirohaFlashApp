# Siroha Flash Tool (Android app)

A native Android port of the [SirohaFlashTool](.) Termux/bash script (all 10
menu modules) — Material 3, Material You dynamic color (+ AMOLED/light/dark/
system theme control), Android 10 (API 29) through 16, running with **root,
Shizuku, or a from-scratch ADB-over-USB client** as the privilege backend.

## What's actually implemented

- Material3 + dynamic color theme, with a Settings-page picker for
  System / Light / Dark / **AMOLED** (true-black surfaces) and a toggle for
  Material You itself.
- Root backend via [libsu](https://github.com/topjohnwu/libsu) and a
  Shizuku backend via a bound `UserService` (AIDL). The app tries root
  first, then Shizuku, automatically (`core/ExecutorProvider.kt`).
- **ADB-over-USB, implemented from scratch** (`core/AdbUsbClient.kt` +
  `core/AdbKeyManager.kt`) — message framing, the CNXN/AUTH handshake, RSA
  keypair generation, Android's own non-standard public-key wire format
  (`RSAPublicKey` C struct, base64-encoded), `adb shell` command execution,
  **and ADB sideload** (the `sideload:<size>` block-request protocol used
  for pushing a ZIP to a device already in recovery — distinct from plain
  shell commands in that the device drives the exchange by requesting
  specific 64KiB blocks). Backs the Samsung/SPRD FRP methods and the
  Sideload ZIP buttons on the Fastboot/A-B/FRP screens.
  ⚠️ **None of this has been exercised against a real device's "Allow USB
  debugging?" dialog or a real recovery** — there was no USB hardware
  available in the environment this was written in. The handshake's
  Montgomery math (n0inv, R² mod N) is standard and should be correct; the
  public-key struct layout and the sideload block-request format are both
  reconstructed from memory of the AOSP C source. If first-time pairing
  never completes (device keeps re-sending AUTH TOKEN after you send your
  public key) or sideload stalls/errors partway through, these are the
  first places to check — send back what you see (the in-app Logs screen
  has protocol-level detail) and it can be debugged from there.
- `qdl` binaries bundled as `jniLibs/<abi>/libqdl.so`.
- **QDL Flash (EDL 9008)** — loader/rawprogram/patch XML picker, eMMC/UFS
  storage choice, and a **partition checklist** parsed from the rawprogram
  XML.
- **Bypass UBL — Redmi 4A (rolex)**.
- **Fastboot Flash Tool, GSI ROM Tool, A/B Partition Tool** — built on a
  from-scratch fastboot-over-USB protocol client (`core/FastbootUsbClient.kt`).
  Same hardware-testing caveat as ADB above: written carefully against the
  public protocol spec, not yet verified against real EDL/fastboot hardware.
- **FRP Remove Tool** — SPRD method via fastboot (`erase persist`); Samsung
  and SPRD/MTK methods via the ADB client.
- **Requirements & Status**, **USB/OTG Fix**, **Guide**, **About** screens.
- **Logs screen** — structured, timestamped, exportable via the share sheet.
- CI workflow — builds debug+release, tests, lint, zero repo secrets,
  self-generates and commits the Gradle wrapper if missing.

## Known gaps

- **wipe-super** — the real fastboot host tool's multi-command sequence for
  resizing dynamic partitions from a `super_empty.img`, not a single
  protocol command. `deleteLogicalPartition` for named partitions works
  since that IS a single command.
- **MiTool** (Xiaomi unlock/flash/assistant) needs Python + Xiaomi's
  official account-based unlock API — out of scope for a native rewrite.
  Original scripts kept in `mitool_reference/`, not compiled into the app.
- **`menu_install`** in flash.sh was Termux package management — replaced
  with **Requirements & Status** (checks root/Shizuku/USB/qdl instead).
- Nothing in this app has been compile-tested or hardware-tested in the
  environment that wrote it (no Android SDK, no USB access). CI is the
  first real compile; a real device is the first real protocol test.

## Getting logs back to me

GitHub Actions can't push anything into this chat automatically. After a
run: download the **build-log** artifact, open `full-build-log.txt`, paste
the relevant part back. For runtime bugs, use the in-app **Logs** screen's
share button instead — it has protocol-level detail the build log won't.

## Local setup

1. Push to GitHub — the first CI run generates and commits the Gradle
   wrapper automatically. Or open in Android Studio, which does the same
   locally.
2. Run on a device/emulator, Android 10–16.
3. Root: grant superuser access in Magisk/KernelSU/APatch.
4. Shizuku: install [Shizuku](https://shizuku.rikka.app/), start it, grant
   permission from Settings → "Use Shizuku".
5. Fastboot/EDL/ADB features need a real target device connected via USB
   OTG — an emulator can't exercise USB host mode. For ADB, the first
   connection to any given target needs you to tap "Allow" on that
   device's own screen after this app sends its public key.

## Project layout

```
app/src/main/
├── jniLibs/<abi>/libqdl.so       ← qdl binaries (renamed from bin/<abi>/qdl)
├── assets/bypass-ubl/...         ← Redmi 4A firehose loader + XML maps
├── aidl/.../IShellService.aidl   ← Shizuku UserService contract
└── java/com/siroha/flashtool/
    ├── core/                     ← shell exec, fastboot/ADB-over-USB, binary/asset mgmt, theme prefs
    ├── data/                     ← LogRepository
    └── ui/{theme,navigation,screens,components}/
mitool_reference/                 ← original Python scripts, not compiled in
```
