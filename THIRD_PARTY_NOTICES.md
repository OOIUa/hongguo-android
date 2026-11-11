# Sources and notices

This Android application is distributed under GPL-3.0-only. See LICENSE.

The protocol and media core are adapted from sunnysky123/hongguo-web:

- Java baseline: v1.0.7, commit a54a5de88c77ea81b8d5d39bdfec51cecebb40c3.
- Current behavior reference: v1.1.4, commit 71d25c2e23c2663d9bc7f1e52a4ed44fd878050b.
- Source: https://github.com/sunnysky123/hongguo-web
- Copyright (c) 2026 sunnysky123. The upstream MIT notice is preserved in app/src/main/assets/licenses/hongguo-web-MIT.txt.

Upstream PureSign, Sm3 and WebSource identify jackotom/hongguo-drama as an implementation source under GPL-3.0. These attribution comments are preserved, and the GPL-3.0 text is included in app/src/main/assets/licenses/GPL-3.0.txt.

- Source: https://github.com/jackotom/hongguo-drama

Android adaptations replace desktop configuration, console handling, HTTP service routing, process launching and FFmpeg invocation. The Android application calls the protocol and media core in its own process and reads prepared media as local files.

AndroidX, Kotlin, Kotlin Coroutines and Coil are used as build/runtime dependencies. Their original dependency metadata and license notices remain applicable. The Gradle wrapper is from Gradle 8.13.

Complete application source, build scripts and modifications accompany the test APK in this project directory. No signing private key, local SDK configuration or build cache is part of the source distribution.

## Personal account integration (0.6.0)

The native account protocol in `app/src/main/java/com/hongguo/account/protocol/` is adapted from:

- tsukiyo-zero/hongguoTV, commit `34c793055fa897075d9061dc7d62ee84031ef855` (2026-10-24), GPL-3.0-only.
  https://github.com/tsukiyo-zero/hongguoTV/tree/34c793055fa897075d9061dc7d62ee84031ef855/kotlin-tv
- Its original account device/login reference is charlie901208/HGTV, `feat/phone-login` (GPL-3.0-only), as identified in the upstream NOTICE.
  https://github.com/charlie901208/HGTV/tree/feat/phone-login
- Account endpoint and parsing references include pan8664716/HGTV, commit `ad69d589bbf472e72ce4f2e985a376f8a10bf99a` (GPL-3.0-only).
  https://github.com/pan8664716/HGTV/tree/ad69d589bbf472e72ce4f2e985a376f8a10bf99a
- The account request signer originated from the Kotlin port of woshishiq1/drpys, commit `22261adfa31435e3b3ef5a730b8a2a75bcaf1715`, GPL-3.0.
  https://github.com/woshishiq1/drpys/tree/22261adfa31435e3b3ef5a730b8a2a75bcaf1715

Original authors' copyrights and SPDX notices are retained. The Compose account UI, encrypted account state, lifecycle isolation and durable progress scheduling are adapted or implemented for this application. No private account samples, credentials, proprietary desktop binaries or unlicensed account implementations are bundled.

Additional runtime dependencies: OkHttp 4.12.0 and Okio (Apache-2.0), Bouncy Castle bcprov-jdk18on 1.79 (MIT). The upstream notices and license texts are included under `app/src/main/assets/licenses/` and in corresponding source. org.json, MockWebServer and kotlinx-coroutines-test are used only for JVM tests; Android supplies its built-in JSON implementation.

## Playback recovery (0.7.2)

On 2026-10-27 the upstream playback endpoint began rejecting per-request synthetic device IDs and signed requests. `app/src/main/java/com/hongguo/api/service/PlaybackDevice.java` (anonymous device registration via ttEncrypt) and the unsigned playback request / inline `video_list` handling in `AppSource.java` were synchronized from:

- tsukiyo-zero/hongguoTV, commit `388931852ddc614db8b00775a3dc54c5ede6307f` (2026-10-27), GPL-3.0-only — `kotlin-tv/core/.../DeviceRegister.kt` and `PlaybackDeviceSession.kt`.
  https://github.com/tsukiyo-zero/hongguoTV/tree/388931852ddc614db8b00775a3dc54c5ede6307f/kotlin-tv
- jackotom/hongguo-drama 3.9, commit `5febca2e5fca0fff4f4a5f21db50f5de75cbeab6` (2026-10-27), GPL-3.0-only — `src/native/playback-device.js` and `src/native/app-source.js`.
  https://github.com/jackotom/hongguo-drama/tree/5febca2e5fca0fff4f4a5f21db50f5de75cbeab6
- sunnysky123/hongguo-web 1.1.5, commit `79babdfefb6a80284e371b1ba14be897f7988c96` (2026-10-27), MIT — `server/src/service/playback-device.js` and `app-source.js`.
  https://github.com/sunnysky123/hongguo-web/tree/79babdfefb6a80284e371b1ba14be897f7988c96

Local changes: the registered identity (device_id / iid / cdid only) is kept in process memory and is fully separate from the personal account module; no device token, cookie or raw response is stored. Successful registration is shared by playback, prefetch and retries; a failed registration is reused only for a 30-second cooldown instead of until process restart, because an Android process may first play while offline and then stay resident.

## Playback source sync (0.7.10)

- `AppSource.java` playback request now sends `device_level=1` instead of 3. At level 3 the server delivers some episodes' 360P–720P renditions as bytevc2, which Android cannot decode, leaving only 1080P. Synchronized from tsukiyo-zero/hongguoTV, commit `ef61f68105e832fefe79baf069ca2feb2d11ebe6` (2026-10-29, TV 0.1.12), GPL-3.0-only — `kotlin-tv/core/.../ContentRepository.kt`.
  https://github.com/tsukiyo-zero/hongguoTV/tree/ef61f68105e832fefe79baf069ca2feb2d11ebe6/kotlin-tv
- An empty playback response (suspected rejected anonymous identity) now triggers one renewed device registration and a single retry; a second empty response, or a failed renewal, is reported as before. Synchronized from jackotom/hongguo-drama 5.0, commit `1fa218a196478c9bd3908bea9666bc23f23b921f` (2026-10-30), GPL-3.0-only — `src/native/app-source.js` and `src/native/playback-device.js`.
  https://github.com/jackotom/hongguo-drama/tree/1fa218a196478c9bd3908bea9666bc23f23b921f

Local changes: concurrent callers that see the same rejected identity share one renewal; renewal still honors the 30-second registration failure cooldown, and a cancelled playback thread does not retry.

## video_tab playback model (0.7.12)

On 2026-11-02 the `POST /novel/player/multi_video_model/v1/` endpoint began answering every request with HTTP 200 and an empty body. `AppSource.java` now first requests `GET /reading/bookapi/video_tab/multi_video_model/v1/` once (registered anonymous identity, unsigned, every `GetVideoModelRequest` field, `device_level=1`) and falls back to the previous flow when that fails. Synchronized from tsukiyo-zero/hongguoTV, commit `8fc02a4aa768` (2026-11-02, TV 0.1.15), GPL-3.0-only — `kotlin-tv/core/.../ContentRepository.kt` `tabVideoModel`.
  https://github.com/tsukiyo-zero/hongguoTV/tree/eddeb64e2436/kotlin-tv

Local changes: no registration cookie is sent (verified unnecessary on 2026-11-02). The endpoint serves bytevc2 below 1080P, which is filtered out as before, so playback is currently 1080P only.

The fallback's empty-response retry now renews the identity, waits 300 ms and retries once on `api5-normal-hl.fqnovel.com` over a fresh connection; if renewal fails it retries with the previous identity instead of giving up. Synchronized from jackotom/hongguo-drama 5.4, commit `93e694e3f4ab` (2026-11-02), GPL-3.0-only — `src/native/app-source.js` `fetchAppPlayUrl`. Its restored request signing is not adopted: hongguoTV reports that fully signed requests to the old endpoint are also answered with 0 bytes.
  https://github.com/jackotom/hongguo-drama/tree/93e694e3f4ab

## Related works, seasons and reservations

`app/src/main/java/com/hongguo/account/protocol/RelatedWorks.kt` (request parameters, cell parsing) is adapted from tsukiyo-zero/hongguoTV, commit `f22bb3f` (2026-11-03), GPL-3.0-only — `kotlin-tv/core/.../RelatedWorks.kt` and `ContentRepository.kt` `related`. The reservation request was already adapted from the same project's `CloudAccount.kt` `reserve`.
  https://github.com/tsukiyo-zero/hongguoTV/tree/f22bb3f/kotlin-tv

Local changes: content types 1 and 1002 are also accepted, because anonymous requests on 2026-11-04 returned them as a playable drama family. The next season is taken from the single gap in the season numbering, and autoplay only follows that result. Reservations are confirmed by a separate list read and never retried; no reservation has been submitted on a real account here.

## Website boards, suggestions, likes, watch records and account counters

`app/src/main/java/com/hongguo/account/protocol/WebsiteCatalog.kt` (rank routes, page data shape, suggestion request) is adapted from tsukiyo-zero/hongguoTV, commit `f22bb3f` (2026-11-03), GPL-3.0-only — `kotlin-tv/core/.../RankBrowser.kt`, `ComicRank.kt` and `ContentRepository.kt`. The like list and account counters follow charlie901208/HGTV `android/core/.../Account.kt` and `ContentRepository.kt`, GPL-3.0-only. The watch record request and fields follow jackotom/hongguo-drama 5.6, GPL-3.0-only — `src/native/account-session.js`.

Local changes: the rank payload is read with a small attribute parser instead of Jsoup, and the first page omits `?page=1` because the site redirects it. Like and watch record names come from the detail batch, because the list title is often a synopsis. Website requests never carry account cookies. Watch records and account counters are best effort and leave the existing lists unchanged when they fail.

## 猜你喜欢 (0.7.10)

`app/src/main/java/com/hongguo/account/protocol/GuessFeed.kt` (request parameters, card parsing) is adapted from tsukiyo-zero/hongguoTV, commit `97c5b1d15dc2233e53f6152918c7e3cc3bbebb84` (2026-10-30), GPL-3.0-only — `kotlin-tv/core/.../RecommendationFeed.kt`, whose endpoint path and card shape reference Xanerix/guoapp `native/core/provider_hongguo_recommendations.go`.
  https://github.com/tsukiyo-zero/hongguoTV/tree/97c5b1d15dc2233e53f6152918c7e3cc3bbebb84/kotlin-tv

Local changes: signed out, the feed is requested anonymously without any cookie or account device; signed in (verified app account only), it carries the account cookie and that account's registered device_id / iid. The request uses the account transport (no redirects, no cookie jar), so the cookie cannot be forwarded to another host. The server session and offset are kept in memory per browse round and discarded on login, logout or account switch. No exposure, click or completion events are reported, and whether the signed-in results match the official App's 猜你喜欢 for the same account has not been verified on a real account.

## 猜你喜欢 110 recovery

A signed-in feed request no longer carries the account's device_id / iid: with them the server answers `110 ILLEGAL_ACCESS`, and with the login cookie alone `0 SUCCESS`. A non-zero `code` now retries the alternate hosts `api5-normal-hl` and `api5-normal-lf` from offset 0 without the old pagination session. Synchronized from tsukiyo-zero/hongguoTV, commits `4131e40`, `6263b54` and `500dc97` (2026-11-03, TV 0.1.17–0.1.19), GPL-3.0-only — `kotlin-tv/core/.../RecommendationFeed.kt`.
  https://github.com/tsukiyo-zero/hongguoTV/tree/f22bb3f/kotlin-tv

Local changes: the signed-in cookie is the account's own login cookie, without the registration cookies and `install_id` that other app requests add, because that is the form hongguoTV measured. The retry also runs on the first page, where upstream reports the error at once when it holds no session. HTTP and network failures do not rotate hosts. Only the three hosts' anonymous responses were checked (2026-11-04, `code` 0 on each); the signed-in behavior has not been verified on a real account here.

The feed offset now advances by the rows the server sent instead of by one, because the server counts its offset in rows. Taken from tsukiyo-zero/hongguoTV PR #59, still open on 2026-11-04, GPL-3.0-only. Rechecked with anonymous requests on 2026-11-04: six batches stepping by one returned 11 unique titles out of 33 rows, and stepping by rows returned 33 out of 33. The offset limit rose from 100 to 600 to keep the round length.

## Playback source sync (ByteVC2 and inline fallback)

An episode that offers only ByteVC2 now reports that directly. Message and rationale follow jackotom/hongguo-drama 5.6, commit `eaae8da` (2026-11-04), and the per-episode codec finding in tsukiyo-zero/hongguoTV commit `9d26de6` (2026-11-02), both GPL-3.0-only.

Inline rows with neither a key nor a declared encryption state may now use the model's `fallback_api`. The fallback must match the video id and full duration (within 0.5 s), must not be lower quality, and must carry a key or declare itself unencrypted. Synchronized from jackotom/hongguo-drama 5.5, commit `4c0855d` (2026-11-03), GPL-3.0-only — `src/native/app-source.js`, with the alternate source parsing and offline cases credited there to PR #92.
  https://github.com/jackotom/hongguo-drama/tree/552566d

Every playback model request now registers its own anonymous identity, because an identity may be single-use, and the registration failure cooldown is 10 seconds. Synchronized from jackotom/hongguo-drama 5.5 and 5.6, commits `4c0855d` and `eaae8da`, GPL-3.0-only — `src/native/app-source.js` and `src/native/playback-device.js`. If `video_tab` fails, the fallback request reuses that identity once before registering another on an empty reply.

## App passport SMS login

SMS login now uses the official App's passport channel on `security.snssdk.com`, with the fixed device from upstream's device table, because the website channel demands a slider before sending. Adapted from tsukiyo-zero/hongguoTV `kotlin-tv/core/.../PhoneLogin.kt` at `f22bb3f` (2026-11-03, TV 0.1.17–0.1.19), GPL-3.0-only, whose origin is charlie901208/HGTV `feat/phone-login`. The message for a session without a business account follows hongguoTV commit `d07b655` (TV 0.1.18).
  https://github.com/tsukiyo-zero/hongguoTV/blob/f22bb3f/kotlin-tv/core/src/main/kotlin/com/hongguotv/core/PhoneLogin.kt

Local changes: redirects are never followed, raw provider text is never shown, one attempt never accepts the same code twice from the user, and 1202 also allows typing a different code. A slider request opens a dialog that sends the user to the official login page to pass it manually; it is not solved or bypassed. The same protocol completed a real number login on iOS 0.1.11 in a simulator; Android has not been verified with a real account.

### Signed playback requests (2026-11)

`AppSource.signPlayback` and the route order in `AppSource.fetch` follow tsukiyo-zero/hongguoTV 0.1.22–0.1.25, commit `0c639bc` (2026-11-06), GPL-3.0-only — `kotlin-tv/core/.../ContentRepository.kt` `playbackRequest`, `Signer.signPlayback` and `PLAYBACK_STABILITY.md`. Since 2026-11-05 the model endpoints answer unsigned requests with an empty body; requests now carry the full signature, the registration cookies and `x-ss-stub`.
  https://github.com/tsukiyo-zero/hongguoTV/tree/0c639bc/kotlin-tv

### Signed recommendation feed and cell continuation (2026-11)

`GuessFeedClient`, `GuessFeedPager`, `AppSource.signRecommendation` and `PlaybackDevice.recommendation` follow tsukiyo-zero/hongguoTV TV 0.1.26, commits `3d319a6` and `62910e0` (2026-11-06), GPL-3.0-only — `kotlin-tv/core/.../RecommendationFeed.kt`, `PlaybackDeviceSession.kt`, `CloudAccount.kt` and `APP_RECOMMENDATION_REFERENCE.md`. The recommendation request carries a registered anonymous identity, the full App signature and the 7.4.3.32 context; when the entry declares `bottom_unlimited` with template 7, later batches use `bookmall/cell/change/v1/` with the server's `next_offset`; an explicit 110 on a signed request returns to the unsigned form. The 30-card cap is removed, a refresh keeps the recent dedupe window, and an empty account reply names its stage.
  https://github.com/tsukiyo-zero/hongguoTV/tree/70edaff/kotlin-tv

Local changes: playback still registers a fresh identity per model request, so the recommendation identity is a separate process-wide one; a registration failure falls back to the unsigned request instead of failing the feed, and an empty reply to a signed request drops that identity and rereads the entry on the next host. `filter_ids` sends at most the 120 most recent ids. Upstream's playback recovery cooldown and 300 ms settle delay address the TV client's shared rotating identity and remote-control browsing and are not ported. Anonymous live check on 2026-11-07: 10 pages, 86 distinct works, all signed; signed-in recommendations have not been verified with a real account.

### ByteVC2 compatibility request (2026-11)

When every rendition of the fallback model is ByteVC2, `AppSource.fallbackTrack` reads the same `fallback_api` once more with `codec_type=0&force_codec=h264`, the compatibility parameters of the official player. Upstream obtained HEVC (hvc1, 1254×720) this way for episodes that had offered only ByteVC2. Synchronized from tsukiyo-zero/hongguoTV TV 0.1.20, commit `265472f2d310f1f979dfcbb325e9453c6582fc96` (2026-11-04, issue #56), GPL-3.0-only — `kotlin-tv/core/.../ContentRepository.kt`. This supersedes the earlier note that no parameter changes an episode's codec.
  https://github.com/tsukiyo-zero/hongguoTV/tree/265472f2d310f1f979dfcbb325e9453c6582fc96/kotlin-tv

Local changes: inline models whose rows are all ByteVC2 also go to the fallback; the other query parameters are kept byte-for-byte; the answer is still filtered by its actual codec, and a replacement that declares another video id or a full duration more than 0.5 s apart is refused. Not verified against a live ByteVC2-only episode.

### Single-episode endpoint and player signature (2026-11-11)

From 2026-11-11 every multi-episode playback route (signed GET `video_tab`, signed POST, both hosts) answered 200 with an empty body; the placeholder `x-argus` / `x-ladon` produced by `PureSign` are no longer accepted. `core/PlayerSign.java` computes the full `x-gorgon`, `x-argus` and `x-ladon`, and `AppSource.fetch` first posts to `/novel/player/video_model/v1/` (7.3.5.32 profile, sorted query) with the request's registered identity, then tries the earlier routes. Synchronized from LiuWQ0809/hongguo-android, commits `3eaf813` (`PlayerSign.java`), `675f895`, `10bda56`, `1765bfa` and `5a29948` (2026-11-11), GPL-3.0-only; its signature algorithm cites Xanerix/guoapp `ad8dc125` (GPL-3.0).
  https://github.com/LiuWQ0809/hongguo-android/tree/1267cdb
  https://github.com/Xanerix/guoapp

Local changes: the single model goes through the same `parseModel` rules (ByteVC2, fallback, undeclared plain rows); its model error is reported ahead of empty answers from the old routes; no cookie is sent with the single request, as upstream verified. Anonymous live check on 2026-11-11: 8 episodes from two series, including previously ByteVC2-only episodes, were downloaded in full, decrypted and decoded (hvc1 720P/1080P).
