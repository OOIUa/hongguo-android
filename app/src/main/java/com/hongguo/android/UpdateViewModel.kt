package com.hongguo.android

import android.app.Activity
import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class UpdateUiState(
    val visible: Boolean = false,
    val checking: Boolean = false,
    val update: AppUpdate? = null,
    val downloadProgress: Float? = null,
    val downloading: Boolean = false,
    val downloadedFile: File? = null,
    val error: String? = null,
    val status: String = "",
    val installPermissionRequired: Boolean = false,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository()
    private val installer = UpdateInstaller(application)
    private val preferences = application.getSharedPreferences("app_updates", 0)
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    private var checkingJob: Job? = null
    private var downloadJob: Job? = null
    private var installJob: Job? = null
    private var checkVersion = 0
    private var downloadVersion = 0
    private var awaitingPermission = false

    init {
        val elapsed = System.currentTimeMillis() - preferences.getLong("last_attempt", 0)
        if (elapsed < 0 || elapsed >= 24 * 60 * 60 * 1000L) check(false)
    }

    fun checkForUpdates() {
        if (state.value.downloading || installJob?.isActive == true) {
            mutableState.update { it.copy(visible = true) }
            return
        }
        check(true)
    }

    private fun check(manual: Boolean) {
        checkingJob?.cancel()
        val version = ++checkVersion
        mutableState.update { it.copy(visible = manual, checking = true, update = null,
            downloadedFile = null, error = null, status = "正在检查更新…", installPermissionRequired = false) }
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        checkingJob = viewModelScope.launch {
            try {
                val update = repository.check(BuildConfig.UPDATE_MANIFEST_URL, BuildConfig.UPDATE_REPOSITORY)
                if (version != checkVersion) return@launch
                val newer = update.isNewerThan(BuildConfig.VERSION_CODE.toLong())
                val supported = update.minSdk <= Build.VERSION.SDK_INT
                mutableState.update { it.copy(visible = manual || (newer && supported), checking = false,
                    update = update.takeIf { newer && supported },
                    status = when {
                        !newer -> "当前已是最新版本（${BuildConfig.VERSION_NAME}）"
                        !supported -> "新版本暂不支持当前系统"
                        else -> "发现新版本 ${update.versionName}"
                    }, error = if (newer && !supported) "新版本需要更新的 Android 系统。" else null) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (version == checkVersion) mutableState.update { it.copy(visible = manual, checking = false,
                    status = "暂时无法检查更新", error = updateFailure(error)) }
            }
        }
    }

    fun dismiss() {
        if (state.value.downloading || installJob?.isActive == true) return
        ++checkVersion
        checkingJob?.cancel()
        awaitingPermission = false
        mutableState.update { it.copy(visible = false, checking = false) }
    }

    fun downloadUpdate() {
        val update = state.value.update ?: return
        if (state.value.downloading || !update.isNewerThan(BuildConfig.VERSION_CODE.toLong())) return
        val version = ++downloadVersion
        downloadJob?.cancel()
        mutableState.update { it.copy(downloading = true, downloadProgress = 0f, downloadedFile = null,
            error = null, status = "正在下载更新…", installPermissionRequired = false) }
        downloadJob = viewModelScope.launch {
            try {
                val file = installer.download(update) { done, total ->
                    if (version == downloadVersion) mutableState.update { it.copy(downloadProgress =
                        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null) }
                }
                if (version != downloadVersion) return@launch
                mutableState.update { it.copy(downloading = false, downloadProgress = 1f, downloadedFile = file,
                    status = "更新包已校验，点击安装", installPermissionRequired = !installer.hasInstallPermission()) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (version == downloadVersion) mutableState.update { it.copy(downloading = false,
                    downloadProgress = null, status = "更新包下载失败", error = updateFailure(error)) }
            }
        }
    }

    fun cancelDownload() {
        ++downloadVersion
        downloadJob?.cancel()
        mutableState.update { it.copy(downloading = false, downloadProgress = null,
            status = "已取消下载，可稍后重试", error = null) }
    }

    fun openInstaller(activity: Activity) {
        val file = state.value.downloadedFile ?: return
        if (installJob?.isActive == true) return
        if (!installer.hasInstallPermission()) {
            awaitingPermission = true
            mutableState.update { it.copy(installPermissionRequired = true, error = null,
                status = "请允许安装更新，返回后继续安装") }
            try { activity.startActivity(installer.permissionIntent()) }
            catch (_: Exception) {
                awaitingPermission = false
                mutableState.update { it.copy(error = "无法打开安装权限设置，请在系统设置中允许此应用安装更新。") }
            }
            return
        }
        awaitingPermission = false
        mutableState.update { it.copy(installPermissionRequired = false, error = null, status = "正在打开系统安装器…") }
        installJob = viewModelScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { installer.installerIntent(file) }
                activity.startActivity(intent)
                mutableState.update { it.copy(status = "请在系统安装器中确认更新") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutableState.update { it.copy(status = "暂时无法安装更新", error = updateFailure(error)) } }
        }
    }

    fun onResume(activity: Activity) {
        if (awaitingPermission) {
            awaitingPermission = false
            if (installer.hasInstallPermission()) openInstaller(activity)
            else mutableState.update { it.copy(status = "尚未允许安装更新，可再次打开设置", installPermissionRequired = true) }
        }
    }

    override fun onCleared() {
        checkingJob?.cancel()
        downloadJob?.cancel()
        installJob?.cancel()
        super.onCleared()
    }
}

private fun updateFailure(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(6).toList()
    return when {
        causes.any { it is SocketTimeoutException } -> "连接超时，请检查网络后重试。"
        causes.any { it is UnknownHostException || it is SSLException } -> "无法连接更新服务，请检查网络或稍后重试。"
        else -> {
            val message = error.message.orEmpty()
            if (message.isNotBlank() && message.length <= 120 && !message.contains(Regex("https?://|[?&](token|sig|key)=", RegexOption.IGNORE_CASE))) message
            else "操作未完成，请稍后重试。"
        }
    }
}
