package com.hongguo.account

import com.hongguo.account.protocol.AccountCredentials
import com.hongguo.account.protocol.CloudProfile
import com.hongguo.account.protocol.CloudStats
import com.hongguo.account.protocol.WebsiteProfile
import com.hongguo.android.LibraryEntry

enum class AccountSessionStatus { SIGNED_OUT, RESTORING, NEEDS_VERIFICATION, LOGGED_IN, WEBSITE_ONLY }
enum class AccountSection { HISTORY, FAVORITES, LIKES, RESERVATIONS }
enum class PhoneStep { IDLE, CODE_SENT }

/** Presentation state never contains a cookie, verification code or device credential. */
data class AccountState(
    val profile: CloudProfile? = null,
    /** Website name/avatar while only the website session is verified (WEBSITE_ONLY). */
    val websiteProfile: WebsiteProfile? = null,
    val sessionStatus: AccountSessionStatus = AccountSessionStatus.SIGNED_OUT,
    val busy: Boolean = false,
    val qrBytes: ByteArray? = null,
    val qrExpiresAt: Long = 0,
    val qrRemainingSeconds: Int = 0,
    val phoneStep: PhoneStep = PhoneStep.IDLE,
    val phoneRemainingSeconds: Int = 0,
    val phoneMasked: String = "",
    /** The platform asked for a verification only the official login page can show. */
    val suggestWebsiteLogin: Boolean = false,
    val history: List<LibraryEntry> = emptyList(),
    val favorites: List<LibraryEntry> = emptyList(),
    val likes: List<LibraryEntry> = emptyList(),
    val stats: CloudStats? = null,
    val reservations: List<LibraryEntry> = emptyList(),
    val reservationOnlineIds: Set<String> = emptySet(),
    /** Platform release text of upcoming reservations, such as 预计11月21日上线, keyed by the entry's card id. */
    val reservationReleaseTexts: Map<String, String> = emptyMap(),
    val historyError: String? = null,
    val likesError: String? = null,
    val favoritesError: String? = null,
    val reservationsError: String? = null,
    val syncEnabled: Boolean = false,
    val message: String? = null,
    val selectedSection: AccountSection = AccountSection.HISTORY,
    val favoriteUncertainIds: Set<String> = emptySet(),
    val favoriteBusyIds: Set<String> = emptySet(),
    val reservationBusyIds: Set<String> = emptySet(),
    val pendingProgressCount: Int = 0,
    val lastSyncAt: Long = 0,
    /** Session is stopped in memory, but the private credential file still needs explicit cleanup. */
    val localClearFailed: Boolean = false,
    /** Fixed local stage/status and field names only; never cookies, tokens or raw responses. */
    val loginDiagnostic: String? = null,
)

internal data class PendingProgress(val entry: LibraryEntry, val durationMs: Long, val revision: Long)
internal data class FavoriteAttempt(val add: Boolean, val startedAt: Long)

internal data class AccountCache(
    val history: List<LibraryEntry> = emptyList(),
    val favorites: List<LibraryEntry> = emptyList(),
    val likes: List<LibraryEntry> = emptyList(),
    val reservations: List<LibraryEntry> = emptyList(),
    val reservationOnlineIds: Set<String> = emptySet(),
    val reservationReleaseTexts: Map<String, String> = emptyMap(),
    val pending: Map<String, PendingProgress> = emptyMap(),
    val favoriteAttempts: Map<String, FavoriteAttempt> = emptyMap(),
    val syncEnabled: Boolean = false,
    val lastSyncAt: Long = 0,
)

internal data class AccountDisk(
    val credentials: AccountCredentials? = null,
    val profile: CloudProfile? = null,
    val accounts: Map<String, AccountCache> = emptyMap(),
    val smsRetryAt: Long = 0,
    /** Website verification succeeded; this session has no confirmed App business identity yet. */
    val websiteCredentials: AccountCredentials? = null,
    /** Display-only name/avatar of [websiteCredentials]; never an account identity. */
    val websiteProfile: WebsiteProfile? = null,
) {
    override fun toString() = "AccountDisk([private])"
}

internal interface AccountPersistence {
    fun load(): AccountDisk
    /** Must complete atomically before a successful result is exposed to the UI. */
    fun save(value: AccountDisk)
    /** Emergency session revocation: erase only the encrypted account snapshot, then verify absence. */
    fun erase()
}
