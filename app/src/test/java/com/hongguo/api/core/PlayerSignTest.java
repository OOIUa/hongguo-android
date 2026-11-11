package com.hongguo.api.core;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 固定输入与 GPL 上游（LiuWQ0809/hongguo-android e73ed41）的播放器签名逐字节对照。 */
public class PlayerSignTest {
    @Test public void matchesReferenceSignature() {
        String query = "aid=8662&device_id=123456789";
        byte[] body = "{\"video_id\":\"123\"}".getBytes(StandardCharsets.UTF_8);
        byte[] nonce = new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals("8404401c0000f27a76e6957ca1b97022103fad22c9d970977ebb",
                PlayerSign.gorgon(query, body, 1700000000L));
        assertEquals("AgMEBVbVr6bCGXEl3RbL1aAkpFHJpdxRfjTl94yWLoz6K/SQ",
                PlayerSign.ladon(1700000000L, Arrays.copyOfRange(nonce, 2, 6)));
        assertEquals("PMw3qMRaFflguIKYlC2mdpV2IWcBCwn1Koj5IoaLq5b3ZXfATS3iituUmnt2HJFxDCWIpwc/xgRjqtkv5n4uLADEdr1fboupPgZ/Kabe2EMEaQ3/o7oMc8hY3WR/n/piCdIOxKzo9jPh8pSH+K+pvGX42LJEU1ciK+i5QxX7TOyhmIHS+K9t673Uc5uPBA18ECoTk5nn361Gl9LORhAI2E5sgtZNeEdG0ZiGxg8C5aL1iFWeyfmPcEQPxdTh/1DX2LE5dW23fo7nBJN4BnwtNwMYQKz+Lx6nN1D5KGt2wQ9f+w==",
                PlayerSign.argus(query, body, 1700000000L, "123456789", nonce));
    }

    @Test public void headersCarryTimestampAndStub() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = PlayerSign.headers("a=1", body, 1700000000123L, "42");
        assertEquals("1700000000", h.get("x-khronos"));
        assertEquals("1700000000123", h.get("x-ss-req-ticket"));
        assertEquals(32, h.get("x-ss-stub").length());
        assertTrue(h.get("x-gorgon").startsWith("8404401c0000"));
        assertTrue(h.containsKey("x-argus") && h.containsKey("x-ladon"));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsNonNumericDevice() {
        PlayerSign.headers("a=1", new byte[0], 1700000000123L, "abc");
    }
}
