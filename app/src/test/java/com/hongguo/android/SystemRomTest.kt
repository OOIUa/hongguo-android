package com.hongguo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemRomTest {
    @Test fun hyperOsVersionCodeMeansHyperOs() {
        assertTrue(SystemRom.isHyperOs { if (it == "ro.mi.os.version.code") "4" else null })
    }

    @Test fun miuiAndOtherVendorsAreNotHyperOs() {
        // MIUI only exposes ro.miui.ui.version.*; stock Android returns an empty string for unknown keys.
        assertFalse(SystemRom.isHyperOs { if (it == "ro.miui.ui.version.name") "V14" else "" })
        assertFalse(SystemRom.isHyperOs { null })
    }

    @Test fun navBarDefaultsToGlassOnlyOnHyperOs() {
        assertEquals(NavBarStyle.GLASS, NavBarStyle.resolve(null, hyperOs = true))
        assertEquals(NavBarStyle.CLASSIC, NavBarStyle.resolve(null, hyperOs = false))
    }

    @Test fun savedNavBarChoiceWinsOverDeviceDefault() {
        assertEquals(NavBarStyle.CLASSIC, NavBarStyle.resolve("classic", hyperOs = true))
        assertEquals(NavBarStyle.GLASS, NavBarStyle.resolve("glass", hyperOs = false))
        assertEquals(NavBarStyle.CLASSIC, NavBarStyle.resolve("unknown", hyperOs = false))
    }
}
