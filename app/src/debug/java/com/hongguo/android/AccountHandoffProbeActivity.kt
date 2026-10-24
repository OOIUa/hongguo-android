package com.hongguo.android

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.CountDownLatch

/** Main-process host exercises the same ActivityResult boundary as the real Compose screen. */
class AccountHandoffHostActivity : ComponentActivity() {
    val completed = CountDownLatch(1)
    var handoffResult: Intent? = null
        private set
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) handoffResult = it.data
        completed.countDown()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        launcher.launch(Intent(this, AccountHandoffProbeActivity::class.java))
    }
}

/** Synthetic, debug-only cross-process contract fixture; never performs an account request. */
class AccountHandoffProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        setResult(RESULT_OK, OfficialLoginActivity.newHandoffResult("sessionid=synthetic-device-test"))
        finish()
    }
}
