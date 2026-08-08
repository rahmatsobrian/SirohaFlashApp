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
  GSI screen includes a **"Wipe Super"** action (deletes the optional
  dynamic partitions — product/system_ext/odm — that every community GSI
  guide has you clear; see "Known gaps" for what this deliberately doesn't
  do).
- **FRP Remove Tool** — SPRD method via fastboot (`erase persist`); Samsung
  and SPRD/MTK methods via the ADB client.
- **MiTool** — 2 of the original's 4 tools, reimplemented natively:
  **Flash Fastboot ROM** (pick a folder, every `*.img` gets fastboot-flashed
  to a same-named partition — exactly how Xiaomi's own `flash_all.sh`
  works, so no per-device partition list needed) and **Firmware Content
  Extractor** (download a ROM ZIP, pull out one named file). The other two
  — **Unlock Bootloader** and **Mi Assistant** — are NOT here; see "Known
  gaps" for why that's a hard boundary, not a "not yet."
- **Requirements & Status**, **USB/OTG Fix**, **Guide**, **About** screens.
- **Logs screen** — structured, timestamped, exportable via the share sheet.
- CI workflow — builds debug+release, tests, lint, zero repo secrets,
  self-generates and commits the Gradle wrapper if missing.

## Known gaps

- **wipe-super**, real version — the actual `fastboot` host tool parses a
  `super_empty.img`'s binary `liblp` metadata and reconciles the on-device
  dynamic-partition table against it command-by-command; that metadata
  format is a substantial parser in its own right and isn't implemented.
  The GSI screen's "Wipe Super" button instead deletes the well-known
  *optional* partitions (product/system_ext/odm, both slots) that GSI
  guides always have you clear — correct for the common case, not a
  general-purpose super_empty.img-driven wipe.
- **MiTool's Unlock Bootloader and Mi Assistant** — genuinely not
  implementable, not just deferred. Unlock Bootloader needs Xiaomi's
  private, account-authenticated unlock servers. Mi Assistant talks to an
  external `miasst_termux` binary that was *never open-sourced even in the
  original project* — there's no protocol spec to reimplement against at
  all, unlike ADB/fastboot which are public. Reference scripts for all four
  original tools are kept in `mitool_reference/` regardless.
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
