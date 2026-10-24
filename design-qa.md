# Hongguo 0.7.1 UI design QA

Date: 2026-10-25
Scope: implement the selected first bottom-navigation design, fix player safe-area behavior, add temporary hold acceleration and repair startup update checks. The established 0.7.0 page style and existing features remain in place. The complete prior-page QA report is retained at tag v0.7.0.

## Source visual truth

- Selected first navigation: H:/oCode/Project/Honguo/output/design/bottom-navigation-v1/light-edge.png
- Selection and specifications: H:/oCode/Project/Honguo/output/design/bottom-navigation-v1/design-spec.json
- Existing page references: H:/oCode/Project/Honguo/output/design/high-fidelity-v1/{home,library,player}.png

The selected design is a full-width warm-white bottom surface with one subtle top divider, four equally spaced outlined icons, labels and a short underline under the active label. Native Material outlined icons preserve a consistent icon family; the generated sketches have slightly different strokes. There is no floating capsule or shadow.

## Viewport, state and normalization

Default emulator: 1080 x 2400 physical pixels, density 420 dpi, font scale 1.0. The real home capture shows all four navigation targets at y=2165..2337, with the OS navigation inset below. The app bar has a minimum 64 dp height and can grow with font size.

Small-screen check: 720 x 1600, density 360 dpi, font scale 1.5 (320 dp wide). All four navigation targets remain visible and their labels and active underline fit. Their bounds are y=1374..1546. The temporary device overrides were reset after capture.

The navigation comparison crops the reference to (0,842,1448,1086) and the native capture to (0,2164,1080,2337), including the divider and excluding the OS navigation inset. The implementation crop is uniformly normalized to the reference width of 1448 pixels; it is not stretched to force a matching height. Both are shown together in compare-nav.png.

## Visual evidence

All current evidence is under H:/oCode/Project/Honguo/qa/ui071/:

- compare-nav.png: selected reference and native bar together, width normalized with aspect ratios preserved.
- home.png, discover.png, mine.png: full native pages with the new shared navigation.
- home-small-font.png: 320 dp / 1.5 font scale, navigation and main actions visible.
- qa-player-controls.png: player information and controls in the stable safe area.
- qa-player-hold-speed.png: temporary speed indication under the top row, away from subtitles and bottom controls.
- qa-player-speed-sheet.png: existing speed control retained.

README screenshots were refreshed from the current native captures. Movie stills, API cover order and real watch history are content differences, not static visual fixtures.

## Findings and fixes

1. [P2, fixed] The previous bottom bar did not match the selected light edge-attached design.
   Replaced it with a warm-white surface, subtle divider, equal-width tabs, unified outlined icons and active underline. Touch selection, keyboard dismissal, accessibility roles and the existing library destination are preserved.

2. [P2, fixed] Hiding the status bar removed its inset, allowing the player top row to occupy the status bar region when transient bars appeared.
   The row now uses statusBarsIgnoringVisibility union displayCutout, limited to top and horizontal edges. Insets use the maximum per edge instead of adding duplicate top padding. Portrait/landscape status-bar show/hide checks and an asymmetric cutout fixture confirm stable, reachable controls. Closing sheets and returning to the foreground restore immersive chrome.

3. [P2, fixed] Temporary acceleration required a separate nonpersistent gesture state.
   Holding the left half of an already-playing video sets the active player to 2x. Release, cancellation, sheet opening, backgrounding, pause, end, disposal and episode changes clear the override. The saved speed is untouched, and a paused long press does not resume playback. A small temporary indication appears under the top row.

4. [P2, fixed] Startup checks could be blocked by a 24-hour attempt timestamp, including manual checks and failures.
   The startup ViewModel now checks once per cold-start instance when enabled. Ordinary resume and configuration recreation reuse it; explicit disable is respected. A manual tap promotes an existing automatic request without restarting it. Silent no-update/failure behavior, source cancellation and stale-result fencing are retained.

No actionable P0/P1/P2 visual findings remain in the inspected states.

## Validation and practical limits

- Gradle build, JVM checks and Android lint completed: build.log and final-checks.log.
- Device gesture, safe-area, navigation, local-library and settings runs completed: device-focused.log.
- A live 王妈 run confirmed actual decoder output, temporary 2x application/restoration, paused behavior, unchanged saved speed, seek, vertical episode switching, recreation, auto-next and the episode picker: device-live.log.
- Production public ViewModel factory wiring confirmed that a recent attempt does not suppress a cold-start check and an explicit disabled preference prevents it: startup-wiring.log.
- Both in-app proxy manifest routes passed in the live UI run. Publication delivery checks are recorded separately under qa/verify-071/.
- Cover installation from 0.7.0 to 0.7.1 preserved the exact local-library snapshot: library-before-upgrade.xml and library-after-upgrade.xml.
- APK metadata: com.hongguo.android, versionName 0.7.1, versionCode 12, minSdk 26. The verified certificate matches release-signing.sha256. APK SHA-256: 2f2b6f744085ae648acf1cf13719326daada2d0c06b1b7d11affb6660f5c3e1b.
- Personal owner-authenticated cloud operations were outside this change. Existing account/confirmation boundaries remain; the user should check real account behavior on the handset. OEM-specific system bars and extreme accessibility settings beyond the inspected sizes still require handset acceptance.

## Implementation checklist

- [x] First navigation variant implemented and compared with its source
- [x] Native icon family, typography, colors and equal tab spacing
- [x] Stable player top and cutout safe area
- [x] Temporary hold speed with restore and interaction coverage
- [x] Startup check policy and real factory wiring
- [x] Normal and large-font visual capture
- [x] Build, live playback and compatible installation checks
- [x] README captures refreshed for this version

final result: passed
