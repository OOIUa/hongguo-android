package com.hongguo.api.service;

import com.hongguo.api.util.Json;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 单集接口 data.video_model 的三种形态（同步自 LiuWQ0809/hongguo-android b3d9423）。 */
public class SinglePlaybackResponseTest {
    @Test public void acceptsJsonObjectStringAndBase64ForRequestedEpisode() {
        String model = "{\"video_id\":\"v1\",\"video_list\":[]}";
        for (Object value : new Object[]{Json.parseObject(model), model,
                Base64.getEncoder().encodeToString(model.getBytes(StandardCharsets.UTF_8))}) {
            Map<String, Object> root = Json.obj(), data = Json.obj();
            root.put("code", 0);
            data.put("video_model", value);
            root.put("data", data);
            Map<String, Object> result = AppSource.parseSingleResponse("123", Json.stringify(root));
            Map<String, Object> episodes = Json.optObj(result.get("data"));
            assertTrue(episodes.containsKey("123"));
            assertEquals("v1", Json.optObj(Json.optObj(episodes.get("123")).get("video_model")).get("video_id"));
        }
    }

    @Test(expected = IllegalStateException.class) public void rejectsEmptyModel() {
        AppSource.parseSingleResponse("123", "{\"code\":0,\"data\":{}}");
    }

    @Test(expected = IllegalStateException.class) public void rejectsBusinessError() {
        AppSource.parseSingleResponse("123", "{\"code\":100103,\"data\":{\"video_model\":{\"a\":1}}}");
    }

    @Test(expected = AppSource.EmptyPlaybackResponseException.class) public void emptyBodyIsEmptyResponse() {
        AppSource.parseSingleResponse("123", "");
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsInvalidEpisodeId() {
        AppSource.parseSingleResponse("not-an-id", "{\"code\":0,\"data\":{}}");
    }
}
