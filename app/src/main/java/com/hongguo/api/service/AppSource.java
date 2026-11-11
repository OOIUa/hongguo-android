package com.hongguo.api.service;

import com.hongguo.api.core.PlayerSign;
import com.hongguo.api.core.PureSign;
import com.hongguo.api.core.Spade;
import com.hongguo.api.util.Crypto;
import com.hongguo.api.util.Http;
import com.hongguo.api.util.Json;
import com.hongguo.api.util.Log;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * App 取直链，移植自 jackotom/hongguo-drama 的 src/native/app-source.js。
 *
 * <p><b>为什么需要这个类</b>：老的 multi_video_model/preload/v1 链路对部分分集
 * （实测 vid=7693917197759958040）已不再返回 video_model 数据 —— data 为 null，
 * 表现为「预热失败：无直链 / video_model」。他的修复（提交
 * "Fix later-episode playback with signed App source"）是换一条路：
 * <ol>
 *   <li>改打 b 域名（api5-normal-sinfonlineb）的 multi_video_model/v1；
 *       设备身份见下方「2026-10-27 上游变化」；</li>
 *   <li>响应里的 video_model 可能直接内联 {@code video_list}（数组），也可能只给一个
 *       {@code fallback_api} 地址，二次 GET 才拿到 {@code video_info.data.video_list}；</li>
 *   <li>媒体 URL 可能被 {@code key_seed} 加密（AES-128-CBC），需先解密；</li>
 *   <li>spade_a -&gt; content key 的推导与既有 {@link Spade#unwrapV1} 等价
 *       （他的 deriveContentKey 是同一算法），直接复用。</li>
 * </ol>
 *
 * <p>与 {@link Client} 的 preload/v1 老路并存：老路先不动，取址时本类优先、
 * 失败再回落，保证其他分集不受影响。
 *
 * <p><b>2026-10-27 上游变化</b>（同步自 sunnysky123/hongguo-web 1.1.5 {@code 79babdf}、
 * jackotom/hongguo-drama 3.9 {@code 5febca2}、tsukiyo-zero/hongguoTV {@code 3889318}）：
 * 播放接口开始校验设备身份，每次随机合成、互不关联的 iid/device_id 会被软拦截
 * （HTTP 200 + 0 字节）。当时改为：
 * <ol>
 *   <li>{@link PlaybackDevice} 向 device_register 注册匿名设备，进程内复用；</li>
 *   <li>播放请求 query 使用注册身份的 device_id / iid / cdid，<b>不带任何签名头</b>；</li>
 *   <li>UA 换为 {@link #PLAYBACK_UA}；</li>
 *   <li>片源内联在 video_model.video_list 时直接使用，不再请求 fallback_api；
 *       内联片源若声明加密却缺少 spade_a，按无效处理，不当作明文播放；</li>
 *   <li>空响应不再笼统报「未返回数据」，单独提示疑似设备身份被拒。</li>
 * </ol>
 * 目录、搜索等其他接口不受影响，仍走 {@link Client} 原有签名链路。
 *
 * <p><b>2026-11-05 上游变化</b>（同步自 tsukiyo-zero/hongguoTV 0.1.22–0.1.25）：无签名请求一律返回
 * 200 + 0 字节，上面「不带签名头」已失效。现在 GET 和 POST 都带完整签名、注册下发的 Cookie 与 x-ss-stub，
 * 见 {@link #signPlayback} 和 {@link #fetch}。
 */
public final class AppSource {

    private AppSource() {}

    /** 接口地址（尾斜杠照抄原实现，签名与路由都以它为准）。 */
    private static final String VIDEO_API =
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/";

    /** 接口主机（自检与排障用）。 */
    public static final String VIDEO_API_HOST = "api5-normal-sinfonlineb.fqnovel.com";

    /** 空响应后重试用的备用主机（hongguo-drama 5.4）。 */
    static final String RETRY_HOST = "api5-normal-hl.fqnovel.com";

    private static final String VIDEO_UA =
            "com.phoenix.read/71332 (Linux; U; Android 16; zh_CN; 25053RT47C; "
            + "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 "
            + "QuicVersion:c67e9834 2025-09-08)";

    /** 官方播放器 UA。 */
    static final String PLAYBACK_UA =
            "com.phoenix.read/71532 (Linux; U; Android 9; SM-N9860; "
            + "Build/PQ3A.190705.10241111;tt-ok/3.12.13.20)";

    private static final String VERSION_NAME = "7.1.3.32";

    /** 官方播放器版本：query 与签名里保持一致。 */
    private static final String PLAYBACK_VERSION_CODE = "73932";
    private static final String PLAYBACK_VERSION_NAME = "7.3.9.32";

    /** 请求域名白名单（他的 checkedUrl 域名表，防签名 body 被带往第三方）。 */
    private static final String[] API_DOMAINS = {"fqnovel.com", "snssdk.com"};

    /** 媒体 CDN 域名白名单。 */
    public static final String[] VOD_DOMAINS =
            {"qznovelvod.com", "fqnovelvod.com", "douyinvod.com"};

    /** decryptSpadeUrl 的常量段（app-source.js constants，64 字节）。 */
    private static final byte[] URL_CONSTANTS = Crypto.unhex(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
            + "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");

    // ==================== 入口 ====================

    /**
     * 取某集的 App 直链。quality 为空 / best / auto 时按「H.264 优先、清晰度优先」
     * 自动选流；指定数值（如 1080p）时按短边尺寸升序偏好。
     *
     * @return 可播放轨道；任何一步失败都抛异常，由调用方决定是否回落老路
     */
    public static Stream.VideoTrack fetch(String vid, String quality) throws Exception {
        if (vid == null || !vid.matches("\\d{1,30}")) {
            throw new IllegalArgumentException("分集编号无效");
        }

        byte[] body = Json.stringify(payload(vid)).getBytes(StandardCharsets.UTF_8);
        PlaybackDevice.Identity identity = PlaybackDevice.fresh();
        // 2026-11-05 起无签名请求一律返回 200 + 0 字节；带完整签名的请求仍随「身份 × 路径」偶发为空，
        // 新身份通常能恢复（hongguoTV 0.1.22–0.1.25）。所以先走官方 GET，再 POST，然后换新身份走 GET
        // 和备用域名，全程有总时限。
        // 不同身份、不同路径返回的片源模型并不一致（2026-11-09 实测：同一集 GET 偶发只给缺密钥的内联片源，
        // POST 却给完整 1080p），所以某条路径的模型解析失败也继续试下一条，全部失败才报模型错误。
        long started = System.currentTimeMillis();
        Exception failure = null;
        Exception modelFailure = null;
        IOException renewal = null;
        // 2026-11-11 起旧的多集接口（GET video_tab / POST）对占位 argus/ladon 一律回空，先走完整签名的单集接口；
        // 它失败时再按原顺序试旧路径。
        try {
            return singlePlayback.get(vid, quality, identity);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (EmptyPlaybackResponseException e) {
            // 与旧路径的空响应同类，交给后面的路径。
        } catch (PlaybackHttpException | IOException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            failure = e;
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            modelFailure = e;
        }
        for (PlaybackRoute route : PLAYBACK_ROUTES) {
            if (System.currentTimeMillis() - started >= PLAYBACK_BUDGET_MS) break;
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("播放请求已取消");
            if (route.renewsIdentity) {
                try {
                    identity = PlaybackDevice.fresh();
                } catch (IOException e) {
                    // 换身份失败（冷却或离线）时仍用原身份试完剩余路径。
                    if (Thread.currentThread().isInterrupted()) throw e;
                    renewal = e;
                }
            }
            Map<String, Object> j;
            try {
                j = route.post ? playbackPost.post(body, identity, route.host) : playbackTab.get(vid, identity);
            } catch (EmptyPlaybackResponseException e) {
                continue;
            } catch (PlaybackHttpException | IOException e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                failure = e;
                continue;
            }
            if (j == null) continue;
            try {
                return parseModel(vid, j, quality);
            } catch (InterruptedIOException e) {
                throw e;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                if (modelFailure == null) modelFailure = e;
                else modelFailure.addSuppressed(e);
            }
        }
        // 模型错误比 HTTP/空响应更能说明本集的情况（如仅有 ByteVC2），优先报告。
        if (modelFailure != null) throw modelFailure;
        if (failure != null) throw failure;
        EmptyPlaybackResponseException empty = new EmptyPlaybackResponseException();
        if (renewal != null) empty.addSuppressed(renewal);
        throw empty;
    }

    /** 一条候选路径：GET 或 POST、主机、是否先换新身份。 */
    static final class PlaybackRoute {
        final boolean post;
        final String host;
        final boolean renewsIdentity;

        PlaybackRoute(boolean post, String host, boolean renewsIdentity) {
            this.post = post;
            this.host = host;
            this.renewsIdentity = renewsIdentity;
        }
    }

    static final PlaybackRoute[] PLAYBACK_ROUTES = {
            new PlaybackRoute(false, VIDEO_API_HOST, false),
            new PlaybackRoute(true, VIDEO_API_HOST, false),
            new PlaybackRoute(false, VIDEO_API_HOST, true),
            new PlaybackRoute(true, RETRY_HOST, false),
    };

    /** 一次取片源的总时限；单次请求另有 {@link #PLAYBACK_TIMEOUT_MS}。 */
    static final long PLAYBACK_BUDGET_MS = 20_000L;
    private static final int PLAYBACK_TIMEOUT_MS = 8_000;

    /** 播放接口 HTTP 状态异常：换路径可能恢复，与业务或格式错误不同。 */
    static final class PlaybackHttpException extends IllegalStateException {
        PlaybackHttpException(int status) {
            super("App 接口 HTTP " + status);
        }
    }

    /** 播放接口返回空响应：疑似匿名身份被拒，fetch 会换新身份重试一次。 */
    static final class EmptyPlaybackResponseException extends IllegalStateException {
        EmptyPlaybackResponseException() {
            super("App 接口返回空响应，疑似播放设备身份被拒，请稍后重试");
        }
    }

    /** 可替换的播放请求（JVM 测试注入）；生产实现见 {@link #postPlayback}. */
    interface PlaybackPost {
        Map<String, Object> post(byte[] body, PlaybackDevice.Identity identity, String host)
                throws Exception;
    }

    private static PlaybackPost playbackPost = AppSource::postPlayback;

    static void setPlaybackPostForTest(PlaybackPost post) {
        playbackPost = post == null ? AppSource::postPlayback : post;
    }

    /** 可替换的单集取流（JVM 测试注入）；生产实现见 {@link #fetchSingle}。 */
    interface SinglePlayback {
        Stream.VideoTrack get(String vid, String quality, PlaybackDevice.Identity identity) throws Exception;
    }

    private static SinglePlayback singlePlayback = AppSource::fetchSingle;

    static void setSinglePlaybackForTest(SinglePlayback playback) {
        singlePlayback = playback == null ? AppSource::fetchSingle : playback;
    }

    /** 可替换的 video_tab 请求（JVM 测试注入）；返回 null 表示该接口不可用、应回退旧流程。 */
    interface PlaybackTab {
        Map<String, Object> get(String vid, PlaybackDevice.Identity identity) throws Exception;
    }

    private static PlaybackTab playbackTab = AppSource::getTabModel;

    static void setPlaybackTabForTest(PlaybackTab tab) {
        playbackTab = tab == null ? AppSource::getTabModel : tab;
    }

    /** 可替换的 fallback_api 请求（JVM 测试注入）。 */
    interface PlaybackFallback {
        Map<String, Object> get(String url) throws Exception;
    }

    private static PlaybackFallback playbackFallback = AppSource::getPlain;

    static void setPlaybackFallbackForTest(PlaybackFallback fallback) {
        playbackFallback = fallback == null ? AppSource::getPlain : fallback;
    }

    /**
     * 从播放接口响应解析出可播放轨道（包内可见，便于用离线响应做 JVM 测试）。
     * 只取所请求的分集，不借用响应里其他分集的模型。
     */
    static Stream.VideoTrack parseModel(String vid, Map<String, Object> j, String quality)
            throws Exception {
        if (Json.optInt(j.get("Code"), 0) != 0 || Json.optInt(j.get("code"), 0) != 0) {
            throw new IllegalStateException("App 接口暂未提供本集播放信息");
        }
        Map<String, Object> data = Json.optObj(j.get("data"));
        Map<String, Object> entry = data == null ? null : Json.optObj(data.get(vid));
        if (entry == null || entry.isEmpty()) {
            throw new IllegalStateException("App 接口未返回所选分集");
        }
        Map<String, Object> model = asObject(entry.get("video_model"));
        if (model.isEmpty()) {
            throw new IllegalStateException("App 接口未返回 video_model");
        }

        // ---- 2. 片源模型：新版直接内联 video_list（数组或对象 map），旧版跟随 fallback_api 二次请求 ----
        Object inlineList = model.get("video_list");
        boolean inline = inlineList instanceof List && !((List<?>) inlineList).isEmpty()
                || inlineList instanceof Map && !((Map<?, ?>) inlineList).isEmpty();
        if (!inline) {
            return fallbackTrack(readFallback(model), parseFallback(model.get("fallback_api")), quality);
        }
        try {
            return selectTrack(model, quality, true, 0);
        } catch (ByteVc2Only onlyByteVc2) {
            // 内联全是 ByteVC2：备用接口及其兼容请求仍可能给出 HEVC。
            String fallback = parseFallback(model.get("fallback_api"));
            if (fallback == null || fallback.isEmpty()) throw onlyByteVc2;
            Map<String, Object> info = readFallback(fallback);
            confirmSameVideo(model, info);
            return fallbackTrack(info, fallback, quality);
        } catch (IncompleteEncryption incomplete) {
            // 内联片源既无密钥也未声明加密状态时才尝试备用接口；备用片源须独立通过编号、
            // 完整时长、清晰度与加密信息校验（同步自 hongguo-drama 5.5）。
            try {
                return verifiedFallback(model, quality, incomplete);
            } catch (InterruptedIOException e) {
                throw e;
            } catch (Exception fallbackFailure) {
                if (Thread.currentThread().isInterrupted()) throw fallbackFailure;
                // 2026-11-09 实测（issue #3，第二季第 14、18 集等）：服务端对未加密的分集整个省略 encrypt_info，
                // 媒体本身是明文 hvc1。备用片源也确认不了时，按明文选这些「未声明」的档位；明文由
                // Mp4Progressive.inspect / Stream.validateMp4 检查索引确认，带 encv/sinf 的仍会被拒。
                // 明确声明加密却缺 spade_a 的档位依旧不用。
                try {
                    return selectTrack(model, quality, true, 0, true);
                } catch (Exception plainFailure) {
                    fallbackFailure.addSuppressed(plainFailure);
                    throw fallbackFailure;
                }
            }
        }
    }

    /** 内联片源缺加密信息时，使用编号、完整时长与清晰度都对得上的备用片源。 */
    private static Stream.VideoTrack verifiedFallback(Map<String, Object> model, String quality,
            IncompleteEncryption incomplete) throws Exception {
        String id = Json.optStr(model.get("video_id"), "").trim();
        double duration = seconds(model.get("video_duration"));
        String fallback = parseFallback(model.get("fallback_api"));
        if (id.isEmpty() || duration <= 0 || incomplete.minimumPixels <= 0
                || fallback == null || fallback.isEmpty()) {
            throw incomplete;
        }
        Map<String, Object> info = readFallback(model);
        double other = seconds(info.get("video_duration"));
        if (!id.equals(Json.optStr(info.get("video_id"), "")) || other <= 0
                || Math.abs(other - duration) > 0.5) {
            throw new IllegalStateException("备用片源编号或完整时长无法确认，已停止取址");
        }
        return selectTrack(info, quality, true, incomplete.minimumPixels);
    }

    /**
     * 备用接口的兼容编码参数，按顺序尝试。2026-11-10 实测（issue #3，《谁让这个文盲修仙的第六季》第 58 集）：
     * 原地址带 {@code codec_type=4}，全档 ByteVC2；{@code codec_type=1} 全档返回 h265，
     * 旧的 {@code codec_type=0&force_codec=h264} 仍是 ByteVC2。旧参数保留在后，覆盖曾由它拿到 HEVC 的分集
     * （同步自 hongguoTV 0.1.20，issue #56，提交 {@code 265472f}）。
     */
    static final String[] COMPATIBLE_CODEC_QUERIES = {
            "codec_type=1",
            "codec_type=0&force_codec=h264",
    };

    /**
     * 从备用模型选档。全档 ByteVC2 时，在同一地址依次换兼容编码参数再读。参数名不保证编码，
     * 返回结果仍按实际编码过滤，并须与原模型是同一视频、同一时长。
     */
    private static Stream.VideoTrack fallbackTrack(Map<String, Object> info, String fallback, String quality)
            throws Exception {
        try {
            return selectTrack(info, quality, false, 0);
        } catch (ByteVc2Only onlyByteVc2) {
            if (fallback == null || fallback.isEmpty()) throw onlyByteVc2;
            for (String codecQuery : COMPATIBLE_CODEC_QUERIES) {
                try {
                    Map<String, Object> other = readFallback(compatibleFallback(fallback, codecQuery));
                    confirmSameVideo(info, other);
                    return selectTrack(other, quality, false, 0);
                } catch (InterruptedIOException e) {
                    throw e;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) throw e;
                    onlyByteVc2.addSuppressed(e);
                }
            }
            throw onlyByteVc2;
        }
    }

    /** 替换 codec_type / force_codec，其余参数按原样保留（不解码再编码，避免改动已签名的值）。 */
    static String compatibleFallback(String fallback, String codecQuery) {
        String url = cleanUrl(fallback);
        int q = url.indexOf('?');
        StringBuilder sb = new StringBuilder(q < 0 ? url : url.substring(0, q)).append('?');
        if (q >= 0) {
            for (String part : url.substring(q + 1).split("&")) {
                String name = part.split("=", 2)[0];
                if (part.isEmpty() || name.equals("codec_type") || name.equals("force_codec")) continue;
                sb.append(part).append('&');
            }
        }
        return sb.append(codecQuery).toString();
    }

    /** 替换模型在双方都声明时，不得指向另一个视频或另一完整时长。 */
    private static void confirmSameVideo(Map<String, Object> reference, Map<String, Object> other) {
        String id = Json.optStr(reference.get("video_id"), "").trim();
        String otherId = Json.optStr(other.get("video_id"), "").trim();
        double duration = seconds(reference.get("video_duration"));
        double otherDuration = seconds(other.get("video_duration"));
        if ((!id.isEmpty() && !otherId.isEmpty() && !id.equals(otherId))
                || (duration > 0 && otherDuration > 0 && Math.abs(duration - otherDuration) > 0.5)) {
            throw new IllegalStateException("备用片源编号或完整时长无法确认，已停止取址");
        }
    }

    /** 请求 fallback_api 并取出其中的片源模型。 */
    private static Map<String, Object> readFallback(Map<String, Object> model) throws Exception {
        return readFallback(parseFallback(model.get("fallback_api")));
    }

    private static Map<String, Object> readFallback(String fallback) throws Exception {
        if (fallback == null || fallback.isEmpty()) {
            throw new IllegalStateException("App 接口未返回本集片源地址");
        }
        Map<String, Object> outer = playbackFallback.get(checkedUrl(fallback, API_DOMAINS));
        Map<String, Object> outerInfo = Json.optObj(outer.get("video_info"));
        if (Json.optInt(outer.get("Code"), 0) != 0
                || Json.optInt(outer.get("code"), 0) != 0
                || Json.optInt(outerInfo == null ? null : outerInfo.get("Code"), 0) != 0) {
            throw new IllegalStateException("App 接口暂未提供本集播放信息");
        }
        Map<String, Object> info = outerInfo == null ? null : Json.optObj(outerInfo.get("data"));
        if (info == null || info.isEmpty()) {
            throw new IllegalStateException("App 接口未返回本集片源模型");
        }
        return info;
    }

    private static double seconds(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return value == null ? 0 : Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 从片源模型选出第一条可播放的轨道。[requireEncryption] 时，无密钥的候选必须明确声明为明文；
     * [minimumPixels] 大于 0 时不接受短边更小的候选，避免备用片源悄悄降低清晰度。
     */
    private static Stream.VideoTrack selectTrack(Map<String, Object> info, String quality,
            boolean requireEncryption, int minimumPixels) throws Exception {
        return selectTrack(info, quality, requireEncryption, minimumPixels, false);
    }

    /** [acceptUndeclared]：完全没有 encrypt_info 加密标记的档位按明文接受（明文另由媒体索引校验）。 */
    private static Stream.VideoTrack selectTrack(Map<String, Object> info, String quality,
            boolean requireEncryption, int minimumPixels, boolean acceptUndeclared) throws Exception {
        // video_list 可能是对象 map（fallback 模型），也可能是数组（内联模型）
        List<Object> videoItems = new ArrayList<>();
        Object rawList = info.get("video_list");
        if (rawList instanceof Map) {
            videoItems.addAll(((Map<?, ?>) rawList).values());
        } else if (rawList instanceof List) {
            videoItems.addAll((List<?>) rawList);
        }
        // key_seed 存在说明媒体 URL 是密文，需 AES 解密
        byte[] seed = null;
        String seedStr = Json.optStr(info.get("key_seed"), "");
        if (!seedStr.isEmpty()) {
            seed = Crypto.base64Decode(seedStr);
        }

        // ---- 3. 选流（H.264 优先，其次清晰度）----
        List<Row> rows = new ArrayList<>();
        if (videoItems.isEmpty()) throw new IllegalStateException("本集未返回可用片源列表");
        for (Object item : videoItems) {
            Row r = toRow(Json.optObj(item));
            if (r != null) rows.add(r);
        }
        boolean auto = quality == null || quality.isEmpty()
                || quality.equals("best") || quality.equals("auto");
        int wantPx = auto ? 0 : pixelsOf(quality);
        List<Row> ok = new ArrayList<>();
        for (Row r : rows) {
            if (r.codecRank >= 2) continue;               // 只要 H.264 / HEVC
            if (minimumPixels > 0 && r.shortSide < minimumPixels) continue;
            if (!auto && wantPx > 0 && r.shortSide < wantPx) continue;
            ok.add(r);
        }
        if (auto) {
            ok.sort((a, b) -> a.codecRank != b.codecRank
                    ? a.codecRank - b.codecRank : b.pixels - a.pixels);
        } else {
            ok.sort((a, b) -> a.shortSide != b.shortSide
                    ? a.shortSide - b.shortSide : a.codecRank - b.codecRank);
        }
        if (ok.isEmpty()) {
            boolean compatible = false, byteVc2 = false;
            for (Row r : rows) {
                compatible |= r.codecRank < 2;
                byteVc2 |= r.codec.equals("bytevc2") || r.codec.equals("bvc2");
            }
            // 目前只见到备用接口的兼容请求能换编码（见 fallbackTrack）。
            if (!compatible && byteVc2) throw new ByteVc2Only();
            throw new IllegalStateException("本集暂未提供兼容的 H.264/HEVC 片源");
        }

        // ---- 4. 逐候选解 URL 与密钥，取第一个可用的 ----
        // 注意 VideoTrack.spadeA 的契约是「原始 base64 spade_a」——解包由
        // Stream.offlineDecrypt 统一完成（老路同此约定）。这里只预解一次
        // 用于判断该候选是否可用，不回存解出的 key。
        Exception last = null;
        boolean incomplete = false;
        for (Row r : ok) {
            String spade = firstNonEmpty(r.item.get("spade_a"),
                    Json.optStr(child(r.item, "encrypt_info").get("spade_a"), ""));
            boolean keyOk = spade.isEmpty();
            boolean undeclared = child(r.item, "encrypt_info").get("encrypt") == null;
            if (spade.isEmpty() && requireEncryption && !declaredPlain(r.item)
                    && !(acceptUndeclared && undeclared)) {
                // 内联模型声明加密（或未声明）却缺少 spade_a：不能当作明文播放
                keyOk = false;
                incomplete |= child(r.item, "encrypt_info").get("encrypt") == null;
            }
            if (!spade.isEmpty()) {
                keyOk = Spade.isValidKey(Spade.unwrapV1(Crypto.base64Decode(spade), 0));
            }
            if (!keyOk) continue; // spade_a 解不出合法 key 的候选不可用
            for (String candidate : urlCandidates(r.item)) {
                try {
                    String media = seed != null
                            ? decryptSpadeUrl(candidate, seed) : cleanUrl(candidate);
                    String url = checkedUrl(media, VOD_DOMAINS);
                    Stream.VideoTrack t = new Stream.VideoTrack();
                    t.url = url;
                    t.spadeA = spade;
                    t.encrypt = !spade.isEmpty();
                    t.definition = r.quality.isEmpty() ? "?" : r.quality;
                    t.size = r.size;
                    return t;
                } catch (Exception ex) {
                    last = ex;
                }
            }
        }
        if (incomplete) throw new IncompleteEncryption(ok.get(0).shortSide);
        throw new IllegalStateException("本集片源地址或密钥无法解析");
    }

    /** 选中的内联候选缺少加密信息；调用方可据此尝试受校验的备用片源。 */
    /** 所有档位都是播放器无法解码的 ByteVC2。 */
    private static final class ByteVc2Only extends IllegalStateException {
        ByteVc2Only() {
            super("本集仅提供播放器不支持的 ByteVC2 编码，当前无法播放，请在官方 App 观看");
        }
    }

    private static final class IncompleteEncryption extends IllegalStateException {
        final int minimumPixels;

        IncompleteEncryption(int minimumPixels) {
            super("本集片源地址或密钥无法解析");
            this.minimumPixels = minimumPixels;
        }
    }

    // ==================== 请求构造 ====================

    /** 请求体（biz_param 与老路一致；注意不带 dr_scene）。 */
    private static Map<String, Object> payload(String vid) {
        Map<String, Object> biz = Json.obj();
        biz.put("detail_page_version", 0);
        // level=3 会把部分剧集的 360P-720P 下发成 bytevc2（无法解码），只剩 1080P；
        // level=1 对正常剧集的供给与 3 一致（同步自 hongguoTV 0.1.12）。
        biz.put("device_level", 1);
        biz.put("disable_digg_stat", false);
        biz.put("need_all_video_definition", true);
        biz.put("need_mp4_align", false);
        biz.put("use_os_player", false);
        biz.put("use_server_dns", false);
        biz.put("video_platform", 1024);
        Map<String, Object> mixed = Json.obj();
        List<Object> ids = Json.arr();
        ids.add(vid);
        mixed.put("1004", ids);
        Map<String, Object> out = Json.obj();
        out.put("biz_param", biz);
        out.put("mixed_video_id_map", mixed);
        return out;
    }

    /**
     * 播放请求的基础设备参数（字段顺序固定 —— query 按插入序编码）。
     * 不含 iid/device_id：播放身份由 {@link PlaybackDevice} 注册提供
     * （2026-10-27 起随机合成的身份会被上游软拦截）。
     */
    public static Map<String, Object> deviceBase() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ac", "wifi");
        d.put("channel", "update_64");
        d.put("aid", "8662");
        d.put("app_name", "novelread");
        d.put("version_code", "71332");
        d.put("version_name", VERSION_NAME);
        d.put("device_platform", "android");
        d.put("os", "android");
        d.put("ssmix", "a");
        d.put("device_type", "25053RT47C");
        d.put("device_brand", "Redmi");
        d.put("language", "zh");
        d.put("os_api", "36");
        d.put("os_version", "16");
        d.put("manifest_version_code", "71332");
        d.put("resolution", "1280*2772");
        d.put("dpi", "520");
        d.put("update_version_code", "71332");
        d.put("host_abi", "arm64-v8a");
        d.put("dragon_device_type", "phone");
        d.put("pv_player", "71332");
        d.put("compliance_status", "0");
        d.put("need_personal_recommend", "1");
        d.put("player_so_load", "1");
        d.put("is_android_pad_screen", "0");
        return d;
    }

    /** 已签名的 App 请求：URL 与签名头绑定，发送时不能再改动。 */
    public static final class SignedPlayback {
        public final String url;
        public final Map<String, String> headers;

        SignedPlayback(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    /**
     * 官方播放器形态的签名请求（同步自 hongguoTV 0.1.25 {@code playbackRequest}）：注册身份同时进入 query 和
     * medusa，版本号用官方播放器版本，只接受 hash 分支 0，靠抖动 _rticket 选到它。
     */
    static SignedPlayback signPlayback(String base, PlaybackDevice.Identity identity, Map<String, String> extra,
            byte[] body, long nowMs) {
        Map<String, String> q = identityQuery(identity);
        for (String name : new String[]{"version_code", "manifest_version_code", "update_version_code", "pv_player"}) {
            q.put(name, PLAYBACK_VERSION_CODE);
        }
        q.put("version_name", PLAYBACK_VERSION_NAME);
        q.put("cdid", identity.cdid);
        q.putAll(extra);
        return signQuery(base, q, body, nowMs, PLAYBACK_UA, identity.cookie);
    }

    /** 推荐流使用的官方 App 版本（hongguoTV 0.1.26 依官方 7.4.3.32 APK 调用链统一）。 */
    public static final String RECOMMENDATION_VERSION_CODE = "74332";
    public static final String RECOMMENDATION_VERSION_NAME = "7.4.3.32";
    static final String RECOMMENDATION_UA =
            "com.phoenix.read/74332 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)";

    /**
     * 推荐流的完整签名请求（同步自 hongguoTV 0.1.26 {@code RecommendationFeed}，commit {@code 62910e0}）：
     * 注册身份与设备参数在前，调用方参数覆盖同名字段，pv_player 跟随推荐版本。cookie 为空时用注册下发的
     * 匿名 Cookie；登录态由调用方传入账号自己的 Cookie。
     */
    public static SignedPlayback signRecommendation(String base, PlaybackDevice.Identity identity,
            Map<String, String> params, String cookie, long nowMs) {
        Map<String, String> q = identityQuery(identity);
        q.put("cdid", identity.cdid);
        q.putAll(params);
        q.remove("_rticket");
        q.put("pv_player", RECOMMENDATION_VERSION_CODE);
        String sent = cookie == null || cookie.isEmpty() ? identity.cookie : cookie;
        return signQuery(base, q, null, nowMs, RECOMMENDATION_UA, sent);
    }

    private static Map<String, String> identityQuery(PlaybackDevice.Identity identity) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("iid", identity.iid);
        q.put("device_id", identity.deviceId);
        for (Map.Entry<String, Object> e : deviceBase().entrySet()) q.put(e.getKey(), String.valueOf(e.getValue()));
        return q;
    }

    private static SignedPlayback signQuery(String base, Map<String, String> q, byte[] body, long nowMs,
            String userAgent, String cookie) {
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : q.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(queryEncode(e.getKey())).append('=').append(queryEncode(e.getValue()));
        }
        long khronos = nowMs / 1000L;
        for (int offset = 0; offset < 128; offset++) {
            long ticket = nowMs + offset;
            String url = base + "?" + qs + "&_rticket=" + ticket;
            if (PureSign.branchOf(url, body, khronos) != 0) continue;
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", userAgent);
            headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
            headers.put("x-ss-req-ticket", String.valueOf(ticket));
            headers.put("sdk-version", "2");
            headers.put("x-xs-from-web", "0");
            headers.putAll(PureSign.sign(url, body, khronos));
            if (body != null) {
                headers.put("Content-Type", "application/json; charset=utf-8");
                headers.put("x-ss-stub", Crypto.md5HexUpper(body));
            }
            if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
            return new SignedPlayback(url, headers);
        }
        throw new IllegalStateException("无法生成播放签名");
    }

    /** 与官方请求一致：* 不编码，其余同 {@link #pyEncode}。 */
    private static String queryEncode(String raw) {
        return pyEncode(raw).replace("%2A", "*");
    }

    // ==================== 单集接口（2026-11-11） ====================
    // 同步自 LiuWQ0809/hongguo-android（commits 675f895、10bda56、1765bfa，2026-11-11，GPL-3.0）。

    /** 单集接口地址。 */
    private static final String SINGLE_API = "https://" + VIDEO_API_HOST + "/novel/player/video_model/v1/";
    private static final String SINGLE_VERSION_CODE = "73532";
    private static final String SINGLE_VERSION_NAME = "7.3.5.32";

    /** 单集接口使用 7.3.5.32 播放器身份；query 按键名排序，签名就对这串最终发送的 query 计算。 */
    static String singleUrl(PlaybackDevice.Identity identity, long now) {
        Map<String, Object> q = new TreeMap<>(deviceBase());
        for (String name : new String[]{"host_abi", "dragon_device_type", "pv_player", "compliance_status",
                "need_personal_recommend", "player_so_load", "is_android_pad_screen"}) {
            q.remove(name);
        }
        for (String name : new String[]{"version_code", "manifest_version_code", "update_version_code"}) {
            q.put(name, SINGLE_VERSION_CODE);
        }
        q.put("version_name", SINGLE_VERSION_NAME);
        q.put("device_id", identity.deviceId);
        q.put("iid", identity.iid);
        q.put("_rticket", now);
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, Object> e : q.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(Http.urlencode(e.getKey())).append('=').append(Http.urlencode(String.valueOf(e.getValue())));
        }
        return SINGLE_API + "?" + query;
    }

    /** 单集接口请求体。 */
    static byte[] singleBody(String vid) {
        Map<String, Object> biz = Json.obj();
        biz.put("need_all_video_definition", true);
        biz.put("video_platform", 3);
        Map<String, Object> payload = Json.obj();
        payload.put("video_id", vid);
        payload.put("content_type", 1);
        payload.put("biz_param", biz);
        return Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
    }

    /** 完整签名的单集 POST；空正文按空响应处理，模型交给 {@link #parseModel}（ByteVC2 与备用片源规则不变）。 */
    private static Stream.VideoTrack fetchSingle(String vid, String quality, PlaybackDevice.Identity identity)
            throws Exception {
        byte[] body = singleBody(vid);
        long now = System.currentTimeMillis();
        String url = singleUrl(identity, now);
        Http.Req req = new Http.Req(url).method("POST").timeout(PLAYBACK_TIMEOUT_MS);
        req.followRedirect = false;
        req.header("User-Agent", VIDEO_UA.replace("71332", SINGLE_VERSION_CODE));
        req.header("Accept", "application/json");
        req.header("Content-Type", "application/json; charset=utf-8");
        req.header("x-xs-from-web", "0");
        req.header("sdk-version", "2");
        for (Map.Entry<String, String> e : PlayerSign.headers(URI.create(url).getRawQuery(), body, now,
                identity.deviceId).entrySet()) {
            req.header(e.getKey(), e.getValue());
        }
        req.body(body);
        Http.Resp res = Http.send(req);
        if (res.status != 200) throw new PlaybackHttpException(res.status);
        return parseModel(vid, parseSingleResponse(vid, res.text()), quality);
    }

    /** 单集接口把模型放在 {@code data.video_model}（对象、JSON 字符串或 Base64）；整理成 parseModel 的形态。 */
    static Map<String, Object> parseSingleResponse(String vid, String text) {
        if (vid == null || !vid.matches("\\d{1,30}")) throw new IllegalArgumentException("分集编号无效");
        Map<String, Object> root = parsePlaybackResponse(text);
        Object code = root.containsKey("code") ? root.get("code") : root.get("Code");
        if (code == null || Json.optInt(code, -1) != 0) throw new IllegalStateException("单集接口暂未提供播放信息");
        Map<String, Object> data = Json.optObj(root.get("data"));
        Object raw = data == null ? null : data.get("video_model");
        if (raw instanceof String && !((String) raw).trim().startsWith("{")) {
            try {
                raw = new String(Crypto.base64Decode(((String) raw).trim()), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                throw new IllegalStateException("单集播放信息格式无效");
            }
        }
        Map<String, Object> model = asObject(raw);
        if (model.isEmpty()) throw new IllegalStateException("单集接口未返回播放模型");
        Map<String, Object> entry = Json.obj();
        entry.put("video_model", model);
        Map<String, Object> episodes = Json.obj();
        episodes.put(vid, entry);
        Map<String, Object> result = Json.obj();
        result.put("code", 0L);
        result.put("data", episodes);
        return result;
    }

    /** video_tab 接口地址（与旧接口同主机）。 */
    private static final String TAB_API = "https://" + VIDEO_API_HOST
            + "/reading/bookapi/video_tab/multi_video_model/v1/";

    /** GetVideoModelRequest 的全部字段：缺任一字段服务端回 100103 PARAM_INVALID。该接口一次只接受一集。 */
    static Map<String, String> tabQuery(String vid) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("video_id", vid);
        q.put("video_id_type", "0");
        q.put("device_level", "1");
        q.put("need_all_video_definition", "1");
        q.put("need_mp4_align", "0");
        q.put("use_os_player", "0");
        q.put("video_platform", "1024");
        q.put("source", "0");
        q.put("from_video_id", "");
        return q;
    }

    /** 单次签名 GET video_tab；空响应、网络、HTTP 或结构问题一律返回 null，由 fetch 换下一条路径。 */
    private static Map<String, Object> getTabModel(String vid, PlaybackDevice.Identity identity)
            throws Exception {
        SignedPlayback signed = signPlayback(TAB_API, identity, tabQuery(vid), null, System.currentTimeMillis());
        Http.Req req = new Http.Req(signed.url).method("GET").timeout(PLAYBACK_TIMEOUT_MS);
        req.followRedirect = false;
        for (Map.Entry<String, String> e : signed.headers.entrySet()) req.header(e.getKey(), e.getValue());
        Http.Resp res;
        try {
            res = Http.send(req);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            return null;
        }
        return res.status == 200 ? parseTabResponse(vid, res.text()) : null;
    }

    /**
     * 把 {@code data.video_model_data[vid].video_model}（对象或 JSON 字符串）整理成 parseModel
     * 认识的 {@code {code:0, data:{vid:{video_model}}}}；非 0 code、缺分集或 video_list 为空返回 null。
     */
    static Map<String, Object> parseTabResponse(String vid, String text) {
        Map<String, Object> root;
        try { root = Json.parseObject(text); } catch (Exception ignored) { return null; }
        if (root == null) return null;
        Object code = root.containsKey("code") ? root.get("code") : root.get("Code");
        if (code == null || Json.optInt(code, -1) != 0) return null;
        Map<String, Object> data = Json.optObj(root.get("data"));
        Map<String, Object> rows = data == null ? null : Json.optObj(data.get("video_model_data"));
        Map<String, Object> row = rows == null ? null : Json.optObj(rows.get(vid));
        if (row == null) return null;
        Map<String, Object> model = asObject(row.get("video_model"));
        if (model == null || Json.optArr(model.get("video_list")).isEmpty()) return null;
        Map<String, Object> entry = Json.obj();
        entry.put("video_model", model);
        Map<String, Object> episodes = Json.obj();
        episodes.put(vid, entry);
        Map<String, Object> j = Json.obj();
        j.put("code", 0L);
        j.put("data", episodes);
        return j;
    }

    /** 单次签名 POST。 */
    private static Map<String, Object> postPlayback(byte[] body, PlaybackDevice.Identity identity,
            String host) throws Exception {
        SignedPlayback signed = signPlayback(VIDEO_API.replace(VIDEO_API_HOST, host), identity,
                new LinkedHashMap<>(), body, System.currentTimeMillis());
        Http.Req req = new Http.Req(signed.url).method("POST").timeout(PLAYBACK_TIMEOUT_MS);
        req.followRedirect = false;
        for (Map.Entry<String, String> e : signed.headers.entrySet()) req.header(e.getKey(), e.getValue());
        req.body(body);
        Http.Resp res = Http.send(req);
        if (res.status != 200) throw new PlaybackHttpException(res.status);
        return parsePlaybackResponse(res.text());
    }

    /** 区分空响应（疑似设备身份被拒）与其他无效响应。空响应不值得用同一身份重试。 */
    static Map<String, Object> parsePlaybackResponse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new EmptyPlaybackResponseException();
        }
        Map<String, Object> j;
        try { j = Json.parseObject(text); }
        catch (Exception ignored) { throw new IllegalStateException("播放信息格式无效"); }
        if (j == null || Json.optObj(j.get("data")) == null) {
            // data 缺失：接口变更或 IP 风控。
            throw new IllegalStateException("App 接口未返回播放数据");
        }
        return j;
    }

    /** fallback_api 的普通 GET：不带签名，只带 UA（与原实现一致）。 */
    private static Map<String, Object> getPlain(String url) throws Exception {
        Http.Req req = new Http.Req(url).method("GET").timeout(15000);
        req.header("User-Agent", VIDEO_UA);
        Http.Resp res = Http.send(req);
        if (res.status != 200) {
            throw new IllegalStateException("fallback HTTP " + res.status);
        }
        try { return Json.parseObject(res.text()); }
        catch (Exception ignored) { throw new IllegalStateException("播放信息格式无效"); }
    }

    /**
     * query 键值编码：未保留字符编 %XX（大写）。
     * 保留集 = JS encodeURIComponent 的不编码集减去被他显式转码的 !'()*。
     */
    public static String pyEncode(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    // ==================== 响应解析 ====================

    /** video_model 可能是 JSON 字符串或对象。 */
    private static Map<String, Object> asObject(Object v) {
        if (v instanceof String) {
            try {
                return Json.parseObject((String) v);
            } catch (Exception e) {
                return Json.obj();
            }
        }
        Map<String, Object> m = Json.optObj(v);
        return m == null ? Json.obj() : m;
    }

    /** 取子对象；缺失返回空 map（Json.optObj 对 null 返回 null，链式调用会踩 NPE）。 */
    private static Map<String, Object> child(Map<String, Object> m, String key) {
        if (m == null) return Json.obj();
        Map<String, Object> c = Json.optObj(m.get(key));
        return c == null ? Json.obj() : c;
    }

    /**
     * fallback_api 可能是 URL 字符串、[url]、{"fallback_api": url}
     * 或内嵌 JSON 的字符串。
     */
    private static String parseFallback(Object v) {
        if (v instanceof String) {
            String s = (String) v;
            Map<String, Object> parsed;
            try {
                parsed = Json.parseObject(s);
            } catch (Exception e) {
                return s;
            }
            Object fb = parsed.get("fallback_api");
            return fb == null ? s : String.valueOf(fb);
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            return l.isEmpty() ? "" : String.valueOf(l.get(0));
        }
        Map<String, Object> map = Json.optObj(v);
        Object fb = map == null ? null : map.get("fallback_api");
        return fb == null ? "" : String.valueOf(fb);
    }

    /** 一条候选流的评分信息。 */
    private static final class Row {
        final Map<String, Object> item;
        final String codec;
        final int codecRank;
        final String quality;
        final int pixels;
        final int shortSide;
        final long size;

        Row(Map<String, Object> item, String codec, int codecRank, String quality,
                int pixels, int shortSide, long size) {
            this.item = item;
            this.codec = codec;
            this.codecRank = codecRank;
            this.quality = quality;
            this.pixels = pixels;
            this.shortSide = shortSide;
            this.size = size;
        }
    }

    private static Row toRow(Map<String, Object> item) {
        if (item == null || item.isEmpty()) return null;
        Map<String, Object> meta = child(item, "video_meta");
        String codec = Json.optStr(item.get("codec_type"),
                Json.optStr(meta.get("codec_type"), "")).toLowerCase();
        int rank = rank(codec);
        String quality = firstNonEmpty(
                Json.optStr(item.get("quality_desc"), ""),
                Json.optStr(item.get("definition"), ""),
                Json.optStr(meta.get("definition"), ""),
                Json.optStr(item.get("height"), ""),
                Json.optStr(item.get("vheight"), ""));
        int px = pixelsOf(quality);
        int w = 0, h = 0;
        int[][] pairs = {
                {Json.optInt(item.get("vwidth"), 0), Json.optInt(item.get("vheight"), 0)},
                {Json.optInt(item.get("width"), 0), Json.optInt(item.get("height"), 0)},
                {Json.optInt(meta.get("vwidth"), 0), Json.optInt(meta.get("vheight"), 0)},
                {Json.optInt(meta.get("width"), 0), Json.optInt(meta.get("height"), 0)},
        };
        for (int[] p : pairs) {
            if (p[0] > 0 && p[1] > 0) { w = p[0]; h = p[1]; break; }
        }
        long size = Json.optLong(meta.get("size"), 0);
        if (size == 0) size = Json.optLong(item.get("size"), 0);
        return new Row(item, codec, rank, quality, px, Math.min(w, h), size);
    }

    private static int rank(String codec) {
        if (codec.equals("h264") || codec.equals("avc") || codec.equals("avc1")) return 0;
        if (codec.equals("hevc") || codec.equals("h265") || codec.equals("hvc1")
                || codec.equals("hev1") || codec.equals("bytevc1")) return 1;
        return 2;
    }

    /** 取字符串里第一组数字；无则 0。 */
    public static int pixelsOf(String s) {
        if (s == null) return 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** main_url / backup_url / backup_url_list 的候选序列。 */
    private static List<String> urlCandidates(Map<String, Object> item) {
        List<String> out = new ArrayList<>();
        addStr(out, item.get("main_url"));
        addStr(out, item.get("backup_url"));
        Object list = item.get("backup_url_list");
        if (list instanceof List) {
            for (Object o : (List<?>) list) addStr(out, o);
        }
        return out;
    }

    private static void addStr(List<String> out, Object v) {
        if (v instanceof String && !((String) v).isEmpty()) out.add((String) v);
    }

    /** encrypt_info.encrypt 明确为 false / 0 时才视为明文片源。 */
    private static boolean declaredPlain(Map<String, Object> item) {
        Object flag = child(item, "encrypt_info").get("encrypt");
        if (flag instanceof Boolean) return !((Boolean) flag);
        if (flag instanceof Number) return ((Number) flag).longValue() == 0;
        if (flag instanceof String) {
            String f = ((String) flag).trim();
            return f.equals("0") || f.equalsIgnoreCase("false");
        }
        return false;
    }

    private static String firstNonEmpty(Object... vals) {
        for (Object v : vals) {
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        }
        return "";
    }

    /** 清掉上游常见的转义形式（他的 cleanUrl）。 */
    public static String cleanUrl(String value) {
        return value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
    }

    // ==================== URL 解密与校验 ====================

    /**
     * 解密 spade URL：seed 双 sha512 派生 AES-128-CBC 密钥/IV。
     * 布局：[0]=0xa8, [1]=?, [2]=0x01, [3]=0x00, [4:]=密文（16 字节整块）。
     */
    public static String decryptSpadeUrl(String value, byte[] seed) throws Exception {
        byte[] raw = Crypto.base64Decode(value);
        if (raw.length < 5) throw new IllegalStateException("spade URL 密文过短");
        if ((raw[0] & 0xFF) != 0xa8 || (raw[2] & 0xFF) != 0x01 || (raw[3] & 0xFF) != 0x00) {
            throw new IllegalStateException("spade URL 头格式错误");
        }
        int cipherLength = ((raw.length - 4) / 16) * 16;
        if (cipherLength == 0) throw new IllegalStateException("spade URL 无完整块");

        MessageDigest md = MessageDigest.getInstance("SHA-512");
        byte[] h1 = md.digest(seed);
        md.update(h1);
        byte[] h2 = md.digest(URL_CONSTANTS);

        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(h2, 0, 16, "AES"),
                new IvParameterSpec(h2, 16, 16));
        byte[] pt = c.doFinal(raw, 4, cipherLength);

        int end = pt.length;
        if (end > 0) {
            int pad = pt[end - 1] & 0xFF;
            if (pad >= 1 && pad <= 16 && pad <= end) end -= pad;
        }
        while (end > 0 && pt[end - 1] == 0) end--;
        return new String(pt, 0, end, StandardCharsets.UTF_8);
    }

    /**
     * 媒体/接口 URL 白名单校验：必须 https、无用户信息、端口为 443，
     * 且 host 等于白名单域名或其子域。防签名 body 被重定向带往第三方。
     */
    public static String checkedUrl(String value, String[] domains) throws Exception {
        URI u;
        try {
            u = URI.create(value);
        } catch (Exception e) {
            throw new IllegalStateException("URL 格式无效");
        }
        String scheme = u.getScheme();
        String host = u.getHost();
        boolean portOk = u.getPort() == -1 || u.getPort() == 443;
        boolean hostOk = false;
        if (host != null) {
            for (String d : domains) {
                if (host.equals(d) || host.endsWith("." + d)) { hostOk = true; break; }
            }
        }
        if (!"https".equals(scheme) || u.getUserInfo() != null || !portOk || !hostOk) {
            throw new IllegalStateException("不受支持的服务地址");
        }
        return u.toString();
    }
}
