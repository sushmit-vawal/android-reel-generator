# Phase 3A — ReelPlan and composition engine

## Repository inspection and incremental plan

The existing app is a single Android module. Compose `MainActivity` observes `ReelViewModel`; the view model manages persisted SAF trees, settings and a unique WorkManager request. `FolderScanner` recursively updates Room's folder/video membership. `BatchWorker` renders sequentially, retains cancellation intent and completed outputs, and holds a mutex until renderer cleanup ends. `ReelExporter` owns Transformer, temporary files and pending MediaStore publication. These working components are retained.

The owner verified Phase 2 on an Android phone on 2026-09-23. PR #2 was marked ready and merged before creating `codex/phase-3a`.

The staged plan is: **3A** validated plan models, duration-based local planning, sequential composition and independent text timing; **3B later** cached clip analysis/provider interfaces, candidate generation, scoring and concept diversity; **3C later** typography presets, adaptive readability and visual polish. No 3B, 3C or 3.5 implementation is included here.

## Architecture changes

`SourceClipReader` reads duration/dimensions, not semantic tags. `LocalReelPlanner` implements `ReelPlanner` and produces a `ReelPlan`. The plan includes category/style, concept, hook/payoff, ordered source trims, output positions, independent text beats, typography, pacing and versioned generation metadata. Quality score is nullable and remains unset; it is not an AI/virality estimate.

The local planner reuses the verified category presets. It varies one/multiple clips and one/multiple beats according to the slot, available duration and reading time. It normally targets eight seconds, allows short readable material, and supports validated timelines up to twenty seconds. Duration-based offsets avoid always starting at source time zero. Neither offsets nor source selection claim scene or semantic understanding.

`ReelCompositionFactory` maps the plan to one `EditedMediaItemSequence`: each source is trimmed, muted and cropped to 1080×1920. Composition-level `TimedTextOverlay` uses output timestamps; one caption may cross a cut, or several beats may appear over one clip. Only one overlay bitmap is retained, redrawn when the active beat changes. Media3 owns texture/overlay release. The existing single-clip exporter overload is a compatibility adapter to a legacy plan.

The worker saves a plan before rendering, resumes valid pending plans, records all source segments and usage, and retains stable export identifiers for interrupted publication recovery. Planning/composition failures can fall back to a single clip. Cancellation is never converted into fallback. Existing bounded retries, partial result reporting, Gallery publication, progress and batch mutex remain in place.

## Added files

- `planning/ReelPlan.kt`: domain models, timing validation and reading-duration heuristic.
- `planning/LocalReelPlanner.kt`: planner interface/request and local fallback implementation.
- `planning/SourceClipReader.kt`: background metadata reader.
- `planning/ReelPlanCodec.kt`: versioned JSON snapshots using Android's built-in JSON library.
- `rendering/ReelCompositionFactory.kt` and `rendering/TimedTextOverlay.kt`: plan-to-Media3 mapping and global timed text.
- `ReelPlanTest.kt`, `ReelCompositionTest.kt`, and `androidTest/.../ReelExportDeviceTest.kt`: planning, serialization, composition and real-export checks.
- Room schema `app/schemas/com.reelgenerator.data.ReelDatabase/2.json`.
- `.github/workflows/render-tests.yml`: accelerated API 29 emulator tests and rendering evidence artifacts.

## Modified files

`BatchWorker.kt`, `BatchEngine.kt`, `ReelExporter.kt`, `data/ReelDatabase.kt`, `MainActivity.kt` (one explanatory sentence), `ReelDatabaseTest.kt`, `app/build.gradle.kts`, README and validation documentation. Folder scanning, persisted grants, category controls, view-model scheduling and the existing APK workflow are reused.

## Database, permissions and dependencies

Room **1 → 2** adds nullable `GeneratedReel.planJson` and a `GeneratedReelSegment` table with a reel foreign key and source index. Migration is explicit and non-destructive. Existing results have null plans and retain their original URI/caption. Folder grants/settings remain untouched. Completion updates every distinct segment source once, with the legacy primary source as a fallback. The stored JSON provides an internal trace without cluttering normal UI.

No new application permissions or production dependencies. Instrumentation adds `androidx.test:runner:1.6.2` and `androidx.test.ext:junit:1.2.1`. Existing Media3 1.6.1, Room 2.7.2 and WorkManager versions remain pinned. APK version: `0.3.0-a`, code 3.

## Verification

Local build command:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

32 local tests pass, including all 17 existing Phase 1/2 tests. Additional tests cover four structure combinations, nonzero trims, contiguous timelines, reading duration, text boundaries/gaps, invalid plans, serialization, composition audio/crop configuration, overlay bitmap reuse, Room migration/data retention and multi-source usage accounting. Lint has no errors; advisory dependency/KTX/backup warnings remain.

The instrumentation suite generates synthetic landscape red and portrait blue videos on-device, then exercises the legacy single-clip route, multiple beats over one clip, one beat across multiple clips, independent text/cut times, cancellation and a real five-reel batch. It checks MP4 dimensions/duration/audio, actual frame colors/text, MediaStore readability and temporary-file cleanup. It deletes only its own Gallery entries and saves test MP4 copies as CI evidence. See the PR checks for the actual emulator result; a built test APK is not evidence that those tests ran.

## Phone acceptance procedure

1. Download **ReelGenerator-debug** from the successful 3A APK workflow; extract and install `app-debug.apk`. Keep a same-signature Phase 2 installation when testing an in-place upgrade. Fresh CI debug signers may differ: uninstalling to resolve a signature mismatch resets app state, so it does not test migration. Automated migration tests cover the database upgrade separately.
2. Confirm folders, toggles, category/style and existing results survive a same-signature update. Rescan, add/remove/disable a folder, and verify original footage is untouched.
3. Use at least three clearly different local videos, each 15–30 seconds long. Include landscape, portrait and square sources. Choose Travel and Generate.
4. In the normal five-reel batch, expect single-clip/single-text, single-clip/sequential-text, multi-clip/continuous-text and multi-clip/sequential-text examples. Limited footage or fallback can reduce structural variety. Play all outputs in Gallery and check cuts, nonzero trims, vertical framing and readable captions.
5. Specifically watch text across a cut: it should not reset. Check later text beats appear at their intended times instead of all at once. Listen for silence. Check no blank/green frames or stretched source images.
6. Repeat with a one-video library and all categories/Humor styles. The one-source path must still work; a very short clip can be rejected when a new caption cannot be read comfortably. Existing pending legacy jobs retain their prior short-source behavior.
7. Rotate/background the app, cancel during a later reel, and immediately Generate again. Finished outputs must remain, and expensive encodes must not overlap. Check revoked permissions, corrupt clips, low storage and process interruption.
8. Record phone/Android/source formats/results in `docs/VALIDATION.md`. **Do not start 3B while 3A device acceptance is unstable.**

## Known limits and fallbacks

No AI, semantic scene analysis, quality ranking, new generative text or trend provider is claimed. Preset captions may repeat across batches; use a category-appropriate library. Metadata reads are capped at forty candidates per execution and performed off the main thread; metadata is kept for the batch rather than advertised as cached visual analysis. Native document-provider calls may outlast a coroutine timeout before returning.

Keyword emphasis is represented in the model but not visually applied yet. The existing clean font/black panel is deliberately retained until 3C. Text sizing has a fit guard, not adaptive scene-aware readability. Real hardware codecs/OEM background policies can differ from the emulator. Clips shorter than the reading budget may yield a partial batch; the UI reports the actual saved count.

## GitHub Actions

The existing **Android APK** workflow remains the phone-download path. **Android Render Tests** adds an API 29 emulator using KVM/SwiftShader and a 30-minute timeout. It uploads `rendering-verification` with instrumentation reports and synthetic MP4 evidence. Both workflows support manual dispatch once on main. No signing secrets or AI keys are added.
