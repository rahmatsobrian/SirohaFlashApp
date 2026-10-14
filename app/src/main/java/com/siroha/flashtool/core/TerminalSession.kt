package com.siroha.flashtool.core

import android.content.Context
import android.net.Uri
import com.siroha.flashtool.data.LogLevel
import com.siroha.flashtool.data.LogRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A PC-style command line on top of the app's built-in fastboot and ADB
 * clients. The person types what they would type on a computer -
 * `fastboot flash boot boot.img`, `adb reboot recovery`, `adb shell` - and
 * files are "in the current folder" by attaching them: a command refers to an
 * attached file by its name.
 *
 * Nothing here talks to a real shell on this phone. `fastboot ...` maps onto
 * [FastbootOperations] and `adb ...` onto [AdbOperations]; `adb shell` with no
 * command switches the prompt into a device shell where every line runs on the
 * target phone (the working directory is tracked so `cd` sticks).
 *
 * One instance lives for the whole app session so the scrollback survives
 * leaving and re-entering the screen.
 */
class TerminalSession(
    private val context: Context,
    private val fastboot: FastbootOperations,
    private val adb: AdbOperations,
    private val logRepository: LogRepository
) {
    enum class Kind { INPUT, OUTPUT, ERROR, INFO }

    data class Line(val text: String, val kind: Kind)
    /**
     * The one attached file. [path] is the text typed into the command line: the real
     * filesystem path when it could be resolved (/storage/emulated/0/Download/x.img),
     * otherwise just the name. [uri] is the way to read it when the path itself isn't readable.
     */
    data class Attachment(val name: String, val uri: Uri?, val path: String)

    private val _lines = MutableStateFlow(
        listOf(
            Line("Siroha Terminal - fastboot and adb, typed like on a PC.", Kind.INFO),
            Line("Tap the paperclip to attach a file - its name is typed into the command line.", Kind.INFO),
            Line("Type help for the list of commands.", Kind.INFO)
        )
    )
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    private val _prompt = MutableStateFlow(LOCAL_PROMPT)
    val prompt: StateFlow<String> = _prompt.asStateFlow()

    /** Commands typed so far, oldest first (for the up/down keys). */
    val history = mutableListOf<String>()

    /** The line being executed, untouched - `adb shell ls "/sdcard/My Files"` must keep its quoting. */
    private var currentLine = ""

    @Volatile
    private var commandRunning = false

    private var adbAutoConnectTried = false

    private var shellMode = false
    private var cwd = "/"
    private var deviceName = "android"
    private var isRoot = false

    // ------------------------------------------------------------------
    // Attachments and screen state
    // ------------------------------------------------------------------

    /** Only one file can be attached at a time: attaching another replaces it. */
    fun attach(name: String, uri: Uri, path: String) {
        _attachments.value = listOf(Attachment(name, uri, path))
        info("attached: $path")
    }

    fun detach(name: String): Boolean {
        val before = _attachments.value
        _attachments.value = before.filterNot { it.name.equals(name, ignoreCase = true) || it.path.equals(name, ignoreCase = true) }
        return _attachments.value.size != before.size
    }

    fun clear() {
        _lines.value = emptyList()
    }

    /** Everything on screen as plain text, for the copy button. */
    fun transcript(): String = _lines.value.joinToString("\n") { it.text }

    private fun add(text: String, kind: Kind) {
        val cleaned = ANSI.replace(text, "").replace("\r\n", "\n").replace('\r', '\n')
        val parts = cleaned.split('\n').let { if (it.size > 1 && it.last().isEmpty()) it.dropLast(1) else it }
            .map { expandTabs(it) }
        var shown = parts
        var extra = 0
        if (parts.size > MAX_LINES_PER_PRINT) {
            shown = parts.take(MAX_LINES_PER_PRINT)
            extra = parts.size - MAX_LINES_PER_PRINT
        }
        val added = shown.map { Line(it, kind) } +
            (if (extra > 0) listOf(Line("... $extra more lines not shown (see Utilities > Logs)", Kind.INFO)) else emptyList())
        _lines.value = (_lines.value + added).takeLast(MAX_LINES)
    }

    /** Compose draws a tab as nothing, so "serial<TAB>device" would show as "serialdevice". Pad to 8-column stops instead. */
    private fun expandTabs(line: String): String {
        if ('\t' !in line) return line
        val sb = StringBuilder()
        for (c in line) {
            if (c == '\t') repeat(8 - sb.length % 8) { sb.append(' ') } else sb.append(c)
        }
        return sb.toString()
    }

    private fun out(text: String) = add(text, Kind.OUTPUT)
    private fun err(text: String) = add(text, Kind.ERROR)
    private fun info(text: String) = add(text, Kind.INFO)

    private fun updatePrompt() {
        _prompt.value = if (shellMode) "$deviceName:$cwd ${if (isRoot) "#" else "$"} " else LOCAL_PROMPT
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    suspend fun execute(raw: String) {
        val line = raw.trim()
        if (line.isEmpty()) return
        if (history.lastOrNull() != line) history += line
        add(_prompt.value + line, Kind.INPUT)

        currentLine = line
        commandRunning = true
        OperationStatus.begin(line.take(70))
        try {
            if (shellMode) runShellLine(line) else runLocalLine(line)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err("error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            OperationStatus.end()
            commandRunning = false
        }
    }

    /**
     * Called about once a second while the terminal is open: drops connections whose phone is gone
     * (and leaves the device shell), and opens the ADB link once per plug-in so the state
     * (device / recovery / sideload) is known without the person running anything.
     */
    suspend fun refreshConnections() {
        if (commandRunning) return
        val devices = UsbDeviceHelper.listDevices(context)
        val hasFastboot = devices.any { UsbDeviceHelper.isLikelyFastbootDevice(it) }
        val hasAdb = devices.any { UsbDeviceHelper.isLikelyAdbDevice(it) }
        if (fastboot.isConnected() && !hasFastboot) fastboot.disconnect()
        if (adb.isConnected() && !hasAdb) {
            adb.disconnect()
            if (shellMode) {
                shellMode = false
                updatePrompt()
                info("device disconnected - left the device shell")
            }
        }
        if (!hasAdb) {
            adbAutoConnectTried = false
        } else if (!adb.isConnected() && !adbAutoConnectTried) {
            adbAutoConnectTried = true
            adb.connect()
        }
    }

    private suspend fun runLocalLine(line: String) {
        val tokens = tokenize(line)
        if (tokens.isEmpty()) return
        when (tokens[0].lowercase()) {
            "help", "?" -> printHelp()
            "clear", "cls" -> clear()
            "ls", "files" -> listAttachments()
            "rm", "detach" -> {
                val name = tokens.getOrNull(1)
                if (name == null) err("usage: rm <file>")
                else if (detach(name)) info("detached $name") else err("rm: $name: no such attached file")
            }
            "fastboot" -> runFastboot(tokens.drop(1))
            "adb" -> runAdb(tokens.drop(1))
            "exit", "quit" -> info("Nothing to exit here - use the back button. (Inside 'adb shell', exit leaves the device shell.)")
            else -> err("${tokens[0]}: command not found - type help")
        }
    }

    // ------------------------------------------------------------------
    // fastboot
    // ------------------------------------------------------------------

    private suspend fun runFastboot(args0: List<String>) {
        var args = args0
        // Global options before the subcommand, e.g. `fastboot -s SERIAL flash ...`.
        while (args.isNotEmpty() && args[0].startsWith("-") && args[0] != "-w") {
            val opt = args[0]
            when {
                opt == "--version" -> { out("fastboot (built-in USB client of Siroha Flash Tool)"); return }
                opt == "-h" || opt == "--help" -> { printFastbootHelp(); return }
                opt in FASTBOOT_OPTIONS_WITH_VALUE -> args = args.drop(2)
                opt.startsWith("--slot") -> {
                    info("note: $opt is ignored - name the slot in the partition instead (flash boot_a boot.img)")
                    args = args.drop(1)
                }
                else -> args = args.drop(1)
            }
        }
        if (args.isEmpty()) { printFastbootHelp(); return }
        val cmd = args[0].lowercase()
        val rest = args.drop(1)

        when (cmd) {
            "help" -> printFastbootHelp()
            "devices" -> {
                // Host-side listing, like the PC tool: it must work before anything is connected.
                val serials = usbSerials(adbMode = false)
                if (serials.isEmpty()) info("(no fastboot devices)") else out(serials.joinToString("\n") { "$it\tfastboot" })
            }
            "getvar" -> {
                if (rest.isEmpty()) { err("usage: fastboot getvar <name | all>"); return }
                if (!ensureFastboot()) return
                printResponse(fastboot.rawCommandWithResponse("getvar " + rest.joinToString(" ")))
            }
            "reboot", "reboot-bootloader", "reboot-recovery", "reboot-fastboot" -> {
                val word = when (cmd) {
                    "reboot-bootloader" -> "bootloader"
                    "reboot-recovery" -> "recovery"
                    "reboot-fastboot" -> "fastboot"
                    else -> rest.getOrNull(0)?.lowercase()
                }
                val target = when (word) {
                    null -> FastbootRebootTarget.SYSTEM
                    "bootloader" -> FastbootRebootTarget.BOOTLOADER
                    "recovery" -> FastbootRebootTarget.RECOVERY
                    "fastboot", "fastbootd" -> FastbootRebootTarget.FASTBOOTD
                    "edl" -> FastbootRebootTarget.EDL
                    else -> { err("fastboot: unknown reboot target '$word' (bootloader, recovery, fastboot, edl)"); return }
                }
                if (!ensureFastboot()) return
                val started = System.currentTimeMillis()
                if (fastboot.reboot(target)) out("Rebooting${word?.let { " into $it" } ?: ""}... OKAY") else failed(started, "reboot")
            }
            "continue" -> {
                if (!ensureFastboot()) return
                printResponse(fastboot.rawCommandWithResponse("continue"))
            }
            "flash" -> {
                val positional = rest.filterNot { it.startsWith("--") }
                val partition = positional.getOrNull(0)
                if (partition == null) { err("usage: fastboot flash <partition> [<filename>]"); return }
                val attachment = findFile(positional.getOrNull(1), "$partition.img") ?: return
                if (!ensureFastboot()) return
                val file = stage(attachment) ?: return
                out("Sending '$partition' (${file.length() / 1024} KB)")
                val started = System.currentTimeMillis()
                if (fastboot.flashPartitionChecked(partition, file)) out("OKAY\nFinished.") else failed(started, "flash $partition")
            }
            "boot" -> {
                val attachment = findFile(rest.getOrNull(0), null) ?: return
                if (!ensureFastboot()) return
                val file = stage(attachment) ?: return
                out("Downloading '${attachment.name}' (${file.length() / 1024} KB)")
                val started = System.currentTimeMillis()
                if (fastboot.boot(file)) out("OKAY\nBooting...") else failed(started, "boot")
            }
            "stage" -> {
                val attachment = findFile(rest.getOrNull(0), null) ?: return
                if (!ensureFastboot()) return
                val file = stage(attachment) ?: return
                val started = System.currentTimeMillis()
                if (fastboot.stageFile(file)) out("OKAY") else failed(started, "stage")
            }
            "erase" -> {
                val partition = rest.getOrNull(0)
                if (partition == null) { err("usage: fastboot erase <partition>"); return }
                if (!ensureFastboot()) return
                val started = System.currentTimeMillis()
                if (fastboot.erase(partition)) out("Erasing '$partition' OKAY") else failed(started, "erase $partition")
            }
            "-w" -> {
                if (!ensureFastboot()) return
                info("Wiping userdata, cache and metadata...")
                val started = System.currentTimeMillis()
                if (fastboot.wipeUserData()) out("OKAY") else failed(started, "wipe")
            }
            "set_active" -> {
                val slot = rest.getOrNull(0)
                if (slot == null) { err("usage: fastboot set_active <a|b>"); return }
                if (!ensureFastboot()) return
                val started = System.currentTimeMillis()
                if (fastboot.setActiveSlot(slot)) out("Setting current slot to '$slot' OKAY") else failed(started, "set_active")
            }
            "flashall", "update", "format", "fetch" ->
                err("fastboot $cmd isn't supported by the built-in client. Use 'flash', 'erase' and 'boot' per partition (the Mi Tool screen flashes whole ROM folders).")
            else -> {
                // Anything else is passed to the bootloader as a protocol command (oem ..., flashing ..., snapshot-update ...).
                if (!ensureFastboot()) return
                printResponse(fastboot.rawCommandWithResponse(args.joinToString(" ")))
            }
        }
    }

    private fun printResponse(text: String) {
        val t = text.trim()
        if (t.isEmpty()) { out("OKAY"); return }
        if (t.contains("FAILED") || t.contains("ERROR")) err(t) else out(t)
    }

    /** Prints the error lines the operation just logged - the bootloader's own reason is among them. */
    private fun failed(since: Long, what: String) {
        val reasons = logRepository.entries.value
            .filter { it.timestamp >= since && it.level == LogLevel.ERROR }
            .map { it.message }
            .distinct()
            .takeLast(4)
        err("FAILED: $what")
        reasons.forEach { err("  $it") }
    }

    /** Waits (like the PC tool) for a fastboot device, then opens it. */
    private suspend fun ensureFastboot(): Boolean {
        if (fastboot.isConnected()) {
            // The link can be stale (phone unplugged or rebooted since): only trust it while the USB device is still there.
            if (UsbDeviceHelper.listDevices(context).any { UsbDeviceHelper.isLikelyFastbootDevice(it) }) return true
            fastboot.disconnect()
        }
        var announced = false
        val deadline = System.currentTimeMillis() + WAIT_FOR_DEVICE_MS
        while (System.currentTimeMillis() < deadline) {
            val dev = UsbDeviceHelper.listDevices(context).firstOrNull { UsbDeviceHelper.isLikelyFastbootDevice(it) }
            if (dev != null) {
                if (fastboot.connect()) return true
                err("fastboot: couldn't open the device - allow the USB permission prompt and try again")
                return false
            }
            if (!announced) { out("< waiting for any device >"); announced = true }
            delay(500)
        }
        err("fastboot: no device found. Put the phone in fastboot mode and plug it in.")
        return false
    }

    // ------------------------------------------------------------------
    // adb
    // ------------------------------------------------------------------

    private suspend fun runAdb(args0: List<String>) {
        var args = args0
        while (args.isNotEmpty()) {
            val opt = args[0]
            args = when {
                opt in listOf("-s", "-t", "-H", "-P", "-L") -> args.drop(2)
                opt in listOf("-d", "-e", "-a") -> args.drop(1)
                else -> break
            }
        }
        if (args.isEmpty()) { printAdbHelp(); return }
        val cmd = args[0].lowercase()
        val rest = args.drop(1)

        when (cmd) {
            "help", "--help", "-h" -> printAdbHelp()
            "version", "--version" -> out("Android Debug Bridge (built-in USB client of Siroha Flash Tool)")
            "devices" -> {
                out("List of devices attached")
                val serials = usbSerials(adbMode = true)
                if (serials.isNotEmpty()) {
                    // A fresh handshake, like the adb server does, so the state is current:
                    // device / recovery / sideload / bootloader come from the phone's own banner.
                    adb.disconnect()
                    val connected = adb.connect()
                    val state = when {
                        connected -> adb.connectionState ?: "device"
                        adb.lastConnectAwaitingAuth -> "unauthorized"
                        else -> "offline"
                    }
                    out(serials.mapIndexed { i, serial -> "$serial\t${if (i == 0) state else "offline"}" }.joinToString("\n"))
                }
            }
            "start-server", "kill-server", "usb", "connect", "disconnect", "tcpip" ->
                info("adb $cmd: not needed - this app talks to the phone directly over USB.")
            "get-state" -> out(if (hasAdbDevice()) "device" else "error: no devices/emulators found")
            "wait-for-device" -> if (ensureAdb()) out("device ready")
            "get-serialno" -> runShellOnce("getprop ro.serialno", withCwd = false)
            "shell" -> {
                // Everything after the word "shell", exactly as typed (quotes and all).
                val command = Regex("""\bshell\b""").find(currentLine)?.let { currentLine.substring(it.range.last + 1).trim() }.orEmpty()
                if (command.isEmpty()) enterShell() else runShellOnce(command, withCwd = false)
            }
            "logcat" -> {
                info("note: streaming isn't supported - showing the current log buffer (logcat -d).")
                runShellOnce("logcat -d " + rest.filterNot { it == "-d" }.joinToString(" "), withCwd = false)
            }
            "reboot" -> {
                val target = rest.getOrNull(0)?.lowercase()
                if (target != null && target !in listOf("bootloader", "recovery", "sideload", "sideload-auto-reboot", "fastboot")) {
                    err("adb: unknown reboot target '$target' (bootloader, recovery, sideload, fastboot)")
                    return
                }
                if (!ensureAdb()) return
                val result = adb.rawCommand(if (target == null) "reboot" else "reboot $target")
                if (result.success) out("Rebooting${target?.let { " into $it" } ?: ""}...") else err(result.text)
                adb.disconnect()
            }
            "root", "unroot" -> {
                if (!ensureAdb()) return
                val result = adb.rawCommand(cmd)
                out(result.text)
                // adbd restarts: drop the old link and reconnect once the device re-enumerates.
                adb.disconnect()
                delay(1500)
                if (ensureAdb(announce = false)) {
                    val uid = adb.shellWithOutcome("id -u").text.trim()
                    out(if (uid == "0") "adbd is running as root" else "adbd is running as uid $uid")
                } else {
                    err("adb: the device didn't come back after the restart - replug it and run adb devices")
                }
            }
            "remount" -> {
                if (!ensureAdb()) return
                out(adb.rawCommand("remount").text)
            }
            "sideload" -> {
                val attachment = findFile(rest.getOrNull(0), null) ?: return
                if (!ensureAdb()) return
                val started = System.currentTimeMillis()
                info("Sideloading ${attachment.name}...")
                val direct = directFile(attachment)
                val uri = attachment.uri
                val sent = when {
                    direct != null -> adb.sideload(direct)
                    uri != null -> adb.sideloadFromUri(context, uri)
                    else -> { err("cannot read '${attachment.path}'"); false }
                }
                if (sent) out("Total xfer: done\nOKAY") else failed(started, "sideload")
            }
            "push", "pull", "install", "uninstall", "install-multiple", "forward", "reverse", "backup", "restore", "sync" ->
                err("adb $cmd isn't supported by the built-in client yet. Available: devices, shell, reboot, sideload, root, remount, logcat -d.")
            else -> err("adb: unknown command $cmd")
        }
    }

    /** Serial numbers of the attached fastboot or ADB devices (falls back to the USB path when the serial is hidden). */
    private fun usbSerials(adbMode: Boolean): List<String> =
        UsbDeviceHelper.listDevices(context)
            .filter { if (adbMode) UsbDeviceHelper.isLikelyAdbDevice(it) else UsbDeviceHelper.isLikelyFastbootDevice(it) }
            .map { device ->
                // Reading the serial needs USB permission on newer Android; don't crash without it.
                runCatching { device.serialNumber }.getOrNull() ?: device.deviceName.substringAfterLast('/')
            }

    private fun hasAdbDevice(): Boolean =
        UsbDeviceHelper.listDevices(context).any { UsbDeviceHelper.isLikelyAdbDevice(it) }

    private suspend fun ensureAdb(announce: Boolean = true): Boolean {
        if (adb.isConnected() && hasAdbDevice()) return true
        if (adb.isConnected()) adb.disconnect() // the link is stale: the phone was unplugged or restarted
        var announced = false
        val deadline = System.currentTimeMillis() + WAIT_FOR_DEVICE_MS
        while (System.currentTimeMillis() < deadline) {
            if (hasAdbDevice()) {
                if (adb.connect()) return true
                err("adb: device unauthorized or the USB permission was denied - accept the 'Allow USB debugging' prompt on the phone and try again")
                return false
            }
            if (!announced && announce) { out("< waiting for any device >"); announced = true }
            delay(500)
        }
        err("adb: no devices/emulators found")
        return false
    }

    // ------------------------------------------------------------------
    // adb shell
    // ------------------------------------------------------------------

    private suspend fun enterShell() {
        if (!ensureAdb()) return
        val answer = adb.shellWithOutcome("getprop ro.product.device; id -u").text
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        deviceName = answer.getOrNull(0)?.takeIf { !it.startsWith("(") && !it.startsWith("ERROR") } ?: "android"
        isRoot = answer.getOrNull(1) == "0"
        shellMode = true
        cwd = "/"
        updatePrompt()
        info("Device shell - every line runs on the phone. Type exit to leave. (Interactive programs like vi/top aren't supported; try top -n 1.)")
    }

    private suspend fun runShellLine(line: String) {
        val t = line.trim()
        when {
            t == "exit" || t == "logout" -> {
                shellMode = false
                updatePrompt()
            }
            t == "clear" -> clear()
            t == "cd" || t.startsWith("cd ") -> {
                val target = t.removePrefix("cd").trim()
                val result = adb.shellWithOutcome("cd ${quote(cwd)} && cd $target && pwd")
                val newDir = result.text.lines().map { it.trim() }.lastOrNull { it.startsWith("/") }
                if (result.success && newDir != null) {
                    cwd = newDir
                    updatePrompt()
                } else {
                    err(result.text.trim())
                }
            }
            else -> runShellOnce(t, withCwd = true)
        }
    }

    private suspend fun runShellOnce(command: String, withCwd: Boolean) {
        if (!ensureAdb()) {
            if (shellMode) { shellMode = false; updatePrompt() }
            return
        }
        val full = if (withCwd) "cd ${quote(cwd)} && $command" else command
        val result = adb.shellWithOutcome(full)
        val text = result.text.trimEnd()
        if (text.isNotEmpty() && !text.startsWith("(no output")) {
            if (result.success) out(text) else err(text)
        } else if (!result.success) {
            err("(command failed)")
        }
    }

    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private fun listAttachments() {
        val files = _attachments.value
        if (files.isEmpty()) info("(no file attached - tap the paperclip)") else files.forEach { out(it.path) }
    }

    /** Finds an attached file by name (a leading path is ignored), or the obvious default when none was named. */
    private fun findFile(name: String?, defaultName: String?): Attachment? {
        val files = _attachments.value
        if (name != null) {
            val base = name.substringAfterLast('/')
            val hit = files.firstOrNull {
                it.path.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) || it.name.equals(base, ignoreCase = true)
            }
            if (hit != null) return hit
            // A path typed by hand, like on a PC: use the file where it is, if the app is allowed to read it.
            if (name.startsWith("/")) {
                val typed = File(name)
                if (SafFiles.canReallyRead(typed)) return Attachment(typed.name, null, name)
                err(
                    if (typed.exists()) "cannot read '$name': no permission - allow All files access for this app, or attach the file with the paperclip"
                    else "cannot load '$name': no such file"
                )
                return null
            }
            err("cannot load '$name': no such file - attach it first (ls shows the attached file)")
            return null
        }
        defaultName?.let { d -> files.firstOrNull { it.name.equals(d, ignoreCase = true) }?.let { return it } }
        if (files.size == 1) return files[0]
        err(if (files.isEmpty()) "no file attached - tap the paperclip first" else "which file? name it, e.g. ${files.first().name}")
        return null
    }

    /** The file itself when [attachment] has a real path the app can read, so a multi-GB image isn't copied for nothing. */
    private fun directFile(attachment: Attachment): File? =
        attachment.path.takeIf { it.startsWith("/") }?.let { File(it) }?.takeIf { SafFiles.canReallyRead(it) }

    /** A readable file for the clients: the real file when possible, otherwise a copy in the app cache. */
    private suspend fun stage(attachment: Attachment): File? {
        directFile(attachment)?.let { return it }
        val uri = attachment.uri
        if (uri == null) {
            err("cannot read '${attachment.path}'")
            return null
        }
        return try {
            File(SafFiles.copyToCache(context, uri, "term_" + attachment.name.replace(Regex("[^A-Za-z0-9._-]"), "_")))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err("cannot read '${attachment.name}': ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Help text and parsing
    // ------------------------------------------------------------------

    private fun printHelp() {
        out(
            """
            Commands
              fastboot <args>   devices | getvar all | flash boot boot.img | boot twrp.img
                                erase userdata | reboot [bootloader|recovery|fastboot|edl]
                                oem device-info | flashing unlock | set_active a | -w
              adb <args>        devices | shell [cmd] | reboot [bootloader|recovery]
                                sideload rom.zip | root | remount | logcat -d
              ls                show the attached file
              rm <file>         detach it
              clear             clear the screen
              help              this text

            Notes
              - adb shell with no command opens a device shell; type exit to leave it.
              - Not supported: adb push/pull/install, fastboot flashall/update/format,
                interactive programs (vi, top).
              - Everything that runs is also recorded in Utilities > Logs.
            """.trimIndent()
        )
    }

    private fun printFastbootHelp() {
        out(
            """
            usage: fastboot [-s SERIAL] <command>
              devices
              getvar <name|all>
              flash <partition> [<file>]
              boot <file>
              erase <partition>
              reboot [bootloader|recovery|fastboot|edl]
              continue
              set_active <a|b>
              stage <file>
              -w                          wipe userdata, cache and metadata
              oem <cmd> | flashing <cmd>  sent to the bootloader as typed
            """.trimIndent()
        )
    }

    private fun printAdbHelp() {
        out(
            """
            usage: adb <command>
              devices
              shell [command]
              reboot [bootloader|recovery|sideload|fastboot]
              sideload <file>
              root | unroot | remount
              logcat -d
              get-state | get-serialno | wait-for-device
            """.trimIndent()
        )
    }

    /** Splits a command line into words, honoring '...' and "..." quoting and backslash escapes. */
    private fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var inToken = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> if (c == quote) quote = null else current.append(c)
                c == '"' || c == '\'' -> { quote = c; inToken = true }
                c == '\\' && i + 1 < line.length -> { current.append(line[i + 1]); inToken = true; i++ }
                c.isWhitespace() -> if (inToken) { tokens += current.toString(); current.clear(); inToken = false }
                else -> { current.append(c); inToken = true }
            }
            i++
        }
        if (inToken) tokens += current.toString()
        return tokens
    }

    private companion object {
        const val LOCAL_PROMPT = "$ "
        const val MAX_LINES = 2000
        const val MAX_LINES_PER_PRINT = 1500
        const val WAIT_FOR_DEVICE_MS = 15_000L
        val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        val FASTBOOT_OPTIONS_WITH_VALUE = setOf("-s", "-i", "-p", "-c", "-S", "-b", "-m", "-t")
    }
}
