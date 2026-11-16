// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.api.service;

import com.hongguo.api.util.Json;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class MobilePlaybackResponseTest {
    @Test public void acceptsFlatAndContentTypeMapsWithExactEpisode() {
        String entry = "\"1234\":{\"video_model\":{\"video_list\":[]}}";
        for (String data : new String[]{"{" + entry + "}", "{\"1004\":{" + entry + "}}"}) {
            Map<String,Object> response = AppSource.parseMobileResponse("1234", "{\"code\":0,\"data\":"+data+"}");
            assertTrue(Json.optObj(response.get("data")).containsKey("1234"));
            try { AppSource.parseMobileResponse("5678", "{\"code\":0,\"data\":"+data+"}"); fail("Wrong episode accepted"); }
            catch (IllegalStateException expected) { }
        }
    }
    @Test public void explicitRefusalStopsRecovery() {
        try { AppSource.parseMobileResponse("1234", "{\"code\":101144}"); fail("Refusal accepted"); }
        catch (AppSource.PlaybackRejectedException expected) { }
    }
}
