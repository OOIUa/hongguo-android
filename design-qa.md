# Hongguo 0.7.0 UI design QA

Date: 2026-10-25
Scope: native Android implementation of the selected home, library and player designs, with a shared visual system on the other existing screens. This is a local test APK, not a GitHub publication.

## Source visual truth

- H:/oCode/Project/Honguo/output/design/high-fidelity-v1/home.png
- H:/oCode/Project/Honguo/output/design/high-fidelity-v1/library.png
- H:/oCode/Project/Honguo/output/design/high-fidelity-v1/player.png
- Design specifications: H:/oCode/Project/Honguo/output/design/high-fidelity-v1/design-spec.json

The generated examples are style and layout references. The APK renders real upstream covers, titles and episode counts, and actual local/account records. It does not ship a rasterized mock screen or seed example user history.

## Viewport, state and normalization

Reference PNGs: 909 x 1731 pixels, app content only.

Content pages were captured on an Android emulator at 1080 x 2190, density 443 dpi, font scale 1.0. System bars occupy approximately 66 pixels at either edge. Cropping to (0, 66, 1080, 2124) gives 1080 x 2058 of app content, then a uniform width normalization to 909 x 1731. This is approximately a 390 x 743 dp app viewport. These dimensions describe native Android layout, not a browser or CSS device scale.

Player capture: 1080 x 2058, density 443 dpi, immersive system bars hidden. The entire app viewport was normalized to 909 x 1731. Its real Media3 video surface retains FIT instead of stretching/cropping the film to imitate the generated still.

Additional checks: default emulator 1080 x 2400 at 420 dpi/font scale 1.0; 720 x 1600 at 360 dpi/font scale 1.5, including the software keyboard.

## Full view comparison evidence

Source and implementation were opened together in each comparison image:

- H:/oCode/Project/Honguo/qa/ui070/compare-home-final.png
- H:/oCode/Project/Honguo/qa/ui070/compare-library-final.png
- H:/oCode/Project/Honguo/qa/ui070/compare-player-final.png

Implementation captures:

- home-final.png, library-final.png, player-final.png
- discover-final.png, search-final.png, detail-final.png
- mine-final.png, settings-final.png, account-final.png, library-cloud-final.png
- player-favorites-verified.png
- home-small-font-fixed.png, library-small-font-fixed.png, search-keyboard-small-font.png
- home-default-final.png

All implementation filenames above are in H:/oCode/Project/Honguo/qa/ui070/.

The home reference's first items and the local library's example rows differ from the live API and real saved data. Player comparison uses the same drama, a different episode and timestamp. These content differences are expected and were not treated as layout errors.

## Focused comparison evidence

- Initial full view: compare-home-initial.png showed excessive vertical spacing and a taller default bottom bar.
- discover-metadata-crop.png showed clipping of the first digit at the lower left of each poster item.
- discover-metadata-fixed-crop.png shows the complete digits after removing the outer rounded clip; the cover retains its own rounded corners.
- player-favorites-verified.png shows the local and account favorite actions in one reachable sheet. Local add/remove were exercised and restored.
- Large font before/after: home-small-font.png and library-small-font.png versus home-small-font-fixed.png and library-small-font-fixed.png.
- search-keyboard-small-font.png confirms that the search field, clear action and submit button remain reachable with the keyboard visible.

## Comparison history and fixes

1. [P2, fixed] Homepage information density diverged from the selected mock. The first poster row started too low.
   Fix: compact the brand/search/continue/feed spacing, use consistent poster typography and a compact bottom bar for normal font size. Final same-viewport comparison is compare-home-final.png.

2. [P2, fixed] Rounding the whole poster column clipped episode-count digits at the bottom-left corner.
   Fix: keep rounding on the cover only. Before/after focused evidence is discover-metadata-crop.png and discover-metadata-fixed-crop.png.

3. [P2, fixed] At 320 dp and font scale 1.5, the brand mark clipped and a side-by-side resume action squeezed the title and watch position.
   Fix: keep the typographic brand mark at its visual logo size, stack the primary action below the text on small/large-font layouts, constrain search placeholders to one line, and increase the bottom bar height for large fonts. Fixed captures and the large-font navigation/library run confirm reachable controls.

4. [P2, fixed] Returning from an account page opened from search overwrote the search origin, creating an account/search back loop.
   Fix: separate forward navigation from restoring the recorded destination. NavigationFlowTest covers this route and the library source return.

5. [P2, fixed] Content list scroll positions were discarded when opening another page.
   Fix: use a page-level SaveableStateHolder, keyed by tab or drama ID.

6. [P2, fixed] Selected favorite text in the dark sheet used a low-contrast orange.
   Fix: white action text and a lighter selected text accent in the dark sheet, with a compact white account action.

No actionable P0/P1/P2 visual findings remain in the inspected states.

## Required fidelity surfaces

- Typography: native Android sans/CJK fallback, shared weights, explicit line heights and no extra Android font padding. Content text uses 14-16 sp; secondary labels are at least 12 sp. Long real titles use ellipsis and remain accessible in details. Large-font primary actions are stacked instead of squeezing text.
- Spacing/layout: 20 dp page margins, consistent gaps, rounded covers and restrained surfaces. Home, discovery, library and forms share the same rhythm. Normal and large-font navigation both remain visible.
- Colors/tokens: canvas #FCF8F4, text #292421, secondary #6B625C, action #C94426, divider #E5DCD5. Player uses dark surround with white controls and orange progress.
- Images/assets: Coil loads real upstream artwork, with rounded/cropped covers in content views. Media3 preserves the full movie frame with FIT. Material outlined icons form one consistent family. No invented ratings, heat counts, VIP/download/cast/comment capabilities are displayed.
- Copy/content: bottom navigation is 首页/发现/片单/我的. Search is a separate page. 本机 and 红果账号 are explicit sources. History/favorites are separate local tabs; cloud history/favorites/reservations retain the existing account boundaries.
- States: loading, empty, error/retry, local management/clear confirmation, signed-out cloud status, login methods, system settings, update dialog, player paused/playing/seek/speed/episode picker and favorite sheet were checked.

## Intentional native adaptations

- A 管理 action preserves existing single-item deletion and clearing without showing destructive controls in the normal library list.
- The local store has elapsed position but no authoritative per-episode duration. Home/library show the actual episode and elapsed time and omit a fabricated percentage stripe.
- The player exposes one favorite sheet containing local and account actions; account owner checks and write confirmation are preserved.
- Minimum readable native type and touch behavior take priority over the generated reference's occasionally smaller raster text. At the compact viewport, the second poster row's metadata can require a short scroll.
- Native system bars and keyboard are OS-owned. They were excluded from app-content fidelity comparisons.

## Functional and packaging evidence

- Final Gradle build and Android lint completed successfully: qa/ui070/final-build.log.
- Existing JVM tests were run after the code changes; no new logic failure was reported.
- Navigation, local library, settings, account-entry and seek/accessibility controls passed in the recorded device runs.
- The 320 dp/1.5 font-scale navigation and library run passed: large-font-ui-tests.log.
- Live discovery/filtering passed. A live 王妈 run covered actual decoder output, pause/resume, speed persistence, seek, recreation, vertical switching, automatic next episode and the episode picker: final-media-tests.log.
- Update proxy selection and both public manifest routes passed on the later run. An earlier backup-route failure is retained in device-ui-tests.log.
- Earlier 总裁是我前女友 runs failed in preparation, once on reopening and once initially. The existing generic error does not identify the precise cause, so those failures are not claimed to be diagnosed or fixed by this UI work. See device-live-tests.log and final-device-tests.log.
- No personal account credentials were supplied for QA. Live owner-authenticated cloud reads/writes and cross-device progress still require the user's own device/account acceptance. Signed-out UI and existing owner/confirmation guards were checked.
- Cover installation from 0.6.3 retained the exact local-library preference snapshot: library-before-upgrade.xml and library-after-upgrade.xml.
- Application ID remains com.hongguo.android; version 0.7.0/versionCode 11, minSdk 26. Signing certificate matches release-signing.sha256. Final artifact metadata is recorded in package-metadata.json.
- APK source snapshot includes the GPL license and third-party notices; no signing private key, local SDK config, build cache or private account data is included.

## Follow-up polish

- Material icon strokes differ slightly from the generated sketches.
- Exact movie stills, poster order and watch positions vary with content and playback.
- Additional OEMs and extreme accessibility sizes beyond the inspected configurations should be checked on the user's handset.

## Implementation checklist

- [x] Home/library/player implementation and remaining-page visual system
- [x] Existing functionality and local/cloud separation retained
- [x] Back-navigation and scroll-state fixes
- [x] Focused visual fixes and recapture
- [x] Small-screen/large-font/keyboard checks
- [x] Cover-install, package identity and certificate checks
- [x] Local test APK and matching source snapshot

final result: passed
