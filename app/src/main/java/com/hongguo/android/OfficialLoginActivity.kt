package com.hongguo.android

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.io.ByteArrayInputStream

/**
 * Private, in-app official login. Manifest MUST assign this Activity to :official_login.
 * No other app component creates a WebView; API 26–27 therefore also have one profile owner.
 * API 28+ uses a separate data directory. Every entry begins with an empty browser profile;
 * every exit clears cookies/storage, destroys the WebView and ends this dedicated process.
 */
class OfficialLoginActivity : ComponentActivity() {
    private var browser: WebView? = null
    private lateinit var status: TextView
    private lateinit var complete: Button
    private lateinit var openLogin: Button
    private lateinit var progress: ProgressBar
    private var closing = false
    private var storageReady = false
    private var handoff: SessionHandoff? = null
    private var instanceGeneration = 0L
    private var activityRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        instanceGeneration = ++processGeneration
        activeActivities++
        activityRegistered = true
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closeLogin(null) }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setResult(RESULT_CANCELED)
        // This must happen before CookieManager/WebView initializes the browser engine.
        if (Build.VERSION.SDK_INT >= 28 && !webProfileConfigured) {
            WebView.setDataDirectorySuffix("official-account-login")
            webProfileConfigured = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(252, 248, 244))
            fitsSystemWindows = true
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bar.addView(Button(this).apply {
            text = "返回"
            contentDescription = "取消官网登录"
            styleLoginButton(this)
            setOnClickListener { closeLogin(null) }
        })
        bar.addView(TextView(this).apply {
            text = "红果官网登录"
            textSize = 17f
            setTextColor(Color.rgb(41, 36, 33))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        complete = Button(this).apply {
            text = "完成登录"
            contentDescription = "完成官网登录并确认账号"
            isEnabled = false
            styleLoginButton(this, prominent = true)
            setOnClickListener { finishWithOfficialSession() }
        }
        bar.addView(complete)
        root.addView(bar)
        status = TextView(this).apply {
            text = "在官网完成登录后，点“完成登录”返回确认。页面可双指放大。"
            textSize = 14f
            setTextColor(Color.rgb(107, 98, 92))
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }
        root.addView(status)
        openLogin = Button(this).apply {
            text = "打开官网登录窗口"
            contentDescription = "打开官网的手机号或扫码登录窗口"
            isEnabled = false
            styleLoginButton(this)
            setOnClickListener { openOfficialLoginDialog() }
        }
        root.addView(openLogin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            setMargins(dp(20), 0, dp(20), dp(12))
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressTintList = ColorStateList.valueOf(Color.rgb(201, 68, 38))
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        val view = WebView(this)
        browser = view
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            saveFormData = false
            safeBrowsingEnabled = true
            userAgentString = com.hongguo.account.protocol.WebLogin.USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        view.isSaveEnabled = false
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val allowed = request != null && isAllowedFrameNavigation(request.url, request.isForMainFrame)
                if (!allowed) showStatus("请在红果官网内完成登录，可返回重试。")
                return !allowed
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val allowed = url != null && isOfficialNavigation(Uri.parse(url))
                if (!allowed) showStatus("请在红果官网内完成登录，可返回重试。")
                return !allowed
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null || request.url.scheme != "https" ||
                    (request.isForMainFrame && !isOfficialNavigation(request.url))) return blockedResponse()
                // HTTPS assets/challenges may use official CDNs. Third-party cookies are disabled;
                // main-frame navigation and credential extraction remain bound to the exact origin.
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!closing && storageReady && url != null && isOfficialNavigation(Uri.parse(url))) {
                    // A desktop User-Agent alone does not override the site's width=device-width
                    // meta tag. Keep its CSS layout above the desktop breakpoint on a phone.
                    view?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                    complete.isEnabled = true
                    openLogin.isEnabled = true
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                showStatus("官网安全连接未成功，请稍后重试。")
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) showStatus("官网暂时无法打开，请返回后重试。")
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) showStatus("官网暂时无法打开，请返回后重试。")
            }

            override fun onFormResubmission(view: WebView?, dontResend: android.os.Message?, resend: android.os.Message?) {
                dontResend?.sendToTarget()
                showStatus("请在页面中重新确认本次操作。")
            }
        }
        view.setDownloadListener { _, _, _, _, _ -> showStatus("请在当前官网页面完成登录。") }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
            removeAllCookies {
                flush()
                WebStorage.getInstance().deleteAllData()
                if (!closing && !isFinishing) {
                    storageReady = true
                    view.clearCache(true)
                    view.clearHistory()
                    view.loadUrl(OFFICIAL_ORIGIN)
                }
            }
        }
    }

    private fun finishWithOfficialSession() {
        val view = browser ?: return
        if (closing || !storageReady || view.url?.let { isOfficialNavigation(Uri.parse(it)) } != true) return
        val cookie = CookieManager.getInstance().getCookie(OFFICIAL_ORIGIN).orEmpty()
        val hasSession = cookie.length in 1..MAX_COOKIE_CHARS && cookie.split(';').any {
            val name = it.substringBefore('=').trim()
            name in setOf("sessionid", "sessionid_ss") && it.substringAfter('=', "").isNotBlank()
        }
        if (!hasSession) {
            showStatus("尚未取得官网登录状态，请先在页面中完成登录。")
            return
        }
        // The main process still verifies the profile. A browser cookie is never UI login success.
        closeLogin(cookie)
    }

    private fun openOfficialLoginDialog() {
        val view = browser ?: return
        if (closing || !storageReady || view.url?.let { isOfficialNavigation(Uri.parse(it)) } != true) return
        // The site's own PC account component listens for this presentation event. It opens the
        // same modal as its header's login button, without filling a field or submitting a form.
        view.evaluateJavascript(OPEN_LOGIN_DIALOG_SCRIPT) {
            mainHandler.postDelayed({
                if (!closing && browser === view) {
                    view.evaluateJavascript("Boolean(document.querySelector('[class*=\"pc-login-dialog-\"]'))") { visible ->
                        showStatus(if (visible == "true") "请在官网弹窗中选择手机号或扫码登录，完成后点“完成登录”。可双指放大。"
                            else "官网尚未打开登录窗口，请稍候后再点一次。")
                    }
                }
            }, 800)
        }
    }

    private fun showStatus(message: String) {
        runOnUiThread { if (!closing) status.text = message }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            browser?.takeIf { !closing && it.url?.let { url -> isOfficialNavigation(Uri.parse(url)) } == true }
                ?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
        }
    }

    private fun closeLogin(cookie: String?) {
        if (closing) return
        closing = true
        complete.isEnabled = false
        openLogin.isEnabled = false
        browser?.stopLoading()
        browser?.settings?.javaScriptEnabled = false
        val manager = CookieManager.getInstance()
        manager.removeAllCookies {
            manager.flush()
            WebStorage.getInstance().deleteAllData()
            browser?.apply {
                clearCache(true)
                clearHistory()
                clearFormData()
            }
            if (cookie != null) {
                val transfer = SessionHandoff(cookie) {
                    scheduleIdleTermination(300)
                }
                handoff = transfer
                // Only an opaque, live Binder handle enters ActivityResult/SavedState, never a cookie.
                val result = handoffIntent(transfer)
                setResult(RESULT_OK, result)
            }
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // No URL, WebView state, cookie, or form field is persisted by the Activity.
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    override fun onDestroy() {
        browser?.let { view ->
            view.stopLoading()
            view.settings.javaScriptEnabled = false
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        }
        browser = null
        if (activityRegistered) {
            activeActivities = (activeActivities - 1).coerceAtLeast(0)
            activityRegistered = false
        }
        if (processGeneration == instanceGeneration && activeActivities == 0) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }
        }
        super.onDestroy()
        // Keep the one-use handoff alive until the parent consumes it, or for at most 30 seconds.
        // No WebView or cookie store remains active while the result waits for the parent lifecycle.
        if (isFinishing && isDedicatedLoginProcess()) {
            val delay = if (handoff == null) 300L else 30_000L
            scheduleIdleTermination(delay, handoff)
        }
    }

    private fun scheduleIdleTermination(delayMillis: Long, transfer: SessionHandoff? = null) {
        val closedGeneration = instanceGeneration
        mainHandler.postDelayed({
            transfer?.discard()
            // A quick reopen or configuration replacement owns a newer generation. Old timers
            // may discard their own handoff but must neither kill nor clear that newer session.
            if (activeActivities == 0 && processGeneration == closedGeneration && isDedicatedLoginProcess()) {
                Process.killProcess(Process.myPid())
            }
        }, delayMillis)
    }

    private fun isDedicatedLoginProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName
        return name == "$packageName:official_login"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun styleLoginButton(button: Button, prominent: Boolean = false) {
        val accent = Color.rgb(201, 68, 38)
        val muted = Color.rgb(107, 98, 92)
        val cream = Color.rgb(245, 237, 229)
        button.isAllCaps = false
        button.textSize = 14f
        button.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        button.minHeight = dp(48)
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(dp(12), dp(4), dp(12), dp(4))
        button.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(cream)
        }
        val states = arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf())
        button.backgroundTintList = ColorStateList(states, intArrayOf(if (prominent) accent else cream, cream))
        button.setTextColor(ColorStateList(states, intArrayOf(if (prominent) Color.WHITE else accent, muted)))
    }

    companion object {
        private const val RESULT_SESSION = "com.hongguo.android.OFFICIAL_LOGIN_SESSION"
        private const val HANDOFF_INTERFACE = "com.hongguo.android.OfficialLoginHandoff"
        private const val OFFICIAL_ORIGIN = "https://hongguoduanju.com/"
        private const val MAX_COOKIE_CHARS = 65536
        // The official responsive hook treats Macintosh + touch points as iPad. A Windows desktop
        // UA keeps Android touch input usable while selecting the site's actual desktop login UI.
        // Observed in the official t.d1c39a18e1.js PC account component on 2026-10-24.
        private const val OPEN_LOGIN_DIALOG_SCRIPT = "window.dispatchEvent(new CustomEvent('hongguo:pc-open-login',{detail:{}}));"
        // Fixed presentation-only script: no account DOM, form, cookie, or native bridge access.
        private const val DESKTOP_VIEWPORT_SCRIPT = """
            (function () {
                var meta = document.querySelector('meta[name="viewport"]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                var scale = Math.max(0.1, Math.min(1, screen.width / 1280));
                meta.content = 'width=1280,initial-scale=' + scale + ',minimum-scale=0.1,maximum-scale=5,user-scalable=yes';
                window.dispatchEvent(new Event('resize'));
            })();
        """
        private var webProfileConfigured = false
        private var processGeneration = 0L
        private var activeActivities = 0

        internal fun consumeCookie(result: Intent?): String? {
            val endpoint = result?.extras?.getBinder(RESULT_SESSION) ?: return null
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            return try {
                request.writeInterfaceToken(HANDOFF_INTERFACE)
                if (!endpoint.transact(IBinder.FIRST_CALL_TRANSACTION, request, response, 0)) return null
                response.readException()
                response.readString()?.takeIf { it.isNotBlank() && it.length <= MAX_COOKIE_CHARS }
            } catch (_: Exception) {
                null // A dead/expired handoff always requires a fresh user login.
            } finally {
                request.recycle()
                response.recycle()
                result?.removeExtra(RESULT_SESSION)
            }
        }

        /** Shared by debug instrumentation probes; does not authenticate or persist any account. */
        internal fun newHandoffResult(cookie: String, consumed: () -> Unit = {}): Intent {
            require(cookie.isNotBlank() && cookie.length <= MAX_COOKIE_CHARS)
            return handoffIntent(SessionHandoff(cookie, consumed))
        }

        private fun handoffIntent(endpoint: IBinder) = Intent().putExtras(Bundle().apply {
            putBinder(RESULT_SESSION, endpoint)
        })

        internal fun isOfficialNavigation(uri: Uri): Boolean =
            uri.scheme == "https" && uri.host == "hongguoduanju.com" &&
                (uri.port == -1 || uri.port == 443) && uri.userInfo == null

        /** A challenge can navigate its HTTPS iframe; only the top document owns login cookies. */
        internal fun isAllowedFrameNavigation(uri: Uri, mainFrame: Boolean): Boolean =
            if (mainFrame) isOfficialNavigation(uri)
            else uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null

        private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private class SessionHandoff(cookie: String, private val consumed: () -> Unit) : Binder() {
        private var value: String? = cookie
        private val expiresAt = SystemClock.elapsedRealtime() + 30_000L

        @Synchronized fun discard() { value = null }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(HANDOFF_INTERFACE)
            if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Private login handoff")
            val cookie = synchronized(this) {
                val found = value.takeIf { SystemClock.elapsedRealtime() <= expiresAt }
                value = null
                found
            }
            reply?.writeNoException()
            reply?.writeString(cookie)
            consumed()
            return true
        }
    }
}
