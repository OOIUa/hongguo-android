// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.api.playback

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MobilePlayerV5Test {
    @Test fun compressedModelAndSignatureUseIdenticalBytesWithoutAccountCookies() {
        val request = MobilePlayerV5.request("1234", "1234567890123456789", "9876543210987654321", "synthetic", 1800000000000)
        val body = JSONObject(GZIPInputStream(ByteArrayInputStream(request.body)).bufferedReader().readText())
        assertEquals(1, body.getJSONObject("biz_param").getInt("device_level"))
        assertEquals("1234", body.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
        assertEquals("player", body.getString("dr_scene"))
        assertEquals(digest("MD5", request.body).hex().uppercase(), request.headers["x-ss-stub"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals("1800000000", request.headers["x-khronos"])
        assertTrue(request.headers["x-medusa"]!!.isNotBlank())
        assertTrue(request.headers["x-helios"]!!.isNotBlank())
        assertFalse(request.headers.containsKey("Cookie"))
        assertFalse(request.headers.containsKey("x-argus"))
        assertFalse(request.headers.containsKey("x-ladon"))
        assertTrue(request.url.startsWith(MobilePlayerV5.ENDPOINT + "?"))
        assertEquals(0, Signer(mobileV5 = true).branch(request.url.substringAfter('?'), request.body, 1800000000))
    }
}
