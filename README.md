# Siroha Flash Tool (Android app)

A native Android port of the [SirohaFlashTool](.) Termux/bash script (all 10
menu modules) — Material 3, Material You dynamic color, Android 10 (API 29)
through 16, running with **either root or Shizuku** (no root) as the
privilege backend.

## What's actually implemented

- Material3 + dynamic color theme — Material You on Android 12+, static
  brand palette fallback on 10/11.
- Root backend via [libsu](https://github.com/topjohnwu/libsu) and a
  Shizuku backend via a bound `UserService` (AIDL). The app tries root
  first, then Shizuku, automatically (`core/ExecutorProvider.kt`).
- `qdl` binaries bundled as `jniLibs/<abi>/libqdl.so` (survives Android
  10+'s W^X restrictions without a runtime `chmod`).
- **QDL Flash (EDL 9008)** — pick loader/rawprogram/patch XML, choose
  eMMC/UFS storage, and a **partition checklist** parsed straight out of the
  rawprogram XML (`core/RawProgramXml.kt`) so you can flash a subset instead
  of everything.
- **Bypass UBL — Redmi 4A (rolex)**.
- **Fastboot Flash Tool, GSI ROM Tool, A/B Partition Tool, FRP Remove
  (fastboot half)** — all built on a **from-scratch fastboot-over-USB
  protocol client** (`core/FastbootUsbClient.kt`), because the uploaded zip
  never included a `fastboot` binary and none could be downloaded in the
  sandbox that built this. It implements the public AOSP wire protocol
  (command/response framing, the DATA download handshake, chunked bulk
  transfer) directly against `UsbManager`/`UsbDeviceConnection`.
- **Requirements & Status**, **USB/OTG Fix**, **Guide**, **About** screens.
- **Logs screen** — structured, timestamped, exportable via the share sheet.
- CI workflow — builds debug+release, tests, lint, **zero repo secrets**,
  self-generates and commits the Gradle wrapper if missing.

## Known gaps — read before assuming something "just works"

- **The fastboot-over-USB protocol client has not been tested against real
  hardware.** It's written carefully against the public protocol spec, but
  there was no EDL/fastboot device or USB access available in the sandbox
  that built this. Treat it as "should be correct," test cautiously, and
  send back logs/errors from a real run so any protocol-level mistakes can
  get fixed.
- **ADB-over-USB is not implemented** — `ADB Sideload` (Fastboot/A-B
  screens) and the Samsung/SPRD FRP methods (`menu_frp` options 2–3 in
  flash.sh) all shell commands into the *target* device over ADB, which
  needs the full ADB protocol (RSA key auth handshake) — a separate,
  larger undertaking from fastboot's simple command/response protocol.
  Only the fastboot-based FRP method (`erase persist`) is wired up.
- **`wipe-super`** (flash.sh's GSI menu option 8) isn't a raw fastboot
  protocol command — the real `fastboot` host tool parses a
  `super_empty.img` and issues a sequence of create/resize/delete logical
  partition commands. Not reimplemented; `deleteLogicalPartition` for named
  partitions is, since that *is* a single protocol command.
- **MiTool** (Xiaomi unlock/flash/assistant) needs Python + Xiaomi's
  official account-based unlock API — out of scope for a native rewrite in
  this pass. Original scripts kept in `mitool_reference/` for reference,
  not compiled into the app.
- **`menu_install`** in flash.sh was Termux package management (`pkg
  install adb/python3/...`) — meaningless once this is a native APK, so it
  was replaced with **Requirements & Status** (checks root/Shizuku/USB/qdl
  presence instead of installing packages).

## Getting logs back to me

GitHub Actions can't push anything into this chat automatically. After a
run: download the **build-log** artifact, open `full-build-log.txt`, paste
the relevant part back into the chat.

For runtime bugs (something crashes or behaves wrong on a real device):
open the in-app **Logs** screen, tap Share, and paste that here too —
it has timestamped detail the build log won't.

## Local setup

1. Push to GitHub — the first CI run generates and commits the Gradle
   wrapper (`gradlew`, `gradle-wrapper.jar`) automatically, no secrets
   needed. Or open in Android Studio, which does the same locally.
2. Run on a device/emulator, Android 10–16.
3. Root: grant superuser access in Magisk/KernelSU/APatch.
4. Shizuku: install [Shizuku](https://shizuku.rikka.app/), start it, grant
   permission from Settings → "Use Shizuku".
5. Fastboot/EDL features need a real target device connected via USB
   OTG — an emulator can't exercise USB host mode.

## Project layout

```
app/src/main/
├── jniLibs/<abi>/libqdl.so       ← qdl binaries (renamed from bin/<abi>/qdl)
├── assets/bypass-ubl/...         ← Redmi 4A firehose loader + XML maps
├── aidl/.../IShellService.aidl   ← Shizuku UserService contract
└── java/com/siroha/flashtool/
    ├── core/                     ← shell exec, fastboot-over-USB, binary/asset mgmt
    ├── data/                     ← LogRepository
    └── ui/{theme,navigation,screens}/
mitool_reference/                 ← original Python scripts, not compiled in
```
