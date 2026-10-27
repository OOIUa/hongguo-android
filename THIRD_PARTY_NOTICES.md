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
