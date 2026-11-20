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

    /** video_tab 的 v5 无正文 GET：查询参数与头的形态对齐 hongguo-drama 7.7。 */
    @JvmStatic
    fun requestVideoTab(id: String, deviceId: String, iid: String, cdid: String, now: Long): TabRequest {
        require(listOf(id, deviceId, iid).all { it.matches(Regex("[0-9]{1,30}")) })
        val values = linkedMapOf(
            "aid" to "8662", "app_name" to "novelread", "version_code" to "74332",
            "version_name" to "7.4.3.32", "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
            "iid" to iid, "cdid" to cdid, "device_id" to deviceId,
            "video_id" to id, "video_id_type" to "0", "device_level" to "1",
            "need_all_video_definition" to "1", "need_mp4_align" to "0", "use_os_player" to "0",
            "video_platform" to "1024", "source" to "0", "from_video_id" to "",
        )
        val signed = signer.signPlaybackGet(TAB_ENDPOINT, values, now)
        return TabRequest(
            signed.url,
            signed.headers + mapOf("User-Agent" to TAB_UA, "Accept" to "application/json"),
        )
    }

    @JvmStatic fun request(id: String, deviceId: String, iid: String, cdid: String, now: Long): Request {
        require(listOf(id, deviceId, iid).all { it.matches(Regex("[0-9]{1,30}")) })
        val query = linkedMapOf("aid" to "8662", "app_name" to "novelread", "version_code" to "74332",
            "version_name" to "7.4.3.32", "channel" to "vivo_8662_64", "device_platform" to "android",
            "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
            "iid" to iid, "cdid" to cdid, "device_id" to deviceId)
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
