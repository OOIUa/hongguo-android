package com.hongguo.account

import com.hongguo.account.protocol.AccountCredentials
import com.hongguo.account.protocol.CloudProfile
import com.hongguo.android.DramaCard
import com.hongguo.android.LibraryEntry
import org.junit.Assert.*
import org.junit.Test

class AccountDiskCodecTest {
    @Test fun roundTripKeepsNineteenDigitIdentityQueueAndAccountIsolation() {
        val uid = "7000000000000000001"
        val other = "7000000000000000002"
        val entry = LibraryEntry(DramaCard("7300000000000000001", "甲", "", 80, ""), "7400000000000000001", 4, 9999, 1700000000000)
        val cache = AccountCache(history = listOf(entry), favorites = listOf(entry), reservations = listOf(entry),
            reservationOnlineIds = setOf(entry.card.id), pending = mapOf(entry.card.id to PendingProgress(entry, 60000, 42)),
            favoriteAttempts = mapOf(entry.card.id to FavoriteAttempt(true, 1700000000001)), syncEnabled = true)
        val source = AccountDisk(AccountCredentials("sessionid=fixture-private", "7100000000000000001", "7200000000000000001"),
            CloudProfile(uid, "测试"), mapOf(uid to cache, other to AccountCache()), 1700000060000)
        val decoded = AccountDiskCodec.decode(AccountDiskCodec.encode(source))
        assertEquals(source, decoded)
        assertEquals(entry.card.id, decoded.accounts.getValue(uid).pending.keys.single())
        assertTrue(decoded.accounts.getValue(other).history.isEmpty())
        assertFalse(source.toString().contains("fixture-private"))
    }

    @Test fun partialCredentialProfileSnapshotIsRejected() {
        val result = runCatching { AccountDiskCodec.decode("{\"version\":1,\"profile\":{\"id\":\"7000000000000000001\"},\"accounts\":{}}") }
        assertTrue(result.isFailure)
    }
}
