package com.hongguo.account

import com.hongguo.account.protocol.AccountCredentials
import com.hongguo.account.protocol.AccountCredentialSource
import com.hongguo.account.protocol.AccountProtocolException
import com.hongguo.account.protocol.AccountVerificationStage
import com.hongguo.account.protocol.CloudFavorite
import com.hongguo.account.protocol.CloudProfile
import com.hongguo.account.protocol.CloudProgress
import com.hongguo.account.protocol.CloudReservation
import com.hongguo.account.protocol.PhoneLogin
import com.hongguo.account.protocol.Series
import com.hongguo.account.protocol.WebLogin
import com.hongguo.android.DramaCard
import com.hongguo.android.LibraryEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AccountRepositoryTest {
    private val uid = "7000000000000000001"
    private val series = Series("7300000000000000001", "测试短剧", "https://example.invalid/cover", "简介", "全 80 集")
    private val card = DramaCard(series.id, series.title, series.cover, 80, series.description)
    private val entry get() = LibraryEntry(card, "7400000000000000001", 3, 12000, 1700000000000)
    private val credentials = AccountCredentials("sessionid=test-private-fixture", "7100000000000000001", "7200000000000000001")

    private class MemoryStore(var value: AccountDisk = AccountDisk()) : AccountPersistence {
        var writes = 0
        var failSave = false
        var failLoad = false
        var failErase = false
        var erases = 0
        var onLoad: (() -> Unit)? = null
        override fun load(): AccountDisk {
            onLoad?.invoke()
            if (failLoad) throw IOException("fixture")
            return value
        }
        override fun save(value: AccountDisk) {
            if (failSave) throw IOException("fixture")
            this.value = value; writes++
        }
        override fun erase() {
            if (failErase) throw IOException("fixture")
            value = AccountDisk(); erases++
        }
    }

    private inner class Gateway : AccountGateway {
        var profileValue = CloudProfile(uid, "测试账号")
        var profileError: Exception? = null
        var historyError: Exception? = null
        var favoritesError: Exception? = null
        var writeError: Exception? = null
        var uploadError: Exception? = null
        var favoritesValue = emptyList<CloudFavorite>()
        var historyValue = emptyList<CloudProgress>()
        var reservationsValue = emptyList<CloudReservation>()
        var setFavoriteCalls = 0
        var uploaded = mutableListOf<CloudProgress>()
        var onProfile: (() -> Unit)? = null
        var onUpload: (() -> Unit)? = null
        var beforeFavoriteWrite: (() -> Unit)? = null
        var beforeFavoriteReadReturn: (() -> Unit)? = null
        var applyFavorite = true
        val profileCredentials = mutableListOf<AccountCredentials>()
        var historyCalls = 0
        override fun prepareCredentials(credentials: AccountCredentials) = credentials.copy(deviceId = "7100000000000000001", installId = "7200000000000000001")
        override fun profile(credentials: AccountCredentials): CloudProfile {
            profileCredentials += credentials
            onProfile?.invoke(); profileError?.let { throw it }; return profileValue
        }
        override fun history(credentials: AccountCredentials): List<CloudProgress> { historyCalls++; historyError?.let { throw it }; return historyValue }
        override fun favorites(credentials: AccountCredentials): List<CloudFavorite> {
            favoritesError?.let { throw it }
            val snapshot = favoritesValue
            beforeFavoriteReadReturn?.invoke()
            return snapshot
        }
        override fun reservations(credentials: AccountCredentials, online: Boolean) = reservationsValue.filter { it.online == online }
        override fun setFavorite(credentials: AccountCredentials, id: String, add: Boolean) {
            beforeFavoriteWrite?.invoke(); setFavoriteCalls++; writeError?.let { throw it }
            if (applyFavorite) favoritesValue = if (add) listOf(CloudFavorite(series, 1700000000000)) else emptyList()
        }
        override fun upload(credentials: AccountCredentials, progress: CloudProgress, episodeCount: Int) {
            uploaded += progress; onUpload?.invoke(); uploadError?.let { throw it }
        }
        override fun cancelAll() = Unit
    }

    private class Login : AccountLogin {
        var sends = 0
        var verifies = 0
        var polls = 0
        var sendError: Exception? = null
        var qrExpiry = 3
        var confirmed = false
        override fun qr() = WebLogin.Qr(byteArrayOf(1, 2, 3), qrExpiry)
        override fun poll(): Boolean { polls++; return confirmed }
        override fun credentials() = AccountCredentials("sessionid=fixture")
        override fun send(number: String): PhoneLogin.Sent { sends++; sendError?.let { throw it }; return PhoneLogin.Sent("138****0000", 60) }
        override fun verify(code: String): AccountCredentials { verifies++; return credentials() }
        override fun cancel() = Unit
    }

    private fun TestScope.repository(store: MemoryStore, api: Gateway, login: Login = Login()) = AccountRepository(
        store, api, { login }, backgroundScope, StandardTestDispatcher(testScheduler), { 1700000000000 + testScheduler.currentTime })

    private fun signedStore(cache: AccountCache = AccountCache()) = MemoryStore(AccountDisk(credentials, CloudProfile(uid, "测试账号"), mapOf(uid to cache)))

    @Test fun networkRestorePreservesCredentialsAndCachedListsButDoesNotAllowWrites() = runTest {
        val store = signedStore(AccountCache(history = listOf(entry), syncEnabled = true))
        val api = Gateway().apply { profileError = AccountProtocolException("offline", verificationStage = AccountVerificationStage.PROFILE,
            diagnostic = "profile-v1;class=BUSINESS_ID_MISSING") }
        val repo = repository(store, api); runCurrent()
        assertEquals(AccountSessionStatus.NEEDS_VERIFICATION, repo.state.value.sessionStatus)
        assertNotNull(repo.state.value.loginDiagnostic)
        assertEquals(entry, repo.state.value.history.single())
        assertEquals(credentials, store.value.credentials)
        assertNull(repo.ownerKey)
        repo.reportProgress(entry, 60000, true, "$uid:0"); runCurrent()
        assertTrue(api.uploaded.isEmpty())
        api.profileError = null; repo.refresh(); runCurrent()
        assertEquals(AccountSessionStatus.LOGGED_IN, repo.state.value.sessionStatus)
        assertNull(repo.state.value.loginDiagnostic)
    }

    @Test fun confirmedExpiredSessionClearsCredentialsAndPendingWithoutDeletingAccountCache() = runTest {
        val queued = PendingProgress(entry, 60000, 1)
        val store = signedStore(AccountCache(history = listOf(entry), pending = mapOf(card.id to queued), syncEnabled = true))
        val api = Gateway().apply { profileError = AccountProtocolException("expired", requiresLogin = true) }
        val repo = repository(store, api); runCurrent()
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
        assertNull(store.value.credentials)
        assertTrue(store.value.accounts.getValue(uid).pending.isEmpty())
        assertEquals(entry, store.value.accounts.getValue(uid).history.single())
        assertTrue(api.uploaded.isEmpty())
    }

    @Test fun failedLogoutRewriteErasesCredentialsBeforeAFreshRepositoryCanRestoreThem() = runTest {
        val store = signedStore(AccountCache(history = listOf(entry)))
        val api = Gateway(); val repo = repository(store, api); runCurrent()
        store.failSave = true
        repo.logout()
        assertEquals(1, store.erases)
        assertNull(store.value.credentials)
        assertTrue(store.value.accounts.isEmpty())
        assertFalse(repo.state.value.localClearFailed)
        assertTrue(repo.state.value.message.orEmpty().contains("账号缓存"))
        val restarted = repository(store, api); runCurrent()
        assertEquals(AccountSessionStatus.SIGNED_OUT, restarted.state.value.sessionStatus)
        assertNull(restarted.ownerKey)
    }

    @Test fun normalLogoutKeepsAccountListsWithoutInvokingDestructiveFallback() = runTest {
        val store = signedStore(AccountCache(history = listOf(entry)))
        val api = Gateway(); val repo = repository(store, api); runCurrent()
        repo.logout()
        assertEquals(0, store.erases)
        assertNull(store.value.credentials)
        assertEquals(listOf(entry), store.value.accounts.getValue(uid).history)
    }

    @Test fun failedRewriteAndEraseExposeRecoverableClearFailureEvenAfterMessageDismissed() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent()
        store.failSave = true; store.failErase = true
        repo.logout()
        assertNull(repo.ownerKey)
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
        assertTrue(repo.state.value.localClearFailed)
        assertNotNull(store.value.credentials)
        repo.consumeMessage()
        assertTrue(repo.state.value.localClearFailed)
        store.failSave = false
        repo.logout()
        assertFalse(repo.state.value.localClearFailed)
        assertNull(store.value.credentials)
    }

    @Test fun expiredSessionAlsoErasesOldCredentialSnapshotWhenRewriteFails() = runTest {
        val store = signedStore().apply { failSave = true }
        val api = Gateway().apply { profileError = AccountProtocolException("expired", requiresLogin = true) }
        val repo = repository(store, api); runCurrent()
        assertEquals(1, store.erases)
        assertNull(store.value.credentials)
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
    }

    @Test fun canceledLateLoginCannotRestoreAccountAndLeavingSuccessfulScreenKeepsOwner() = runTest {
        val store = MemoryStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent()
        api.onProfile = { repo.cancelLogin() }
        repo.completeWebLogin("sessionid=fixture"); runCurrent()
        assertNull(store.value.credentials)
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
        api.onProfile = null
        repo.completeWebLogin("sessionid=fixture"); runCurrent()
        val owner = repo.ownerKey
        assertNotNull(owner)
        repo.cancelLogin()
        assertEquals(owner, repo.ownerKey)
    }

    @Test fun startingThenCancelingAnotherLoginKeepsTheVerifiedPlayersOwner() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent(); repo.setSyncEnabled(true)
        val owner = repo.ownerKey
        repo.startQrLogin(); runCurrent()
        assertEquals(owner, repo.ownerKey)
        repo.cancelLogin(); runCurrent()
        assertEquals(owner, repo.ownerKey)
        repo.reportProgress(entry, 60000, true, owner); runCurrent()
        assertEquals(1, api.uploaded.size)
        assertEquals(uid, repo.state.value.profile?.id)
    }

    @Test fun loginDoesNotExposeSuccessIfEncryptedStoreCannotCommit() = runTest {
        val store = MemoryStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent()
        store.failSave = true
        repo.completeWebLogin("sessionid=fixture"); runCurrent()
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
        assertNull(repo.ownerKey)
        assertNotNull(repo.state.value.message)
    }

    @Test fun postLoginFailureShowsSafeStageAndNeverPersistsUnverifiedIdentity() = runTest {
        val store = MemoryStore()
        val api = Gateway().apply {
            profileError = AccountProtocolException("官网登录会话已失效（错误码 13）", 13, true,
                AccountVerificationStage.WEBSITE)
        }
        val repo = repository(store, api); runCurrent()
        repo.completeWebLogin("sessionid=private-browser-session"); runCurrent()
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
        assertNull(store.value.credentials)
        assertNull(repo.ownerKey)
        assertTrue(repo.state.value.message.orEmpty().contains("官网登录验证"))
        assertTrue(repo.state.value.message.orEmpty().contains("13"))
        assertFalse(repo.state.value.message.orEmpty().contains("private-browser-session"))
        api.profileError = IOException("https://provider.invalid?sessionid=private-browser-session")
        repo.completeWebLogin("sessionid=private-browser-session"); runCurrent()
        assertFalse(repo.state.value.message.orEmpty().contains("provider.invalid"))
        assertFalse(repo.state.value.message.orEmpty().contains("private-browser-session"))
    }

    @Test fun smsAndQrUseTheSameSafePostLoginFailureDetails() = runTest {
        val login = Login().apply { confirmed = true }
        val api = Gateway().apply {
            profileError = AccountProtocolException("未返回有效账号身份", verificationStage = AccountVerificationStage.PROFILE)
        }
        val repo = repository(MemoryStore(), api, login); runCurrent()
        repo.sendSms("13800138000"); runCurrent()
        repo.verifySms("123456"); runCurrent()
        assertTrue(repo.state.value.message.orEmpty().contains("红果账号资料验证"))
        repo.startQrLogin(); runCurrent(); advanceTimeBy(2_001); runCurrent()
        assertTrue(repo.state.value.message.orEmpty().contains("红果账号资料验证"))
        assertNull(repo.ownerKey)
    }

    @Test fun verifiedWebsiteSessionIsRetainedForRetryWithoutGrantingCloudOwnership() = runTest {
        val store = MemoryStore()
        val login = Login()
        val api = Gateway().apply {
            profileError = AccountProtocolException("手机账号授权未确认", verificationStage = AccountVerificationStage.PROFILE,
                diagnostic = "classification=BUSINESS_ID_MISSING")
        }
        val repo = repository(store, api, login); runCurrent()
        repo.completeWebLogin("sessionid=website-fixture"); runCurrent()
        assertEquals(AccountSessionStatus.WEBSITE_ONLY, repo.state.value.sessionStatus)
        assertNull(repo.state.value.profile)
        assertNull(repo.ownerKey)
        assertNull(store.value.credentials)
        assertEquals("sessionid=website-fixture", store.value.websiteCredentials?.cookie)
        assertEquals("classification=BUSINESS_ID_MISSING", repo.state.value.loginDiagnostic)
        repo.setSyncEnabled(true); repo.reportProgress(entry, 60000, true, null)
        repo.setFavorite(card, true); runCurrent()
        assertEquals(0, api.historyCalls)
        assertTrue(api.uploaded.isEmpty())
        assertEquals(0, api.setFavoriteCalls)
        api.profileError = null
        repo.retryWebsiteAccount(); runCurrent()
        assertEquals(0, login.sends)
        assertEquals(0, login.polls)
        assertEquals(AccountSessionStatus.LOGGED_IN, repo.state.value.sessionStatus)
        assertNull(store.value.websiteCredentials)
        assertNull(repo.state.value.loginDiagnostic)
        assertNotNull(repo.ownerKey)
        assertEquals(api.profileCredentials.first().deviceId, api.profileCredentials.last().deviceId)
    }

    @Test fun websiteOnlyRestartWaitsForExplicitRetryAndLogoutErasesSavedSession() = runTest {
        val store = MemoryStore(AccountDisk(websiteCredentials = credentials))
        val api = Gateway()
        val repo = repository(store, api); runCurrent()
        assertEquals(AccountSessionStatus.WEBSITE_ONLY, repo.state.value.sessionStatus)
        assertTrue(api.profileCredentials.isEmpty())
        assertNull(repo.ownerKey)
        val session = repo.sessionKey
        repo.logout(); runCurrent()
        assertNotEquals(session, repo.sessionKey)
        assertNull(store.value.websiteCredentials)
        val restarted = repository(store, api); runCurrent()
        assertEquals(AccountSessionStatus.SIGNED_OUT, restarted.state.value.sessionStatus)
    }

    @Test fun explicitWebsiteExpiryClearsPendingSessionButNetworkFailureRetainsIt() = runTest {
        val store = MemoryStore(AccountDisk(websiteCredentials = credentials))
        val api = Gateway().apply { profileError = AccountProtocolException("offline", verificationStage = AccountVerificationStage.WEBSITE) }
        val repo = repository(store, api); runCurrent()
        repo.retryWebsiteAccount(); runCurrent()
        assertEquals(AccountSessionStatus.WEBSITE_ONLY, repo.state.value.sessionStatus)
        assertNotNull(store.value.websiteCredentials)
        api.profileError = AccountProtocolException("expired", 13, true, AccountVerificationStage.WEBSITE)
        repo.retryWebsiteAccount(); runCurrent()
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
        assertNull(store.value.websiteCredentials)
    }

    @Test fun rejectedNewWebsiteCookieCannotEraseThePreviouslyConfirmedWebsiteSession() = runTest {
        val store = MemoryStore(AccountDisk(websiteCredentials = credentials))
        val api = Gateway().apply { profileError = AccountProtocolException("expired", 13, true, AccountVerificationStage.WEBSITE) }
        val repo = repository(store, api); runCurrent()
        repo.importCookie("sessionid=different-expired-session"); runCurrent()
        assertEquals(credentials, store.value.websiteCredentials)
        assertEquals(AccountSessionStatus.WEBSITE_ONLY, repo.state.value.sessionStatus)
    }

    @Test fun lateWebsiteProfileFailureCannotSaveSessionAfterLogout() = runTest {
        val store = MemoryStore()
        val api = Gateway().apply { profileError = AccountProtocolException("unverified", verificationStage = AccountVerificationStage.PROFILE) }
        val repo = repository(store, api); runCurrent()
        api.onProfile = { repo.logout() }
        repo.completeWebLogin("sessionid=late-fixture"); runCurrent()
        assertNull(store.value.websiteCredentials)
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
    }

    @Test fun explicitAppImportHasItsOwnSourceAndNeverClaimsWebsiteSessionWhenItFails() = runTest {
        val store = MemoryStore()
        val api = Gateway().apply { profileError = AccountProtocolException("unverified", verificationStage = AccountVerificationStage.PROFILE) }
        val repo = repository(store, api); runCurrent()
        repo.importAppCookie("sessionid=app-fixture"); runCurrent()
        assertEquals(AccountCredentialSource.APP, api.profileCredentials.single().source)
        assertNull(store.value.websiteCredentials)
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
        api.profileError = null
        repo.importAppCookie("sessionid=app-fixture"); runCurrent()
        assertEquals(AccountCredentialSource.APP, store.value.credentials?.source)
        assertNotNull(repo.ownerKey)
    }

    @Test fun failedNewWebsiteLoginDoesNotReplacePreviouslyVerifiedCloudOwner() = runTest {
        val store = signedStore()
        val api = Gateway()
        val repo = repository(store, api); runCurrent()
        val owner = repo.ownerKey
        api.profileError = AccountProtocolException("unverified", verificationStage = AccountVerificationStage.PROFILE)
        repo.completeWebLogin("sessionid=new-website-fixture"); runCurrent()
        assertEquals(owner, repo.ownerKey)
        assertEquals(AccountSessionStatus.LOGGED_IN, repo.state.value.sessionStatus)
        assertEquals(credentials, store.value.credentials)
        assertNull(store.value.websiteCredentials)
    }

    @Test fun lateDiskLoadCannotResurrectAUserLoggedOutSession() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api)
        store.onLoad = { repo.logout() }
        runCurrent()
        assertEquals(AccountSessionStatus.SIGNED_OUT, repo.state.value.sessionStatus)
        assertNull(repo.ownerKey)
        assertNull(store.value.credentials)
    }

    @Test fun switchingAccountsNeverUploadsTheOldPlayersObservation() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent(); repo.setSyncEnabled(true)
        val previousOwner = repo.ownerKey
        repo.logout()
        api.profileValue = CloudProfile("7000000000000000002", "另一账号")
        repo.completeWebLogin("sessionid=other-fixture"); runCurrent(); repo.setSyncEnabled(true)
        repo.reportProgress(entry, 60000, true, previousOwner); runCurrent()
        assertTrue(api.uploaded.isEmpty())
        assertTrue(repo.state.value.history.isEmpty())
        assertNotEquals(previousOwner, repo.ownerKey)
    }

    @Test fun independentListFailurePreservesOnlyThatAccountsLastSuccessfulList() = runTest {
        val store = signedStore(AccountCache(history = listOf(entry), favorites = listOf(entry)))
        val api = Gateway().apply { historyError = IOException("offline") }
        val repo = repository(store, api); runCurrent()
        assertEquals(listOf(entry), repo.state.value.history)
        assertNotNull(repo.state.value.historyError)
        assertTrue(repo.state.value.favorites.isEmpty())
        assertNull(repo.state.value.favoritesError)
        assertEquals(AccountSessionStatus.LOGGED_IN, repo.state.value.sessionStatus)
    }

    @Test fun progressRequiresOptInAndMatchingOwnerAndConvertsOneBasedEpisodeIndex() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent()
        val owner = repo.ownerKey
        repo.reportProgress(entry, 60000, true, owner); runCurrent()
        assertTrue(api.uploaded.isEmpty())
        repo.setSyncEnabled(true)
        repo.reportProgress(entry, 60000, true, "old-owner"); runCurrent()
        assertTrue(api.uploaded.isEmpty())
        repo.reportProgress(entry, 60000, true, owner); runCurrent()
        assertEquals(2, api.uploaded.single().episodeIndex)
        assertEquals(12000L, api.uploaded.single().position)
        assertEquals(60000L, api.uploaded.single().duration)
        val writes = store.writes
        repeat(3) { repo.reportProgress(entry.copy(updatedAt = it.toLong()), 60000, false, owner); runCurrent() }
        assertEquals(1, api.uploaded.size)
        assertEquals(writes, store.writes)
        repo.logout()
        repo.reportProgress(entry.copy(positionMs = 14000), 60000, true, owner); runCurrent()
        assertEquals(1, api.uploaded.size)
    }

    @Test fun progressIsCoalescedThrottledAndFailedAttemptRemainsDurable() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent(); repo.setSyncEnabled(true)
        val owner = repo.ownerKey
        repo.reportProgress(entry, 60000, false, owner); runCurrent()
        assertEquals(1, api.uploaded.size)
        api.uploadError = IOException("offline")
        repo.reportProgress(entry.copy(positionMs = 14000), 60000, false, owner); runCurrent()
        repo.reportProgress(entry.copy(positionMs = 18000), 60000, false, owner); runCurrent()
        assertEquals(1, api.uploaded.size)
        advanceTimeBy(15000); runCurrent()
        assertEquals(2, api.uploaded.size)
        assertEquals(18000L, api.uploaded.last().position)
        assertEquals(1, store.value.accounts.getValue(uid).pending.size)
        repo.setSyncEnabled(false); advanceTimeBy(30000); runCurrent()
        assertEquals(2, api.uploaded.size)
        assertTrue(store.value.accounts.getValue(uid).pending.isEmpty())
    }

    @Test fun progressObservationNeverPersistsSynchronouslyOnTheCallingThread() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent(); repo.setSyncEnabled(true)
        val writes = store.writes
        repo.reportProgress(entry, 60000, true, repo.ownerKey)
        assertEquals(writes, store.writes)
        assertTrue(api.uploaded.isEmpty())
        runCurrent()
        assertEquals(1, api.uploaded.size)
        assertTrue(store.writes > writes)
    }

    @Test fun forceFlushExpeditesWaitingTimerWithoutReplayingAnInflightWrite() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent(); repo.setSyncEnabled(true)
        val owner = repo.ownerKey
        repo.reportProgress(entry, 60000, false, owner); runCurrent()
        assertEquals(1, api.uploaded.size)
        repo.reportProgress(entry.copy(positionMs = 16000), 60000, false, owner); runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, api.uploaded.size)
        api.onUpload = { repo.flushProgress(owner) }
        repo.flushProgress(owner); runCurrent()
        assertEquals(2, api.uploaded.size)
        assertEquals(16000L, api.uploaded.last().position)
        advanceTimeBy(20000); runCurrent()
        assertEquals(2, api.uploaded.size)
    }

    @Test fun explicitRefreshImmediatelyRetriesDurableFailedProgress() = runTest {
        val store = signedStore(); val api = Gateway().apply { uploadError = IOException("offline") }
        val repo = repository(store, api); runCurrent(); repo.setSyncEnabled(true)
        repo.reportProgress(entry, 60000, true, repo.ownerKey); runCurrent()
        assertEquals(1, api.uploaded.size)
        assertEquals(1, repo.state.value.pendingProgressCount)
        api.uploadError = null
        repo.refresh(); runCurrent()
        assertEquals(2, api.uploaded.size)
        assertEquals(0, repo.state.value.pendingProgressCount)
    }

    @Test fun newerProgressQueuedDuringUploadSurvivesOlderAcknowledgement() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent(); repo.setSyncEnabled(true)
        val owner = repo.ownerKey
        api.onUpload = { api.onUpload = null; repo.reportProgress(entry.copy(positionMs = 20000), 60000, false, owner) }
        repo.reportProgress(entry, 60000, true, owner); runCurrent()
        assertEquals(20000L, store.value.accounts.getValue(uid).pending.getValue(card.id).entry.positionMs)
        advanceTimeBy(15000); runCurrent()
        assertEquals(2, api.uploaded.size)
        assertTrue(store.value.accounts.getValue(uid).pending.isEmpty())
    }

    @Test fun uncertainFavoriteIsPersistedBeforeWriteAndNeverAutomaticallyReplayed() = runTest {
        val store = signedStore(); val api = Gateway().apply { writeError = IOException("lost reply") }
        val repo = repository(store, api); runCurrent()
        api.beforeFavoriteWrite = { assertTrue(store.value.accounts.getValue(uid).favoriteAttempts.containsKey(card.id)) }
        repo.setFavorite(card, true, repo.ownerKey); runCurrent()
        assertEquals(1, api.setFavoriteCalls)
        assertTrue(repo.state.value.favoriteUncertainIds.contains(card.id))
        repo.setFavorite(card, true, repo.ownerKey); repo.refresh(); runCurrent()
        assertEquals(1, api.setFavoriteCalls)
        repo.resolveFavorite(card.id); runCurrent()
        assertFalse(repo.state.value.favoriteUncertainIds.contains(card.id))
        assertEquals(1, api.setFavoriteCalls)
    }

    @Test fun favoriteCodeZeroWithoutShelfChangeRemainsUnconfirmed() = runTest {
        val store = signedStore(); val api = Gateway().apply { applyFavorite = false }
        val repo = repository(store, api); runCurrent()
        repo.setFavorite(card, true, repo.ownerKey); runCurrent()
        assertTrue(repo.state.value.favorites.isEmpty())
        assertTrue(card.id in repo.state.value.favoriteUncertainIds)
        assertEquals(1, api.setFavoriteCalls)
    }

    @Test fun oldFavoriteListReadCannotOverrideAConfirmedNewShelf() = runTest {
        val store = signedStore(); val api = Gateway()
        val repo = repository(store, api); runCurrent()
        api.beforeFavoriteReadReturn = {
            api.beforeFavoriteReadReturn = null
            repo.setFavorite(card, true, repo.ownerKey)
            // Finish the newer operation before allowing this captured old response to return.
            runCurrent()
            assertTrue(repo.state.value.favorites.any { it.card.id == card.id })
        }
        repo.refresh(); runCurrent()
        assertEquals(1, api.setFavoriteCalls)
        assertTrue(repo.state.value.favorites.any { it.card.id == card.id })
        assertFalse(card.id in repo.state.value.favoriteUncertainIds)
    }

    @Test fun smsUncertainSendPersistsCooldownAndNeverAutomaticallyResends() = runTest {
        val store = MemoryStore(); val api = Gateway(); val login = Login().apply { sendError = IOException("timeout") }
        val repo = repository(store, api, login); runCurrent()
        repo.sendSms("13800000000"); runCurrent()
        assertEquals(1, login.sends)
        assertTrue(store.value.smsRetryAt > 1700000000000)
        repo.sendSms("13800000000"); runCurrent()
        assertEquals(1, login.sends)
        advanceTimeBy(61000); runCurrent()
        assertEquals(0, repo.state.value.phoneRemainingSeconds)
        assertEquals(1, login.sends)
    }

    @Test fun qrExpiryStopsPollingWithoutAutomaticRefresh() = runTest {
        val store = MemoryStore(); val api = Gateway(); val login = Login()
        val repo = repository(store, api, login); runCurrent()
        repo.startQrLogin(); runCurrent()
        assertNotNull(repo.state.value.qrBytes)
        advanceTimeBy(5000); runCurrent()
        assertNull(repo.state.value.qrBytes)
        assertEquals(1, login.polls)
        advanceTimeBy(30000); runCurrent()
        assertEquals(1, login.polls)
    }
}
