# Siroha Flash Tool (Android app)

A native Android port of the [SirohaFlashTool](.) Termux/bash script (all 10
menu modules) — Material 3, Material You dynamic color (+ AMOLED/light/dark/
system theme control), Android 10 (API 29) through 16, running with **root,
Shizuku, or a from-scratch ADB-over-USB client** as the privilege backend.

## What's actually implemented

- **ADB shell v2 protocol (`shell,v2,raw:`)** — the legacy `shell:` service
  has no exit-code signal at the wire protocol level at all, which is why
  a failed command with no output used to look identical to a successful
  one with no output (both just returned empty text). Now, when the
  connected device advertises `shell_v2` support (checked during the CNXN
  handshake), commands run through the framed protocol variant instead,
  which carries a genuine exit code — so success/failure is now actually
  known, not guessed from text content. Falls back to the legacy service
  (with an honest "can't verify success" note) on older Android versions
  that don't support shell v2.
- **Shell vs. raw ADB mode toggle** on the manual ADB command box — "Shell"
  wraps input in `adb shell <command>` as before; "ADB" sends it as a bare
  ADB service instead (e.g. `reboot`, `reboot:bootloader`), matching what
  typing a bare `adb <command>` does on a PC. `devices` in ADB mode is
  intercepted locally and lists the currently attached ADB device(s), the
  same way fastboot's `devices` already worked — real `adb devices` is
  answered by the local adb server on a PC and never reaches the device
  either.
- **QDL screen now correctly reflects fastboot connection state** — it
  previously always showed "not connected yet" regardless of the actual
  shared connection, because the screen was never given a reference to
  the shared `FastbootOperations` at all. It also now explicitly flags
  when a fastboot/ADB-mode device is connected but this screen specifically
  needs EDL (9008) mode instead, rather than implying it's usable as-is.
- **Live Status refresh button shows visible feedback** — the label reads
  "Live status — refreshing..." and the refresh icon becomes a small
  spinner for the duration of a manual refresh.
- **Home's Live Status now auto-connects fastboot/ADB when detected** —
  previously, Home only *displayed* device presence; every tool screen
  still needed its own manual Connect tap even after Home showed a
  device. Now, as soon as Home detects a fastboot- or ADB-mode device, it
  connects automatically in the background (once per detection), so by
  the time you open any tool screen it's already connected.
- **Fixed: root not detected on Home until visiting Requirements first** —
  the root-checking library (libsu) only returns an accurate answer once
  a shell session has been created at least once in the app's lifetime;
  Home's passive status checker never did that, so it stayed on
  "Checking..." until some other screen happened to trigger it. Home now
  primes this once per app launch — silent if root was already granted,
  and only shows a real prompt on a device that's never granted this app
  root before.
- **Fixed: Toasts claimed success even when nothing was connected** — the
  "not connected" messages from fastboot/ADB manual commands didn't
  contain the words the success/fail check was looking for, so a
  disconnected, garbage-input command could show a green "success" toast.
  Both now return a message the check correctly reads as a failure.
- **Fastboot/ADB connections now persist across screens** — `FastbootOperations`
  and `AdbOperations` are app-wide singletons (created once in
  `SirohaApplication`) instead of one private instance per screen. Connect
  once anywhere and every other fastboot/ADB screen sees it as already
  connected; `connect()` is also idempotent now, so calling it again while
  already connected is a safe no-op instead of re-claiming (and breaking)
  the USB interface.
- **Fastboot command output actually shows the real response now** —
  `oem device-info` and friends previously discarded every `INFO` packet
  the bootloader sends (the individual `(bootloader) Verity mode: true`-
  style lines) and only displayed the terse final status, which is why it
  looked like a meaningless short token. Output is now formatted like the
  real `fastboot` CLI: every INFO line prefixed `(bootloader) `, followed
  by `OKAY [ Xs ]` / `FAILED (...)` and a `Finished. Total time: Xs` line.
- **`devices` typed into the manual command box now works** — it's a
  host-side (PC-side) fastboot CLI subcommand, never sent over the wire to
  the phone at all, so it's intercepted locally and lists the currently
  attached fastboot device(s) by serial, matching real `fastboot devices`
  output, instead of being forwarded to the bootloader and failing with
  "unknown command".
- **Manual ADB shell command box** — same idea as the fastboot one, now
  also on the Fastboot/A-B/FRP screens: type what you'd type after
  `adb shell` on a PC (no `adb shell` prefix needed) and it runs directly.
- **Snackbar feedback on (almost) every action**, everywhere — connect,
  flash, erase, reboot, sideload, Mi Unlock steps, and more all show an
  immediate "succeeded"/"failed" Snackbar, so checking whether something
  worked doesn't require scrolling down to the log list first.
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
- **Mi Unlock** — a close port of offici5l/MiTools' MiUnlock module
  (Apache-2.0, upstream author of the credited "MiTool" project). WebView
  login against Xiaomi's real account.xiaomi.com, then the account-
  authenticated unlock API: region/host resolution, an AES-CBC + HMAC-SHA1
  request-signing scheme, nonce/eligibility check, and staging the signed
  `encryptData` over fastboot before `oem unlock`. Ported deliberately
  close to the reference implementation rather than rewritten, since for a
  crypto/auth flow like this, matching known-working code is far safer
  than reconstructing it from memory.
- **MiTool** — the other 2 of the original 4 tools, reimplemented natively:
  **Flash Fastboot ROM** (pick a folder, every `*.img` gets fastboot-flashed
  to a same-named partition — exactly how Xiaomi's own `flash_all.sh`
  works, so no per-device partition list needed) and **Firmware Content
  Extractor** (download a ROM ZIP, pull out one named file). **Mi
  Assistant** is the one MiTool feature NOT here; see "Known gaps."
- **Live status card** on Home and the QDL Flash screen — auto-refreshing
  (every 2s, no permission prompts triggered) indicator of which execution
  backend is active ("Working with root" / "Working with Shizuku" / neither)
  and what's on USB right now, including a specific "EDL (9008) mode
  detected" state so you can tell the target actually entered EDL mode
  without needing to start a flash first.
- **Guide split by transport** — one combined Guide screen became three
  (EDL, Fastboot, MiTool), each reachable from a "Guide" entry at the
  bottom of its own section on Home instead of one generic entry buried
  under "Info".
- **Requirements & Status**, **USB/OTG Fix**, **About** screens.
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
- **Mi Assistant** — genuinely not implementable, not just deferred. It
  talks to an external `miasst_termux` binary that was *never open-sourced
  anywhere, even in offici5l/MiTools itself* (that project's own README
  lists it as "planned, no ETA" too) — there's no protocol spec to
  reimplement against at all, unlike ADB/fastboot/Mi Unlock which are
  public or open-sourced. Reference scripts for all four original MiTool
  tools are kept in `mitool_reference/` regardless.
- **Mi Unlock has not been tested against a real Xiaomi account or
  device** — same caveat as the ADB/fastboot protocol work: written as a
  close port of a known-working reference, but nothing in this app has run
  against real hardware. The login WebView going solid black turned out to
  be two compounding Compose-`AndroidView`-hosted-`WebView` issues, fixed
  across two rounds once real screenshots were available: a hardware-layer
  rendering quirk, and `loadUrl()` firing before the view had stable
  non-zero layout bounds (some pages collapse to invisible if their CSS
  uses `height:100%` against a zero-height container on first paint) — now
  deferred to fire only once real bounds exist, with mixed-content loading
  also explicitly allowed since Xiaomi's login page pulls some sub-
  resources over plain HTTP. If the WebView loads but the server calls fail,
  or
  `oem unlock` doesn't stick, send back the Logs screen output. The login
  WebView also intercepts the system Back button to navigate within its
  own history first (e.g. back out of a "forgot password" sub-page) before
  falling through to leaving the Mi Unlock screen — so Back never
  unexpectedly dumps you out of the whole flow while there's still WebView
  history to go back through.
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

## Third-party attribution

`core/MiUnlockApi.kt` and `core/MiUnlockOperations.kt` are a close port of
the MiUnlock module from [offici5l/MiTools](https://github.com/offici5l/MiTools),
licensed Apache License 2.0. Per that license: the original copyright and
license notice are retained in those files' headers, and this note
documents that the Kotlin has been adapted (restructured into this app's
executor/logging architecture; the WebView login and USB steps are
otherwise a deliberately close port). A full copy of the Apache 2.0 license
text is available at https://www.apache.org/licenses/LICENSE-2.0.
