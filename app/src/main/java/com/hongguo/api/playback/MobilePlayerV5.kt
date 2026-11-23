// SPDX-License-Identifier: GPL-3.0-only
// Model contract from N3urda/hongguoTV 6672b55, including issue 61 device_level=1.
package com.hongguo.api.playback

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

object MobilePlayerV5 {
    const val ENDPOINT = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1"

    /** video_tab 属于 player 签名族，走 v5 无正文 GET（2026-11-20 起唯一可用的取源路径）。 */
    const val TAB_ENDPOINT =
        "https://api5-normal-sinfonlineb.fqnovel.com/reading/bookapi/video_tab/multi_video_model/v1/"

    private const val TAB_UA =
        "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; " +
            "Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)"

    private val signer = Signer(mobileV5 = true)
    data class Request(val url: String, val headers: Map<String, String>, val body: ByteArray)

    /** 无正文 GET 请求（video_tab），调用方自行以 GET 发送。 */
    data class TabRequest(val url: String, val headers: Map<String, String>)

    /**
     * 官方播放 query 的完整设备上下文（2026-11-24 对齐 lvguo-tv 的可用形态）。
     *
     * 此前只发 21 个参数，缺 resolution / dpi / os_api / rom_version / host_abi /
     * dragon_device_type / compliance_status / player_so_load 等分量。签名本身合法，
     * 但上游把这些当作「设备画像不完整」，同样回 200 + 0 字节。这里按官方顺序补齐；
     * 注册下发 klink_egdi 时附带（空值不写，避免污染签名串）。
     */
    private fun deviceQuery(
        deviceId: String,
        iid: String,
        cdid: String,
        egdi: String?,
    ): LinkedHashMap<String, String> {
        val values = linkedMapOf(
            "device_platform" to "android", "os" to "android", "ssmix" to "a",
            "cdid" to cdid, "channel" to "vivo_8662_64",
            "aid" to "8662", "app_name" to "novelread",
            "version_code" to "74332", "version_name" to "7.4.3.32",
            "manifest_version_code" to "74332", "update_version_code" to "74332",
            "resolution" to "1920*1080", "dpi" to "280",
            "device_type" to "PGT-AN10", "device_brand" to "HONOR",
            "language" to "zh", "os_api" to "31", "os_version" to "12",
            "ac" to "wifi", "host_abi" to "arm64-v8a",
            "dragon_device_type" to "phone", "pv_player" to "74332",
            "compliance_status" to "0", "need_personal_recommend" to "1",
            "player_so_load" to "1", "is_android_pad_screen" to "0",
            "rom_version" to "V417IR+release-keys",
            "iid" to iid, "device_id" to deviceId,
        )
        if (!egdi.isNullOrEmpty()) values["klink_egdi"] = egdi
        return values
    }

    /** video_tab 的 v5 无正文 GET：查询参数与头的形态对齐 hongguo-drama 7.7。 */
    @JvmStatic @JvmOverloads
    fun requestVideoTab(
        id: String,
        deviceId: String,
        iid: String,
        cdid: String,
        now: Long,
        egdi: String? = null,
    ): TabRequest {
        require(listOf(id, deviceId, iid).all { it.matches(Regex("[0-9]{1,30}")) })
        val values = deviceQuery(deviceId, iid, cdid, egdi)
        values.putAll(
            linkedMapOf(
                "video_id" to id, "video_id_type" to "0", "device_level" to "1",
                "need_all_video_definition" to "1", "need_mp4_align" to "0",
                "use_os_player" to "0", "video_platform" to "1024",
                "source" to "0", "from_video_id" to "",
            )
        )
        val signed = signer.signPlaybackGet(TAB_ENDPOINT, values, now)
        return TabRequest(
            signed.url,
            signed.headers + mapOf("User-Agent" to TAB_UA, "Accept" to "application/json"),
        )
    }

    @JvmStatic @JvmOverloads
    fun request(
        id: String,
        deviceId: String,
        iid: String,
        cdid: String,
        now: Long,
        egdi: String? = null,
    ): Request {
        require(listOf(id, deviceId, iid).all { it.matches(Regex("[0-9]{1,30}")) })
        val query = deviceQuery(deviceId, iid, cdid, egdi)
        val payload = JSONObject("""{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}""")
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val body = output.toByteArray()
        val signed = signer.signPlayback(ENDPOINT, query, body, now)
        val headers = signed.headers + mapOf("User-Agent" to
            "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)",
            "Accept" to "application/json", "Content-Encoding" to "gzip")
        return Request(signed.url, headers, body)
    }
}
