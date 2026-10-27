package com.hongguo.android

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner

/**
 * Marks the first-launch theme picker as answered before any test activity starts, so flows written before
 * 0.7.3 are not covered by the dialog. ThemeOnboardingFlowTest resets the flag for its own launches.
 */
class HongguoTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        app.getSharedPreferences(ThemePreferences.FILE, 0).edit()
            .putBoolean(ThemePreferences.ONBOARDING_DONE, true)
            .putInt(ThemePreferences.ONBOARDING_VERSION, ThemePreferences.CURRENT_ONBOARDING_VERSION).commit()
        super.callApplicationOnCreate(app)
    }
}
