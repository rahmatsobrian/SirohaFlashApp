package com.siroha.flashtool.core

import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Xiaomi Mi Unlock's account-authenticated API — ported from offici5l's
 * open-source MiTools app (Apache-2.0), which is the only place this
 * protocol is documented anywhere: it's Xiaomi's private
 * account.xiaomi.com / *.miui.com unlock API, reverse-engineered and
 * open-sourced by that project. Every constant below (the HMAC key, the
 * request-signing scheme, the region subdomain map) comes directly from
 * that source rather than any official Xiaomi documentation, since none
 * exists publicly.
 *
 * This is a close, deliberate port rather than a rewrite — for a
 * cryptographic signing flow like this, matching a known-working
 * implementation line-for-line is much safer than "improving" it from
 * memory the way the from-scratch ADB/fastboot protocol work elsewhere in
 * this app had to be.
 */
object MiUnlockApi {

    private const val UNLOCK_SID = "miui_unlocktool_client"
    private const val HMAC_KEY = "2tBeoEyJTunmWUGq7bQH2Abn0k2NhhurOaqBfyxCuLVgn4AVj7swcawe53uDUno"

    data class AccountSession(val passToken: String, val deviceId: String, val userId: String)
    data class ServerSession(val host: String, val ssecurity: String, val serviceToken: String)

    private class MemoryCookieJar(private val initial: Map<String, String>) : CookieJar {
        private val store = mutableMapOf<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val initialCookies = initial.map { (name, value) -> Cookie.Builder().name(name).value(value).domain(url.host).build() }
            return initialCookies + (store[url.host] ?: emptyList())
        }
    }

    /**
     * Exchanges the account.xiaomi.com login cookies (captured from the
     * WebView login step) for the unlock-API session: region, host,
     * ssecurity, serviceToken.
     */
    fun resolveServerSession(account: AccountSession): ServerSession {
        val client = OkHttpClient.Builder()
            .cookieJar(MemoryCookieJar(mapOf("passToken" to account.passToken, "deviceId" to account.deviceId, "userId" to account.userId)))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val region = getRegion(client)
        val regionConfig = getRegionConfig(client, region)
        val host = hostForRegionConfig(regionConfig)
        val (ssecurity, serviceToken) = getSsecurityAndServiceToken(client)
        return ServerSession(host, ssecurity, serviceToken)
    }

    private fun getRegion(client: OkHttpClient): String {
        val request = Request.Builder()
            .url("https://account.xiaomi.com/pass/user/login/region")
            .header("User-Agent", "com.siroha.flashtool")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
            // Xiaomi prefixes these JSON responses with an 11-byte anti-hijack
            // header before the real payload — matches the upstream client.
            val body = response.body?.string()?.drop(11) ?: throw IOException("Empty response")
            return JSONObject(body).getJSONObject("data").getString("region")
        }
    }

    private fun getRegionConfig(client: OkHttpClient, region: String): String {
        val request = Request.Builder()
            .url("https://account.xiaomi.com/pass2/config?key=regionConfig")
            .header("User-Agent", "com.siroha.flashtool")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
            val body = response.body?.string()?.drop(11) ?: throw IOException("Empty response")
            val regionConfigs = JSONObject(body).getJSONObject("regionConfig")
            for (key in regionConfigs.keys()) {
                val config = regionConfigs.getJSONObject(key)
                if (!config.has("region.codes")) continue
                val codes = config.getJSONArray("region.codes")
                for (i in 0 until codes.length()) {
                    if (region == codes.getString(i)) return key
                }
            }
            throw IOException("Region config not found for region: $region")
        }
    }

    private fun hostForRegionConfig(regionConfig: String): String {
        val subdomains = mapOf(
            "Singapore" to "unlock.update.intl",
            "China" to "unlock.update",
            "India" to "in-unlock.update.intl",
            "Russia" to "ru-unlock.update.intl",
            "Europe" to "eu-unlock.update.intl"
        )
        val subdomain = subdomains[regionConfig] ?: throw IOException("Unknown region config: $regionConfig")
        return "https://$subdomain.miui.com"
    }

    private fun getSsecurityAndServiceToken(client: OkHttpClient): Pair<String, String> {
        val request = Request.Builder()
            .url("https://account.xiaomi.com/pass/serviceLogin?sid=unlockApi")
            .header("User-Agent", "com.siroha.flashtool")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")
            val ssecurity = (response.priorResponse ?: response).header("extension-pragma")?.let {
                JSONObject(it).getString("ssecurity")
            } ?: throw IOException("ssecurity not found")
            val serviceToken = response.headers("Set-Cookie")
                .filter { !it.substringBefore(";").endsWith("=null") }
                .joinToString(";") { it.substringBefore(";") }
            return ssecurity to serviceToken
        }
    }

    /** MD5(deviceId) hex — used as the "pcId" field in the unlock request. */
    fun pcIdFor(deviceId: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(deviceId.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Xiaomi's signed+encrypted request scheme: every param is AES-CBC
     * encrypted individually, a separate HMAC-SHA1-then-AES "sign" value is
     * computed over the unencrypted param string, and a final SHA1
     * "signature" is computed over the fully-encoded request — all keyed
     * off the account's ssecurity. The response is base64+AES decrypted the
     * same way in reverse.
     */
    fun send(
        host: String,
        path: String,
        paramOrder: List<String>,
        paramsRaw: Map<String, String>,
        ssecurity: String,
        serviceToken: String
    ): JSONObject {
        return try {
            val key = Base64.decode(ssecurity, Base64.DEFAULT)
            val iv = "0102030405060708".toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)

            val params = paramsRaw.toMutableMap()
            if (params.containsKey("data")) {
                params["data"] = Base64.encodeToString(params["data"]!!.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }
            params.putIfAbsent("sid", UNLOCK_SID)

            val encryptParam: (String) -> String = { input ->
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
                Base64.encodeToString(cipher.doFinal(input.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            }

            val signParams = paramOrder.joinToString("&") { k -> "$k=${params[k]}" }
            val signStr = "POST\n$path\n$signParams"

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val hmacHex = mac.doFinal(signStr.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            val currentSign = Base64.encodeToString(cipher.doFinal(hmacHex.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

            val encodedParams = paramOrder.map { k -> "$k=${encryptParam(params[k]!!)}" }
            val sha1Input = "POST&$path&${encodedParams.joinToString("&")}&sign=$currentSign&$ssecurity"
            val signature = Base64.encodeToString(
                MessageDigest.getInstance("SHA1").digest(sha1Input.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )

            val formBody = FormBody.Builder().apply {
                paramOrder.forEach { k -> add(k, encryptParam(params[k]!!)) }
                add("sign", currentSign)
                add("signature", signature)
            }.build()

            val cookieString = serviceToken.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains("=") }
                .joinToString("; ")

            val request = Request.Builder()
                .url("$host$path")
                .post(formBody)
                .header("User-Agent", "com.siroha.flashtool")
                .header("Cookie", cookieString)
                .build()

            val response = OkHttpClient().newCall(request).execute()
            val responseBody = response.body?.string() ?: throw IOException("Empty response from server")

            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(Base64.decode(responseBody, Base64.DEFAULT))
            val decryptedString = String(decrypted, Charsets.UTF_8)
            val jsonString = String(Base64.decode(decryptedString, Base64.DEFAULT), Charsets.UTF_8)
            JSONObject(jsonString)
        } catch (e: Exception) {
            JSONObject().put("error", "Request failed: ${e.javaClass.simpleName} - ${e.message}")
        }
    }

    fun hexStringToByteArray(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have an even length" }
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
