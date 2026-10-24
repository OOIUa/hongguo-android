package com.hongguo.account

import android.content.Context
import com.hongguo.account.protocol.AccountCredentials
import com.hongguo.account.protocol.AccountProtocolException
import com.hongguo.account.protocol.CloudAccountClient
import com.hongguo.account.protocol.CloudFavorite
import com.hongguo.account.protocol.CloudProfile
import com.hongguo.account.protocol.CloudProgress
import com.hongguo.account.protocol.CloudReservation
import com.hongguo.account.protocol.PhoneLogin
import com.hongguo.account.protocol.Series
import com.hongguo.account.protocol.WebLogin
import com.hongguo.android.DramaCard
import com.hongguo.android.LibraryCodec
import com.hongguo.android.LibraryEntry
import com.hongguo.android.LibrarySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A small injectable boundary keeps auth, persistence and account-switch tests off the network. */
internal interface AccountGateway {
    fun prepareCredentials(credentials: AccountCredentials): AccountCredentials
    fun profile(credentials: AccountCredentials): CloudProfile
    fun history(credentials: AccountCredentials): List<CloudProgress>
    fun favorites(credentials: AccountCredentials): List<CloudFavorite>
    fun reservations(credentials: AccountCredentials, online: Boolean): List<CloudReservation>
    fun setFavorite(credentials: AccountCredentials, id: String, add: Boolean)
    fun upload(credentials: AccountCredentials, progress: CloudProgress, episodeCount: Int)
    fun cancelAll()
}

internal interface AccountLogin {
    fun qr(): WebLogin.Qr
    fun poll(): Boolean
    fun credentials(): AccountCredentials
    fun send(number: String): PhoneLogin.Sent
    fun verify(code: String): AccountCredentials
    fun cancel()
}

private fun accountHttp() = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

private class LiveAccountGateway : AccountGateway {
    private val client = CloudAccountClient(accountHttp())
    override fun prepareCredentials(credentials: AccountCredentials) = client.prepareCredentials(credentials)
    override fun profile(credentials: AccountCredentials) = client.profile(credentials)
    override fun history(credentials: AccountCredentials) = client.history(credentials)
    override fun favorites(credentials: AccountCredentials) = client.favorites(credentials)
    override fun reservations(credentials: AccountCredentials, online: Boolean) = client.reservations(credentials, online)
    override fun setFavorite(credentials: AccountCredentials, id: String, add: Boolean) = client.setFavorite(credentials, id, add)
    override fun upload(credentials: AccountCredentials, progress: CloudProgress, episodeCount: Int) = client.upload(credentials, progress, episodeCount)
    override fun cancelAll() = client.cancelAll()
}

private class LiveAccountLogin : AccountLogin {
    private val login = WebLogin(accountHttp())
    override fun qr() = login.qr()
    override fun poll() = login.poll()
    override fun credentials() = login.credentials()
    override fun send(number: String) = login.send(number)
    override fun verify(code: String) = login.verify(code)
    override fun cancel() = login.cancel()
}

/** Application-owned session. Every async result is fenced by the active session generation. */
class AccountRepository internal constructor(
    private val persistence: AccountPersistence,
    private val gateway: AccountGateway,
    private val loginFactory: () -> AccountLogin,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(AccountStore(context), LiveAccountGateway(), { LiveAccountLogin() },
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))

    private val lock = Any()
    private val mutableState = MutableStateFlow(AccountState(sessionStatus = AccountSessionStatus.RESTORING, busy = true))
    val state: StateFlow<AccountState> = mutableState.asStateFlow()
    private var disk = AccountDisk()
    private var generation = 0L
    private var ownerGeneration = 0L
    private val observationSequence = AtomicLong()
    private val lastObserved = mutableMapOf<String, PendingProgress>()
    private var forceProgressThroughSequence = -1L
    private var favoriteRevision = 0L
    private var activeLogin: AccountLogin? = null
    private var loginJob: Job? = null
    private var refreshJob: Job? = null
    private var progressJob: Job? = null
    private var progressWriting = false
    private var progressTaskId = 0L
    private var countdownJob: Job? = null
    private val favoriteJobs = mutableMapOf<String, Job>()
    private val lastUploaded = mutableMapOf<String, PendingProgress>()
    private var lastProgressUploadAt = 0L

    val ownerKey: String? get() = synchronized(lock) {
        disk.profile?.id?.takeIf { mutableState.value.sessionStatus == AccountSessionStatus.LOGGED_IN }?.let { "$it:$ownerGeneration" }
    }

    init {
        scope.launch(io) {
            try {
                val loaded = runInterruptible(io) { persistence.load() }
                synchronized(lock) {
                    // A user-triggered login/logout during disk IO owns the newer state.
                    if (generation != 0L) return@launch
                    disk = loaded
                    val profile = loaded.profile
                    mutableState.value = AccountState(profile = profile,
                        sessionStatus = if (profile == null) AccountSessionStatus.SIGNED_OUT else AccountSessionStatus.RESTORING)
                    showCacheLocked()
                    updatePhoneCountdownLocked()
                }
                startCountdown()
                if (loaded.profile != null) restore()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { synchronized(lock) { if (generation == 0L) mutableState.value = AccountState(message = "本机账号资料无法读取，请退出账号后重新登录") } }
        }
    }

    private fun state(change: (AccountState) -> AccountState) = synchronized(lock) { mutableState.value = change(mutableState.value) }
    private fun current(g: Long) = synchronized(lock) { generation == g }
    private fun cacheLocked() = disk.profile?.id?.let { disk.accounts[it] } ?: AccountCache()
    private fun showCacheLocked() {
        val c = cacheLocked()
        mutableState.value = mutableState.value.copy(profile = disk.profile, history = c.history, favorites = c.favorites,
            reservations = c.reservations, reservationOnlineIds = c.reservationOnlineIds, syncEnabled = c.syncEnabled,
            favoriteUncertainIds = c.favoriteAttempts.keys, pendingProgressCount = c.pending.size, lastSyncAt = c.lastSyncAt)
    }
    private fun saveLocked(next: AccountDisk): Boolean = try {
        persistence.save(next)
        disk = next
        true
    } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(message = "账号资料保存失败，请检查本机空间后重试")
        false
    }
    private fun cacheChange(g: Long, transform: (AccountCache) -> AccountCache): Boolean = synchronized(lock) {
        if (generation != g) return@synchronized false
        val uid = disk.profile?.id ?: return@synchronized false
        val next = transform(cacheLocked())
        if (!saveLocked(disk.copy(accounts = disk.accounts + (uid to next)))) return@synchronized false
        showCacheLocked()
        true
    }

    private fun resetRequestsLocked() {
        loginJob?.cancel(); refreshJob?.cancel(); progressJob?.cancel()
        progressJob = null; progressWriting = false; progressTaskId++
        favoriteRevision++
        favoriteJobs.values.forEach { it.cancel() }; favoriteJobs.clear()
        activeLogin?.cancel(); activeLogin = null
        gateway.cancelAll()
        mutableState.value = mutableState.value.copy(busy = false, qrBytes = null, qrExpiresAt = 0, qrRemainingSeconds = 0,
            phoneStep = PhoneStep.IDLE, phoneMasked = "", favoriteBusyIds = emptySet())
    }

    /** A canceled scan, verification or old player can never restore a superseded account. */
    fun cancelLogin() = synchronized(lock) {
        // Leaving the account screen after success must not invalidate an active player's owner.
        if (activeLogin == null) return@synchronized
        generation++
        resetRequestsLocked()
    }

    private fun beginLogin(): Pair<Long, AccountLogin> = synchronized(lock) {
        generation++
        resetRequestsLocked()
        val login = loginFactory()
        activeLogin = login
        mutableState.value = mutableState.value.copy(message = null, busy = true)
        generation to login
    }

    fun startQrLogin() {
        val (g, login) = beginLogin()
        loginJob = scope.launch(io) {
            try {
                val qr = runInterruptible(io) { login.qr() }
                val expires = now() + qr.expiresIn.coerceIn(0, 600) * 1000L
                if (!current(g)) return@launch
                state { it.copy(busy = false, qrBytes = qr.image, qrExpiresAt = expires, qrRemainingSeconds = qr.expiresIn.coerceAtLeast(0)) }
                while (isActive && current(g) && now() < expires) {
                    delay(2_000)
                    if (!current(g) || now() >= expires) break
                    state { it.copy(qrRemainingSeconds = ((expires - now() + 999) / 1000).coerceAtLeast(0).toInt()) }
                    if (runInterruptible(io) { login.poll() }) {
                        val credentials = runInterruptible(io) { login.credentials() }
                        finishLogin(g, credentials)
                        return@launch
                    }
                }
                if (current(g)) state { it.copy(qrBytes = null, qrRemainingSeconds = 0, message = "二维码已过期，请手动重新获取") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(g)) state { it.copy(busy = false, qrBytes = null, message = "扫码登录未完成，可重试或在官网完成验证") } }
        }
    }

    fun sendSms(phone: String) {
        val number = phone.trim()
        if (!number.matches(Regex("(?:\\+86[ -]?)?1[3-9][0-9]{9}"))) {
            state { it.copy(message = "请输入有效的中国大陆手机号") }; return
        }
        synchronized(lock) {
            if (mutableState.value.busy || now() < disk.smsRetryAt) {
                updatePhoneCountdownLocked(); return
            }
            // Guard is persisted BEFORE the request, including uncertain delivery and process restarts.
            if (!saveLocked(disk.copy(smsRetryAt = now() + 60_000))) return
            updatePhoneCountdownLocked()
        }
        val (g, login) = beginLogin()
        startCountdown()
        loginJob = scope.launch(io) {
            try {
                val sent = runInterruptible(io) { login.send(number) }
                if (!current(g)) return@launch
                synchronized(lock) {
                    if (generation != g) return@synchronized
                    saveLocked(disk.copy(smsRetryAt = maxOf(disk.smsRetryAt, now() + sent.retrySeconds.coerceIn(1, 3600) * 1000L)))
                    updatePhoneCountdownLocked()
                    mutableState.value = mutableState.value.copy(busy = false, phoneStep = PhoneStep.CODE_SENT,
                        phoneMasked = sent.maskedMobile, message = "验证码已发送，请在本机输入；不会自动重发")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(g)) state { it.copy(busy = false, message = "验证码发送未获确认，请等待后手动重试，或在官网完成验证") } }
        }
    }

    fun verifySms(code: String) {
        val value = code.trim()
        if (!value.matches(Regex("[0-9]{4,8}"))) { state { it.copy(message = "请输入短信中的验证码") }; return }
        val pair = synchronized(lock) {
            if (mutableState.value.busy || mutableState.value.phoneStep != PhoneStep.CODE_SENT) return
            val login = activeLogin ?: return
            mutableState.value = mutableState.value.copy(busy = true, phoneStep = PhoneStep.IDLE, message = null)
            generation to login
        }
        loginJob = scope.launch(io) {
            try { finishLogin(pair.first, runInterruptible(io) { pair.second.verify(value) }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current(pair.first)) state { it.copy(busy = false, message = "本次验证码验证未完成，请在官网完成验证，或等待后手动获取新验证码") }
            }
        }
    }

    fun importCookie(cookie: String) = completeWebLogin(cookie)

    /** Called only with cookies captured from the official HTTPS website or entered by the user. */
    fun completeWebLogin(cookie: String) {
        val clean = cookie.trim()
        if (clean.length !in 1..65_536 || clean.any { it == '\r' || it == '\n' || it == '\u0000' } ||
            clean.split(';').none { part -> part.substringBefore('=').trim() in setOf("sessionid", "sessionid_ss") && part.substringAfter('=', "").trim().isNotBlank() }) {
            state { it.copy(message = "未取得完整的官网登录态，请重新登录") }; return
        }
        val (g, _) = beginLogin()
        loginJob = scope.launch(io) {
            try { finishLogin(g, AccountCredentials(clean)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(g)) state { it.copy(busy = false, message = "登录态未通过红果账号验证，请重新登录或检查网络") } }
        }
    }

    private suspend fun finishLogin(g: Long, web: AccountCredentials) {
        if (!current(g)) return
        val credentials = runInterruptible(io) { gateway.prepareCredentials(web) }
        if (!current(g)) return
        val profile = runInterruptible(io) { gateway.profile(credentials) }
        if (!validAccountId(profile.id)) throw IOException("账号身份无效")
        synchronized(lock) {
            if (generation != g) return
            if (!saveLocked(disk.copy(credentials = credentials, profile = profile))) {
                mutableState.value = mutableState.value.copy(busy = false); return
            }
            ownerGeneration++
            lastUploaded.clear(); lastObserved.clear(); lastProgressUploadAt = 0
            activeLogin?.cancel(); activeLogin = null
            mutableState.value = mutableState.value.copy(profile = profile, sessionStatus = AccountSessionStatus.LOGGED_IN,
                busy = false, qrBytes = null, qrExpiresAt = 0, qrRemainingSeconds = 0, phoneStep = PhoneStep.IDLE,
                historyError = null, favoritesError = null, reservationsError = null, message = "已登录红果账号")
            showCacheLocked()
        }
        refresh()
    }

    private fun restore() {
        val g = synchronized(lock) { generation }
        refreshJob?.cancel()
        refreshJob = scope.launch(io) {
            val credentials = synchronized(lock) { disk.credentials } ?: return@launch
            try {
                val profile = runInterruptible(io) { gateway.profile(credentials) }
                synchronized(lock) {
                    if (generation != g) return@launch
                    if (!validAccountId(profile.id) || profile.id != disk.profile?.id) throw AccountProtocolException("账号身份已变化", requiresLogin = true)
                    if (!saveLocked(disk.copy(profile = profile))) return@launch
                    mutableState.value = mutableState.value.copy(sessionStatus = AccountSessionStatus.LOGGED_IN, profile = profile, message = null)
                }
                refreshLists(g, credentials)
                scheduleProgress(false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!current(g)) return@launch
                if (requiresLogin(error)) invalidate(g)
                else state { it.copy(sessionStatus = AccountSessionStatus.NEEDS_VERIFICATION, busy = false,
                    message = "网络验证未完成，已保留本机登录态与片单；恢复网络后点击重试") }
            }
        }
    }

    fun refresh() {
        if (mutableState.value.sessionStatus in setOf(AccountSessionStatus.NEEDS_VERIFICATION, AccountSessionStatus.RESTORING)) { restore(); return }
        val pair = synchronized(lock) {
            if (mutableState.value.sessionStatus != AccountSessionStatus.LOGGED_IN) return
            generation to (disk.credentials ?: return)
        }
        refreshJob?.cancel()
        refreshJob = scope.launch(io) {
            refreshLists(pair.first, pair.second)
            if (current(pair.first)) scheduleProgress(true)
        }
    }

    private suspend fun refreshLists(g: Long, credentials: AccountCredentials) {
        if (!current(g)) return
        state { it.copy(busy = true) }
        var anySuccess = false
        for (section in AccountSection.entries) {
            if (!current(g)) return
            try {
                when (section) {
                    AccountSection.HISTORY -> {
                        val rows = runInterruptible(io) { gateway.history(credentials) }.map(::historyEntry)
                        cacheChange(g) { cache ->
                            // Preserve a newer local observation and any queued update while reads are in flight.
                            val merged = (cache.history + rows).groupBy { it.card.id }.values.map { items -> items.maxBy { it.updatedAt } }
                            cache.copy(history = normalizeHistory(merged))
                        }
                        if (current(g)) state { it.copy(historyError = null) }
                    }
                    AccountSection.FAVORITES -> {
                        val readRevision = synchronized(lock) { favoriteRevision }
                        val rows = runInterruptible(io) { gateway.favorites(credentials) }.map(::favoriteEntry)
                        synchronized(lock) {
                            // A list read started before/during a confirmed write must not restore the old shelf.
                            if (generation == g && favoriteRevision == readRevision && mutableState.value.favoriteBusyIds.isEmpty()) {
                                cacheChange(g) { it.copy(favorites = normalizeFavorites(rows)) }
                            }
                        }
                        if (current(g)) state { it.copy(favoritesError = null) }
                    }
                    AccountSection.RESERVATIONS -> {
                        val upcoming = runInterruptible(io) { gateway.reservations(credentials, false) }
                        val online = runInterruptible(io) { gateway.reservations(credentials, true) }
                        cacheChange(g) { it.copy(reservations = (upcoming + online).distinctBy { row -> row.series.id }.map(::reservationEntry),
                            reservationOnlineIds = online.map { row -> row.series.id }.toSet()) }
                        if (current(g)) state { it.copy(reservationsError = null) }
                    }
                }
                anySuccess = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!current(g)) return
                if (requiresLogin(error)) { invalidate(g); return }
                state { current -> when (section) {
                    AccountSection.HISTORY -> current.copy(historyError = "历史读取失败，已保留上次记录")
                    AccountSection.FAVORITES -> current.copy(favoritesError = "收藏读取失败，已保留上次记录")
                    AccountSection.RESERVATIONS -> current.copy(reservationsError = "预约读取失败，已保留上次记录")
                } }
            }
        }
        if (current(g)) {
            if (anySuccess) cacheChange(g) { it.copy(lastSyncAt = now()) }
            state { it.copy(busy = false) }
        }
    }

    fun selectSection(section: AccountSection) = state { it.copy(selectedSection = section) }
    fun consumeMessage() = state { it.copy(message = null) }

    fun setSyncEnabled(enabled: Boolean) {
        val g = synchronized(lock) {
            if (mutableState.value.sessionStatus != AccountSessionStatus.LOGGED_IN) return
            if (cacheLocked().syncEnabled == enabled) return
            generation
        }
        // Enabling starts with future first-frame observations only, never an import of guest history.
        if (!cacheChange(g) { it.copy(syncEnabled = enabled, pending = emptyMap()) }) return
        progressJob?.cancel(); progressJob = null
        lastUploaded.clear(); lastObserved.clear()
        state { it.copy(message = if (enabled) "已开启：只上报此后实际播放的进度，本机游客记录不会上传" else "已关闭播放进度上报，未发送的进度已移除") }
    }

    fun setFavorite(card: DramaCard, add: Boolean, expectedOwner: String? = ownerKey) {
        val g: Long
        val credentials: AccountCredentials
        synchronized(lock) {
            if (expectedOwner == null || expectedOwner != ownerKey) { state { it.copy(message = "账号已变化，请重新确认收藏操作") }; return }
            if (!validAccountId(card.id) || mutableState.value.favoriteBusyIds.isNotEmpty()) return
            if (card.id in cacheLocked().favoriteAttempts) { state { it.copy(message = "此收藏操作结果待核对，请先读取云端状态") }; return }
            g = generation; credentials = disk.credentials ?: return
            favoriteRevision++
            mutableState.value = mutableState.value.copy(favoriteBusyIds = mutableState.value.favoriteBusyIds + card.id)
        }
        favoriteJobs[card.id] = scope.launch(io) {
            var submitted = false
            try {
                val before = runInterruptible(io) { gateway.favorites(credentials) }.map(::favoriteEntry)
                if (!current(g)) return@launch
                if (!cacheChange(g) { it.copy(favorites = normalizeFavorites(before)) }) return@launch
                if (before.any { it.card.id == card.id } == add) {
                    state { it.copy(message = if (add) "云端已收藏" else "云端已取消收藏") }; return@launch
                }
                // Persist uncertainty before dispatch. Process death/cancel must not replay this write.
                if (!cacheChange(g) { it.copy(favoriteAttempts = it.favoriteAttempts + (card.id to FavoriteAttempt(add, now()))) }) return@launch
                submitted = true
                runInterruptible(io) { gateway.setFavorite(credentials, card.id, add) }
                if (!current(g)) return@launch
                val after = runInterruptible(io) { gateway.favorites(credentials) }.map(::favoriteEntry)
                if (!current(g)) return@launch
                if (after.any { it.card.id == card.id } == add) {
                    cacheChange(g) { it.copy(favorites = normalizeFavorites(after), favoriteAttempts = it.favoriteAttempts - card.id) }
                    state { it.copy(message = if (add) "已确认云端收藏成功" else "已确认云端取消收藏") }
                } else {
                    cacheChange(g) { it.copy(favorites = normalizeFavorites(after)) }
                    state { it.copy(message = "云端未确认本次收藏变化，请核对后再决定；不会自动重试") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current(g)) {
                    if (requiresLogin(error)) invalidate(g)
                    else state { it.copy(message = if (submitted) "收藏请求结果不确定，请先核对云端状态；不会自动重试" else "无法读取云端收藏，本次未提交修改") }
                }
            } finally {
                synchronized(lock) {
                    if (generation == g) {
                        favoriteRevision++
                        mutableState.value = mutableState.value.copy(favoriteBusyIds = mutableState.value.favoriteBusyIds - card.id)
                        favoriteJobs.remove(card.id)
                    }
                }
            }
        }
    }

    /** Read only. Clearing the guard permits a new explicit user action, never an automatic replay. */
    fun resolveFavorite(seriesId: String) {
        val pair = synchronized(lock) {
            if (ownerKey == null || mutableState.value.favoriteBusyIds.isNotEmpty()) return
            favoriteRevision++
            mutableState.value = mutableState.value.copy(favoriteBusyIds = mutableState.value.favoriteBusyIds + seriesId)
            generation to (disk.credentials ?: return)
        }
        favoriteJobs[seriesId] = scope.launch(io) {
            try {
                val rows = runInterruptible(io) { gateway.favorites(pair.second) }.map(::favoriteEntry)
                if (!current(pair.first)) return@launch
                val added = rows.any { it.card.id == seriesId }
                cacheChange(pair.first) { it.copy(favorites = normalizeFavorites(rows), favoriteAttempts = it.favoriteAttempts - seriesId) }
                state { it.copy(message = if (added) "已核对：云端当前已收藏" else "已核对：云端当前未收藏。需要修改时请重新确认") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (current(pair.first)) {
                    if (requiresLogin(error)) invalidate(pair.first)
                    else state { it.copy(message = "云端收藏仍未能核对，已保留待确认状态") }
                }
            } finally {
                synchronized(lock) {
                    if (generation == pair.first) {
                        favoriteRevision++
                        mutableState.value = mutableState.value.copy(favoriteBusyIds = mutableState.value.favoriteBusyIds - seriesId)
                        favoriteJobs.remove(seriesId)
                    }
                }
            }
        }
    }

    /** Caller must have rendered a first frame for this exact playback/owner. Indices are 1-based. */
    fun reportProgress(entry: LibraryEntry, durationMs: Long, force: Boolean = false, ownerKey: String? = this.ownerKey) {
        if (ownerKey == null) return
        // This method runs every two seconds on the player's Main thread. Never take the disk
        // transaction lock or perform Keystore/encryption/fsync on that calling thread.
        val sequence = observationSequence.incrementAndGet()
        val observedAt = now()
        scope.launch(io) {
            var dispatch = false
            var expedite = force
            synchronized(lock) {
                if (ownerKey != this@AccountRepository.ownerKey || !validAccountId(entry.card.id) || !validAccountId(entry.episodeId) || entry.episodeIndex < 1) return@launch
                val cache = cacheLocked()
                if (!cache.syncEnabled) return@launch
                val seen = lastObserved[entry.card.id]
                if (seen != null && seen.revision >= sequence) return@launch
                val duration = durationMs.coerceAtLeast(0)
                val clean = entry.copy(positionMs = entry.positionMs.coerceAtLeast(0), updatedAt = observedAt)
                val pending = PendingProgress(clean, duration, sequence)
                lastObserved[entry.card.id] = pending
                expedite = force || sequence <= forceProgressThroughSequence
                val previous = cache.pending[entry.card.id] ?: lastUploaded[entry.card.id]
                if (previous != null && sameObservation(previous, clean, duration)) {
                    dispatch = expedite && cache.pending.isNotEmpty()
                } else {
                    dispatch = cacheChange(generation) { it.copy(
                        pending = (it.pending + (entry.card.id to pending)).entries.toList().takeLast(200).associate { row -> row.toPair() },
                        history = normalizeHistory(listOf(clean) + it.history.filterNot { row -> row.card.id == clean.card.id })) }
                }
            }
            if (dispatch) scheduleProgress(expedite)
        }
    }

    fun flushProgress(ownerKey: String?) {
        if (ownerKey == null) return
        val through = observationSequence.get()
        scope.launch(io) {
            synchronized(lock) {
                if (ownerKey != this@AccountRepository.ownerKey) return@launch
                // A pause can overtake a queued IO persistence task. That task inherits force.
                forceProgressThroughSequence = maxOf(forceProgressThroughSequence, through)
            }
            scheduleProgress(true)
        }
    }

    private fun scheduleProgress(force: Boolean) {
        synchronized(lock) {
            if (ownerKey == null || !cacheLocked().syncEnabled || cacheLocked().pending.isEmpty()) return
            if (progressJob?.isActive == true) {
                // Pause/finish can expedite a queued timer, but never replay a submitted write.
                if (!force || progressWriting) return
                progressTaskId++
                progressJob?.cancel()
                progressJob = null
            }
            val g = generation
            val credentials = disk.credentials ?: return
            val wait = if (force) 0 else (15_000 - (now() - lastProgressUploadAt)).coerceAtLeast(0)
            val taskId = ++progressTaskId
            progressWriting = false
            progressJob = scope.launch(io) {
                var failed = false
                try {
                    if (wait > 0) delay(wait)
                    val batch = synchronized(lock) {
                        if (generation != g || progressTaskId != taskId || !cacheLocked().syncEnabled) emptyList()
                        else {
                            progressWriting = true
                            cacheLocked().pending.values.toList()
                        }
                    }
                    for (pending in batch) {
                        if (!current(g)) return@launch
                        synchronized(lock) { lastProgressUploadAt = now() }
                        runInterruptible(io) { gateway.upload(credentials, cloudProgress(pending), pending.entry.card.episodeCount) }
                        if (!current(g)) return@launch
                        synchronized(lock) {
                            lastUploaded[pending.entry.card.id] = pending
                            lastProgressUploadAt = now()
                        }
                        cacheChange(g) { cache -> if (cache.pending[pending.entry.card.id]?.revision == pending.revision)
                            cache.copy(pending = cache.pending - pending.entry.card.id) else cache }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    failed = true
                    if (current(g)) {
                        if (requiresLogin(error)) invalidate(g)
                        else state { it.copy(message = "播放进度未能上报，已保留待同步记录") }
                    }
                } finally {
                    synchronized(lock) {
                        if (generation == g && progressTaskId == taskId) {
                            progressJob = null; progressWriting = false
                            // Retry only on later playback/explicit refresh, avoiding a network failure loop.
                            if (!failed && cacheLocked().pending.isNotEmpty()) scope.launch(io) { delay(15_000); scheduleProgress(false) }
                        }
                    }
                }
            }
        }
    }

    private enum class SessionClear { SAVED, ERASED, FAILED }

    /** A failed token-free rewrite must never silently leave a restorable signed-in snapshot. */
    private fun clearSessionLocked(next: AccountDisk): SessionClear {
        if (saveLocked(next)) return SessionClear.SAVED
        return try {
            persistence.erase()
            // The fallback erased account caches as well; never claim they survived this failure.
            disk = AccountDisk(smsRetryAt = next.smsRetryAt)
            SessionClear.ERASED
        } catch (_: Exception) {
            // Keep all runtime account use stopped, and retain an explicit recovery flag for the UI.
            disk = next
            SessionClear.FAILED
        }
    }

    fun logout() = synchronized(lock) {
        generation++
        ownerGeneration++
        resetRequestsLocked()
        val uid = disk.profile?.id
        val accounts = if (uid == null) disk.accounts else disk.accounts + (uid to cacheLocked().copy(pending = emptyMap()))
        val next = disk.copy(credentials = null, profile = null, accounts = accounts)
        val cleared = clearSessionLocked(next)
        lastUploaded.clear(); lastObserved.clear(); lastProgressUploadAt = 0
        mutableState.value = AccountState(phoneRemainingSeconds = ((disk.smsRetryAt - now() + 999) / 1000).coerceAtLeast(0).toInt(),
            localClearFailed = cleared == SessionClear.FAILED,
            message = when (cleared) {
                SessionClear.SAVED -> "已退出账号，本机游客记录和账号片单保留"
                SessionClear.ERASED -> "已退出账号。账号资料保存失败，已清除加密登录文件及账号缓存；本机游客记录保留"
                SessionClear.FAILED -> "已停止账号会话，但本机清除保存失败：原登录文件仍可能存在。请检查存储权限或空间后再次清除，重新启动可能恢复旧登录态"
            })
    }

    private fun invalidate(g: Long) = synchronized(lock) {
        if (generation != g) return@synchronized
        generation++
        ownerGeneration++
        resetRequestsLocked()
        val uid = disk.profile?.id
        val accounts = if (uid == null) disk.accounts else disk.accounts + (uid to cacheLocked().copy(pending = emptyMap(), syncEnabled = false))
        val next = disk.copy(credentials = null, profile = null, accounts = accounts)
        val cleared = clearSessionLocked(next)
        lastUploaded.clear(); lastObserved.clear()
        mutableState.value = AccountState(localClearFailed = cleared == SessionClear.FAILED, message = when (cleared) {
            SessionClear.SAVED -> "红果登录态已失效，已停止同步，请重新登录"
            SessionClear.ERASED -> "红果登录态已失效，已清除加密登录文件及账号缓存；本机游客记录保留，请重新登录"
            SessionClear.FAILED -> "红果登录态已失效，已停止同步，但本机清除保存失败。原登录文件仍可能存在，请检查存储权限或空间后再次清除"
        })
    }

    private fun updatePhoneCountdownLocked() {
        mutableState.value = mutableState.value.copy(phoneRemainingSeconds = ((disk.smsRetryAt - now() + 999) / 1000).coerceIn(0, 3600).toInt())
    }
    private fun startCountdown() {
        if (countdownJob?.isActive == true) return
        countdownJob = scope.launch(io) {
            while (isActive) {
                synchronized(lock) { updatePhoneCountdownLocked() }
                if (mutableState.value.phoneRemainingSeconds == 0) break
                delay(1_000)
            }
        }
    }

    private fun requiresLogin(error: Exception) = error is AccountProtocolException && error.requiresLogin

    internal companion object {
        fun card(series: Series, count: Int = 0) = DramaCard(series.id, series.title, series.cover,
            if (count > 0) count else Regex("[0-9]+").find(series.badge)?.value?.toIntOrNull() ?: 0, series.description)
        fun historyEntry(progress: CloudProgress) = LibraryEntry(card(progress.series, progress.episodeCount), progress.episodeId,
            progress.episodeIndex.coerceAtLeast(0) + 1, progress.position.coerceAtLeast(0), progress.updatedAt.coerceAtLeast(0))
        fun favoriteEntry(favorite: CloudFavorite) = LibraryEntry(card(favorite.series), updatedAt = favorite.collectedAt.coerceAtLeast(0))
        fun reservationEntry(reservation: CloudReservation) = LibraryEntry(card(reservation.series), updatedAt = reservation.publishAt.coerceAtLeast(0))
        fun cloudProgress(pending: PendingProgress) = pending.entry.let { entry -> CloudProgress(
            Series(entry.card.id, entry.card.title, entry.card.cover, entry.card.summary, "全 ${entry.card.episodeCount} 集"),
            entry.episodeId, entry.episodeIndex - 1, entry.positionMs, pending.durationMs, entry.updatedAt, entry.card.episodeCount) }
        private fun normalizeHistory(rows: List<LibraryEntry>) = LibraryCodec.normalize(LibrarySnapshot(history = rows)).history
        private fun normalizeFavorites(rows: List<LibraryEntry>) = LibraryCodec.normalize(LibrarySnapshot(favorites = rows)).favorites
        private fun sameObservation(previous: PendingProgress, entry: LibraryEntry, duration: Long) = previous.entry.episodeId == entry.episodeId &&
            previous.entry.episodeIndex == entry.episodeIndex && previous.entry.positionMs == entry.positionMs && previous.durationMs == duration
    }
}
