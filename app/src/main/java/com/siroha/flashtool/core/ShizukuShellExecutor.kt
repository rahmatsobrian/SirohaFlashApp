package com.siroha.flashtool.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

private const val SHIZUKU_PERMISSION_REQUEST_CODE = 8642

/**
 * Executes commands through Shizuku instead of su — this is what lets the app
 * work on a NON-ROOTED phone that has Shizuku running (started either via
 * `adb shell` once from a PC, or via wireless debugging pairing on Android 11+).
 */
class ShizukuShellExecutor(private val context: Context) : ShellExecutor {

    override val mode = ExecutionMode.SHIZUKU

    private var service: IShellService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShellService.Stub.asInterface(binder)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    private fun bindIfNeeded() {
        if (bound) return
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell_service")
            .debuggable(false)
            .version(1)
        Shizuku.bindUserService(args, connection)
    }

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun requestAccess(): Boolean {
        if (isReady()) {
            bindIfNeeded()
            return true
        }
        if (!Shizuku.pingBinder()) return false // Shizuku service isn't running
        return suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    if (granted) bindIfNeeded()
                    if (cont.isActive) cont.resume(granted)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext ShellResult(-1, emptyList(), listOf("Shizuku service not bound yet"))
        parseUserServiceResult(svc.runCommand(command))
    }

    /**
     * Shizuku's UserService AIDL call is not a live pipe, so we can't get true
     * line-by-line streaming the way we do with root. We poll by re-invoking a
     * "tail -n +N" style read against a log file the command redirects to,
     * which keeps the UI responsive for long qdl/flash operations.
     */
    override fun execStreaming(command: String): Flow<String> = flow {
        val svc = service
        if (svc == null) {
            emit("[error] Shizuku service not bound")
            return@flow
        }
        val logPath = "/data/local/tmp/siroha_${System.currentTimeMillis()}.log"
        val wrapped = "($command) > $logPath 2>&1 &"
        svc.runCommand(wrapped)
        var lastSize = 0
        var idleTicks = 0
        while (idleTicks < 5) {
            kotlinx.coroutines.delay(700)
            val res = parseUserServiceResult(svc.runCommand("cat $logPath 2>/dev/null | wc -c && cat $logPath 2>/dev/null"))
            val text = res.stdout.joinToString("\n")
            val newLen = text.length
            if (newLen > lastSize) {
                emit(text.substring(lastSize))
                lastSize = newLen
                idleTicks = 0
            } else {
                idleTicks++
            }
        }
        svc.runCommand("rm -f $logPath")
    }.flowOn(Dispatchers.IO)
}
