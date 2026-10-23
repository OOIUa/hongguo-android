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
