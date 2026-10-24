package com.hongguo.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.hongguo.account.protocol.AccountCredentials
import com.hongguo.account.protocol.AccountCredentialSource
import com.hongguo.account.protocol.CloudProfile
import com.hongguo.account.protocol.validBusinessAccountId
import com.hongguo.android.LibraryCodec
import com.hongguo.android.LibrarySnapshot
import com.hongguo.api.util.Json
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One private, encrypted and synchronously committed account snapshot. No preferences or logs. */
internal class AccountStore(context: Context) : AccountPersistence {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "hongguo-account.enc"))

    override fun load(): AccountDisk = synchronized(LOCK) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized AccountDisk()
        try {
            val encoded = file.openRead().use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > MAX_BYTES) throw IOException("账号文件过大")
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
            if (encoded.size < 32 || encoded[0] != VERSION) throw IOException("账号文件格式无效")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
            cipher.updateAAD(AAD)
            AccountDiskCodec.decode(String(cipher.doFinal(encoded.copyOfRange(13, encoded.size)), Charsets.UTF_8))
        } catch (_: Exception) {
            // Preserve the file for recovery; do not silently replace inaccessible credentials.
            throw IOException("无法读取本机加密账号资料，请退出账号后重新登录")
        }
    }

    override fun save(value: AccountDisk) = synchronized(LOCK) {
        val bytes = AccountDiskCodec.encode(value).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES - 64) throw IOException("账号本机资料过大，请清理后重试")
        val encoded = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(bytes)
        } catch (_: Exception) { throw IOException("无法加密保存账号资料") }
        var output: java.io.FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(encoded)
            output.fd.sync()
            file.finishWrite(output)
        } catch (_: Exception) {
            output?.let { file.failWrite(it) }
            throw IOException("账号资料保存失败，请检查本机剩余空间")
        }
    }

    override fun erase() = synchronized(LOCK) {
        // This is deliberately confined to the account snapshot. Guest LibraryStore is independent.
        file.delete()
        if (listOf(file.baseFile, File(file.baseFile.path + ".bak"), File(file.baseFile.path + ".new")).any { it.exists() }) {
            throw IOException("本机账号登录资料未能清除")
        }
    }

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "hongguo.personal.account.v1"
        private const val MAX_BYTES = 12 * 1024 * 1024
        private const val VERSION: Byte = 1
        private val AAD = "com.hongguo.android.account.v1".toByteArray(Charsets.US_ASCII)
        private val LOCK = Any()
    }
}

/** Pure codec shared by the encrypted store and persistence regression tests. */
internal object AccountDiskCodec {
    private fun number(value: Any?): Long = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0
        else -> 0
    }.coerceAtLeast(0)
    private fun map(value: Any?): Map<String, Any?> = Json.optObj(value) ?: emptyMap()
    private fun list(value: Any?): List<Any?> = Json.optArr(value)
    private fun entry(value: com.hongguo.android.LibraryEntry) = Json.parse(LibraryCodec.encode(LibrarySnapshot(history = listOf(value))))
    private fun readEntry(value: Any?): com.hongguo.android.LibraryEntry? = LibraryCodec.decode(Json.stringify(value)).history.firstOrNull()
    private fun library(cache: AccountCache) = Json.parse(LibraryCodec.encode(LibrarySnapshot(cache.favorites, cache.history)))

    private fun websiteOnly(value: AccountCredentials?): AccountCredentials? = value?.also {
        if (it.source != AccountCredentialSource.WEBSITE) throw IOException("待验证的官网登录资料来源无效")
    }

    private fun decodeWebsiteCredentials(value: Any?): AccountCredentials? {
        if (value == null) return null
        if (value !is String) throw IOException("待验证的官网登录资料格式无效")
        // This optional field did not exist in old snapshots. Its new entries must have an explicit
        // website provenance; the general credential decoder's legacy default must not grant it.
        if (map(Json.parse(value))["source"] != AccountCredentialSource.WEBSITE.name)
            throw IOException("待验证的官网登录资料来源无效")
        return websiteOnly(AccountCredentials.fromJson(value))
    }

    fun encode(value: AccountDisk): String = Json.stringify(linkedMapOf(
        "version" to 1,
        "credentials" to value.credentials?.toJson(),
        "websiteCredentials" to websiteOnly(value.websiteCredentials)?.toJson(),
        "profile" to value.profile?.let { mapOf("id" to it.id, "nickname" to it.nickname) },
        "smsRetryAt" to value.smsRetryAt,
        "accounts" to value.accounts.mapValues { (_, cache) -> mapOf(
            "library" to library(cache),
            "reservations" to Json.parse(LibraryCodec.encode(LibrarySnapshot(history = cache.reservations))),
            "online" to cache.reservationOnlineIds.toList(),
            "pending" to cache.pending.mapValues { (_, progress) -> mapOf("entry" to entry(progress.entry), "duration" to progress.durationMs, "revision" to progress.revision) },
            "attempts" to cache.favoriteAttempts.mapValues { (_, attempt) -> mapOf("add" to attempt.add, "at" to attempt.startedAt) },
            "syncEnabled" to cache.syncEnabled, "lastSyncAt" to cache.lastSyncAt,
        ) },
    ))

    fun decode(value: String): AccountDisk {
        val root = map(Json.parse(value))
        if (number(root["version"]) != 1L) throw IOException("账号数据版本不支持")
        val profileRaw = map(root["profile"])
        val profile = (profileRaw["id"] as? String)?.takeIf(::validBusinessAccountId)?.let {
            CloudProfile(it, (profileRaw["nickname"] as? String).orEmpty().take(256))
        }
        val credentials = (root["credentials"] as? String)?.let { AccountCredentials.fromJson(it) }
        if ((profile == null) != (credentials == null)) throw IOException("账号数据不完整")
        val websiteCredentials = decodeWebsiteCredentials(root["websiteCredentials"])
        val accounts = map(root["accounts"]).filterKeys(::validBusinessAccountId).mapValues { (_, raw) ->
            val cache = map(raw)
            val library = LibraryCodec.decode(Json.stringify(cache["library"]))
            val reservations = LibraryCodec.decode(Json.stringify(cache["reservations"])).history
            AccountCache(
                history = library.history, favorites = library.favorites, reservations = reservations,
                reservationOnlineIds = list(cache["online"]).filterIsInstance<String>().filter(::validContentId).toSet(),
                pending = map(cache["pending"]).entries.mapNotNull { (id, item) ->
                    val pending = map(item)
                    readEntry(pending["entry"])?.takeIf { it.card.id == id && it.episodeId.isNotBlank() }?.let {
                        id to PendingProgress(it, number(pending["duration"]), number(pending["revision"]))
                    }
                }.take(200).toMap(),
                favoriteAttempts = map(cache["attempts"]).filterKeys(::validContentId).mapValues { (_, item) ->
                    val attempt = map(item)
                    FavoriteAttempt(attempt["add"] == true, number(attempt["at"]))
                },
                syncEnabled = cache["syncEnabled"] == true, lastSyncAt = number(cache["lastSyncAt"]),
            )
        }
        return AccountDisk(credentials, profile, accounts, number(root["smsRetryAt"]), websiteCredentials)
    }
}

internal fun validContentId(value: String): Boolean = value.matches(Regex("[0-9]{1,30}")) && value.any { it != '0' }
