package com.hongguo.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.hongguo.android.DramaCard
import com.hongguo.android.HongguoApplication
import com.hongguo.android.LibraryEntry

/** UI recreation does not create a new login session or resend a verification code. */
class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as HongguoApplication).accountRepository
    val state = repository.state
    val ownerKey: String? get() = repository.ownerKey
    fun startQrLogin() = repository.startQrLogin()
    fun cancelLogin() = repository.cancelLogin()
    fun sendSms(phone: String) = repository.sendSms(phone)
    fun verifySms(code: String) = repository.verifySms(code)
    fun importCookie(cookie: String) = repository.importCookie(cookie)
    fun completeWebLogin(cookie: String) = repository.completeWebLogin(cookie)
    fun logout() = repository.logout()
    fun refresh() = repository.refresh()
    fun setSyncEnabled(enabled: Boolean) = repository.setSyncEnabled(enabled)
    fun setFavorite(card: DramaCard, add: Boolean, expectedOwner: String? = ownerKey) = repository.setFavorite(card, add, expectedOwner)
    fun resolveFavorite(seriesId: String) = repository.resolveFavorite(seriesId)
    fun selectSection(section: AccountSection) = repository.selectSection(section)
    fun consumeMessage() = repository.consumeMessage()
    fun reportProgress(entry: LibraryEntry, durationMs: Long, force: Boolean = false, ownerKey: String? = this.ownerKey) =
        repository.reportProgress(entry, durationMs, force, ownerKey)
    fun flushProgress(ownerKey: String?) = repository.flushProgress(ownerKey)
}
