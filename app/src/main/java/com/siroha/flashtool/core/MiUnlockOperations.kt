package com.siroha.flashtool.core

import android.content.Context
import com.siroha.flashtool.data.LogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Result of the "clear device" pre-check step, shown to the user before they confirm unlocking. */
data class MiUnlockClearInfo(val notice: String, val wipesUserData: Boolean)

sealed class MiUnlockResult {
    object Unlocked : MiUnlockResult()
    data class Failed(val reason: String) : MiUnlockResult()
}

/**
 * Orchestrates the full Mi Unlock flow: account session -> server session ->
 * fastboot device probe -> nonce/clear check -> the actual unlock call ->
 * staging encryptData -> `oem unlock`. Ported from offici5l/MiTools
 * (Apache-2.0) — see [MiUnlockApi] for why this is a close port rather than
 * a rewrite.
 */
class MiUnlockOperations(
    private val context: Context,
    private val log: LogRepository,
    private val fastboot: FastbootOperations
) {
    companion object {
        private const val TAG = "MiUnlock"
    }

    private var serverSession: MiUnlockApi.ServerSession? = null
    private var accountSession: MiUnlockApi.AccountSession? = null
    private var product: String? = null
    private var deviceToken: String? = null
    private var nonce: String? = null

    suspend fun resolveServerSession(account: MiUnlockApi.AccountSession): Boolean = withContext(Dispatchers.IO) {
        accountSession = account
        log.info(TAG, "Resolving account region and unlock-server session...")
        try {
            val session = MiUnlockApi.resolveServerSession(account)
            serverSession = session
            log.success(TAG, "Unlock server: ${session.host}")
            true
        } catch (e: Exception) {
            log.error(TAG, "Failed to resolve server session: ${e.message}")
            false
        }
    }

    /** Connects to the phone in bootloader/fastboot mode and reads product + device token. */
    suspend fun readDeviceInfo(): Boolean = withContext(Dispatchers.IO) {
        log.info(TAG, "Reading device info (product, token)...")
        product = fastboot.getVar("product").ifBlank { null }
        if (product == null) {
            log.error(TAG, "Failed to read product — is the phone connected in Bootloader mode?")
            return@withContext false
        }
        log.info(TAG, "product = $product")
        deviceToken = fastboot.getDeviceToken().ifBlank { null }
        if (deviceToken == null) {
            log.error(TAG, "Failed to read device token")
            return@withContext false
        }
        true
    }

    /** Asks Xiaomi's server whether this device+product can be unlocked, and whether it wipes data. */
    suspend fun checkClearStatus(): MiUnlockClearInfo? = withContext(Dispatchers.IO) {
        val session = serverSession ?: return@withContext null
        val p = product ?: return@withContext null

        val randomString = (1..16).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
        val nonceResponse = MiUnlockApi.send(
            session.host, "/api/v2/nonce", listOf("r", "sid"),
            mapOf("r" to randomString, "sid" to "miui_unlocktool_client"),
            session.ssecurity, session.serviceToken
        )
        val n = nonceResponse.optString("nonce")
        if (n.isNullOrEmpty()) {
            log.error(TAG, "Failed to retrieve nonce: $nonceResponse")
            return@withContext null
        }
        nonce = n

        val clearResponse = MiUnlockApi.send(
            session.host, "/api/v2/unlock/device/clear", listOf("data", "nonce", "sid"),
            mapOf("data" to JSONObject().put("product", p).toString(), "nonce" to n, "sid" to "miui_unlocktool_client"),
            session.ssecurity, session.serviceToken
        )
        val notice = clearResponse.optString("notice", "No notice available")
        val wipes = clearResponse.optInt("cleanOrNot", -1) == 1
        log.info(TAG, "$notice (${if (wipes) "wipes user data" else "does not wipe user data"})")
        MiUnlockClearInfo(notice, wipes)
    }

    /** Performs the actual unlock: server call -> stage encryptData -> `oem unlock`. */
    suspend fun performUnlock(userId: String): MiUnlockResult = withContext(Dispatchers.IO) {
        val session = serverSession ?: return@withContext MiUnlockResult.Failed("No server session")
        val p = product ?: return@withContext MiUnlockResult.Failed("No product")
        val token = deviceToken ?: return@withContext MiUnlockResult.Failed("No device token")
        val n = nonce ?: return@withContext MiUnlockResult.Failed("No nonce — run the clear-status check first")
        val deviceId = accountSession?.deviceId ?: return@withContext MiUnlockResult.Failed("No account session")
        val pcId = MiUnlockApi.pcIdFor(deviceId)

        log.info(TAG, "Requesting unlock authorization...")
        val ahaResponse = MiUnlockApi.send(
            session.host, "/api/v3/ahaUnlock", listOf("appId", "data", "nonce", "sid"),
            mapOf(
                "appId" to "1",
                "data" to JSONObject().apply {
                    put("clientId", "2")
                    put("clientVersion", "7.6.727.43")
                    put("deviceInfo", JSONObject().apply {
                        put("boardVersion", ""); put("deviceName", ""); put("product", p); put("socId", "")
                    })
                    put("deviceToken", token)
                    put("language", "en")
                    put("operate", "unlock")
                    put("pcId", pcId)
                    put("region", "")
                    put("uid", userId)
                }.toString(),
                "nonce" to n,
                "sid" to "miui_unlocktool_client"
            ),
            session.ssecurity, session.serviceToken
        )

        if (ahaResponse.optInt("code") != 0) {
            val message = if (ahaResponse.has("descEN")) ahaResponse.optString("descEN") else ahaResponse.optString("description", "Unknown error")
            log.error(TAG, "Unlock authorization denied: $message")
            return@withContext MiUnlockResult.Failed(message)
        }

        val encryptDataHex = ahaResponse.optString("encryptData", "")
        if (encryptDataHex.isEmpty()) {
            log.error(TAG, "Server returned no encryptData")
            return@withContext MiUnlockResult.Failed("No encryptData in server response")
        }

        val encryptFile = File(context.filesDir, "miunlock_encryptData")
        encryptFile.writeBytes(MiUnlockApi.hexStringToByteArray(encryptDataHex))

        log.info(TAG, "Staging encryptData to the device...")
        if (!fastboot.stageFile(encryptFile)) {
            return@withContext MiUnlockResult.Failed("Failed to stage encryptData over USB")
        }

        log.info(TAG, "Sending oem unlock...")
        val unlockOk = fastboot.rawCommand("oem unlock")
        encryptFile.delete()

        if (unlockOk) {
            log.success(TAG, "Bootloader unlocked.")
            MiUnlockResult.Unlocked
        } else {
            MiUnlockResult.Failed("oem unlock command failed — check the phone screen for an on-device confirmation prompt")
        }
    }
}
