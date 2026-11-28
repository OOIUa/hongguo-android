package com.hongguo.tv

import com.hongguo.android.PlaybackSpeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvModeTest {
    private val phone = DeviceCapabilities(
        uiModeTelevision = false,
        hasLeanback = false,
        hasTelevisionFeature = false,
        hasTouchscreen = true,
        hasPcFeature = false,
    )
    private val androidTv = DeviceCapabilities(
        uiModeTelevision = true,
        hasLeanback = true,
        hasTelevisionFeature = true,
        hasTouchscreen = false,
        hasPcFeature = false,
    )
    private val domesticBoxNoLeanback = DeviceCapabilities(
        uiModeTelevision = false,
        hasLeanback = false,
        hasTelevisionFeature = false,
        hasTouchscreen = false,
        hasPcFeature = false,
    )
    private val desktopPc = DeviceCapabilities(
        uiModeTelevision = false,
        hasLeanback = false,
        hasTelevisionFeature = false,
        hasTouchscreen = false,
        hasPcFeature = true,
    )

    @Test fun displayModeKeyRoundTripsAndDefaultsToAuto() {
        assertEquals(DisplayMode.AUTO, DisplayMode.fromKey(null))
        assertEquals(DisplayMode.AUTO, DisplayMode.fromKey("unknown"))
        assertEquals(DisplayMode.AUTO, DisplayMode.fromKey("auto"))
        assertEquals(DisplayMode.PHONE, DisplayMode.fromKey("phone"))
        assertEquals(DisplayMode.TV, DisplayMode.fromKey("tv"))
    }

    @Test fun televisionDetectionCoversAndroidTvAndDomesticBoxesWithoutFlaggingPhonesOrPc() {
        assertFalse(phone.isTelevision)
        assertFalse(desktopPc.isTelevision)
        assertTrue(androidTv.isTelevision)
        assertTrue(domesticBoxNoLeanback.isTelevision)
        assertTrue(phone.copy(uiModeTelevision = true).isTelevision)
        assertTrue(phone.copy(hasLeanback = true).isTelevision)
        assertTrue(phone.copy(hasTelevisionFeature = true).isTelevision)
    }

    @Test fun phoneEntryRoutesByDisplayModeAndDeviceType() {
        assertEquals(ActivityRoute.PHONE, resolveRouteForPhoneEntry(DisplayMode.AUTO, phone))
        assertEquals(ActivityRoute.TV, resolveRouteForPhoneEntry(DisplayMode.AUTO, androidTv))
        assertEquals(ActivityRoute.TV, resolveRouteForPhoneEntry(DisplayMode.AUTO, domesticBoxNoLeanback))

        assertEquals(ActivityRoute.TV, resolveRouteForPhoneEntry(DisplayMode.TV, phone))
        assertEquals(ActivityRoute.TV, resolveRouteForPhoneEntry(DisplayMode.TV, androidTv))

        assertEquals(ActivityRoute.PHONE, resolveRouteForPhoneEntry(DisplayMode.PHONE, phone))
        assertEquals(ActivityRoute.PHONE, resolveRouteForPhoneEntry(DisplayMode.PHONE, androidTv))
    }

    @Test fun tvEntryStaysInTvUnlessExplicitlyLockedToPhone() {
        assertEquals(ActivityRoute.TV, resolveRouteForTvEntry(DisplayMode.AUTO))
        assertEquals(ActivityRoute.TV, resolveRouteForTvEntry(DisplayMode.TV))
        assertEquals(ActivityRoute.PHONE, resolveRouteForTvEntry(DisplayMode.PHONE))
    }

    @Test fun switchToPhoneAvoidsAutoBounceOnTelevisionHardware() {
        assertEquals(DisplayMode.PHONE, modeForSwitchToPhone(androidTv))
        assertEquals(DisplayMode.PHONE, modeForSwitchToPhone(domesticBoxNoLeanback))
        assertEquals(DisplayMode.AUTO, modeForSwitchToPhone(phone))
    }

    @Test fun tvSpeedsMatchSharedPlaybackSpeeds() {
        assertEquals(PlaybackSpeeds, tvSpeeds)
        assertEquals("2.5×", speedLabel(2.5f))
        assertEquals("3×", speedLabel(3f))
    }
}
