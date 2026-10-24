package com.hongguo.account

import com.hongguo.account.protocol.AccountCredentials
import com.hongguo.account.protocol.AccountCredentialSource
import com.hongguo.account.protocol.CloudProfile
import com.hongguo.api.util.Json
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

    @Test fun preWebsiteSnapshotStillReadsWithoutInventingAWebsiteSession() {
        val legacyCredential = """{"cookie":"sessionid=legacy-fixture","deviceId":"7100000000000000001","installId":"7200000000000000001","device":{}}"""
        val legacy = Json.stringify(mapOf("version" to 1, "credentials" to legacyCredential,
            "profile" to mapOf("id" to "7000000000000000001", "nickname" to "原账号"),
            "smsRetryAt" to 1700000060000L, "accounts" to emptyMap<String, Any>()))
        val decoded = AccountDiskCodec.decode(legacy)
        assertEquals("7000000000000000001", decoded.profile?.id)
        assertEquals("sessionid=legacy-fixture", decoded.credentials?.cookie)
        assertEquals(1700000060000L, decoded.smsRetryAt)
        assertNull(decoded.websiteCredentials)
    }

    @Test fun websiteOnlySessionRoundTripsWithoutClaimingABusinessIdentity() {
        val website = AccountCredentials("sessionid=website-private-fixture", source = AccountCredentialSource.WEBSITE)
        val source = AccountDisk(websiteCredentials = website, smsRetryAt = 1700000060000L)
        val decoded = AccountDiskCodec.decode(AccountDiskCodec.encode(source))
        assertEquals(source, decoded)
        assertNull(decoded.credentials)
        assertNull(decoded.profile)
        assertTrue(decoded.accounts.isEmpty())
        assertEquals(AccountCredentialSource.WEBSITE, decoded.websiteCredentials?.source)
        assertFalse(source.toString().contains("website-private-fixture"))
        assertFalse(decoded.toString().contains("website-private-fixture"))
        assertFalse(website.toString().contains("website-private-fixture"))
    }

    @Test fun pendingWebsiteSessionNeverOverwritesTheActiveAppAccount() {
        val active = AccountCredentials("sessionid=active-app-fixture", "7100000000000000001", "7200000000000000001",
            source = AccountCredentialSource.APP)
        val website = AccountCredentials("sessionid=pending-website-fixture", source = AccountCredentialSource.WEBSITE)
        val source = AccountDisk(active, CloudProfile("7000000000000000001", "原账号"),
            websiteCredentials = website)
        val decoded = AccountDiskCodec.decode(AccountDiskCodec.encode(source))
        assertEquals(active, decoded.credentials)
        assertEquals(source.profile, decoded.profile)
        assertEquals(website, decoded.websiteCredentials)
        assertFalse(decoded.toString().contains("active-app-fixture"))
        assertFalse(decoded.toString().contains("pending-website-fixture"))
    }

    @Test fun appCredentialsCannotBeEncodedOrDecodedAsVerifiedWebsiteCredentials() {
        val app = AccountCredentials("sessionid=app-private-fixture", source = AccountCredentialSource.APP)
        assertThrows(java.io.IOException::class.java) {
            AccountDiskCodec.encode(AccountDisk(websiteCredentials = app))
        }
        val encoded = Json.stringify(mapOf("version" to 1, "websiteCredentials" to app.toJson(),
            "accounts" to emptyMap<String, Any>()))
        assertThrows(java.io.IOException::class.java) { AccountDiskCodec.decode(encoded) }
    }

    @Test fun websiteFieldRequiresExplicitKnownProvenanceAndCorrectValueType() {
        val legacy = """{"cookie":"sessionid=fixture"}"""
        val unknown = """{"cookie":"sessionid=fixture","source":"UNRECOGNIZED"}"""
        for (value in listOf<Any>(legacy, unknown, mapOf("cookie" to "sessionid=fixture", "source" to "WEBSITE"))) {
            val encoded = Json.stringify(mapOf("version" to 1, "websiteCredentials" to value,
                "accounts" to emptyMap<String, Any>()))
            assertThrows(java.io.IOException::class.java) { AccountDiskCodec.decode(encoded) }
        }
    }

    @Test fun websiteSessionDoesNotRelaxTheExistingActiveCredentialProfilePairInvariant() {
        val website = AccountCredentials("sessionid=website-fixture", source = AccountCredentialSource.WEBSITE)
        for (partial in listOf(
            mapOf("profile" to mapOf("id" to "7000000000000000001")),
            mapOf("credentials" to website.toJson()),
        )) {
            val encoded = Json.stringify(mapOf("version" to 1, "websiteCredentials" to website.toJson(),
                "accounts" to emptyMap<String, Any>()) + partial)
            assertThrows(java.io.IOException::class.java) { AccountDiskCodec.decode(encoded) }
        }
    }
}
