# ReelGenerator — Phase 2

Native Kotlin / Compose application for Android 10+. Select source folders once, choose Lifestyle, Motivation, Travel or Humor, and tap **Generate** for five locally rendered, single-clip reels.

Phase 1 was physically verified on the owner's Android phone and merged via PR #1 on 2026-09-23. Phase 2 adds folder libraries and sequential batches; it does not add AI or multi-clip composition.

## What Phase 2 provides

- Add multiple Android source folders using the Storage Access Framework, one picker selection at a time. Read access survives app restarts/reboots where the provider permits.
- Manage folders: add, remove, enable/disable, rescan, video counts, scan times and per-folder access errors. Removing a folder never deletes its source files.
- Recursive scans on every Generate, with overlapping folders deduplicated. Scan results update metadata without resetting use counts; inaccessible/disabled folders are excluded.
- Room stores folders, videos, folder membership, settings, batch progress and reel results. The versioned schema is checked in.
- Exactly five successful reels are targeted, sequentially. Fresh footage is preferred, and small libraries can reuse clips. Bad sources are skipped, with at most 20 attempts per execution. Partial results report their actual count.
- Humor style: Auto, Funny or Dark. Each batch uses five different short preset captions. Dark humor is cynical, non-harmful entertainment.
- WorkManager foreground generation with a progress notification, cancellation, persisted completed results and process-interruption recovery. Completed Gallery outputs are recovered by stable identifiers rather than rendered twice after a checkpoint interruption.
- Each output uses the Phase 1 export path: up to 12 seconds, 1080×1920 center crop, H.264, 30 fps requested, no source audio, burned-in readable text, and MediaStore publication to `Movies/Upload Reels`.
- View/play each completed reel, share individually, or share the batch through Android's share sheet. Nothing is posted automatically.

## Install from an Android phone

1. Sign in to GitHub in your phone browser, open **Actions → Android APK**, and choose a successful run on `codex/phase-2` (or `main` after merge).
2. Under Artifacts download **ReelGenerator-debug**, extract its ZIP, and tap `app-debug.apk`. Allow **Install unknown apps** for your browser/Files app if prompted.
3. The APK requires Android 10 or newer. Debug builds from different runners can have different signing keys: if Android refuses an update because of a signature mismatch, uninstall the previous debug app, then install this one. Uninstalling resets selected folders and app history; exported Gallery videos remain outside app-private storage.
4. Open ReelGenerator → **Select / Manage Folders → Add Source Folder**. Select a local video folder and approve access. Repeat for each additional folder. Android blocks some root/restricted directories; choose a permitted subfolder.
5. Return home, select a category (and Humor style if applicable), then tap **Generate**. Allow notifications if desired; declining does not prevent generation.
6. Wait for the completion count, tap **View Reels**, then play/share the results. Also verify all five videos in Gallery → Albums → **Upload Reels**.

## Required Phase 2 phone acceptance

- Select two folders containing different videos, including portrait, landscape and square sources. Confirm both counts and five finished outputs with different captions.
- Restart the app and reboot the phone. Confirm folders, enabled states and category/style remain saved, then generate again.
- Disable a folder and generate; none of its unique videos should appear. Enable it again. Remove a folder and verify original videos remain untouched.
- Add a new video and remove an old video using Files, then Rescan. Confirm counts update. Select a parent and child folder and confirm the same physical clip is not treated as two distinct videos.
- Try Lifestyle, Motivation, Travel, and Humor Auto/Funny/Dark. Each batch should have five captions; text remains preset, not footage-aware.
- Use only one valid source and verify five exports happen one after another. Try mixed valid/corrupt files, an empty folder, revoked access and low storage. Confirm accurate partial/zero results and no incomplete visible Gallery files.
- While generating, rotate, return home, and reopen the app. Cancel midway: completed reels must remain in Gallery and the result list. Try generation again.
- Test interruption/process recovery on the actual phone. Android scheduling, foreground-service quotas and OEM battery management can delay or stop work; do not assume uninterrupted background processing. Force-stopping the app requires reopening it.

Record device, Android version, source codecs and actual results in `docs/VALIDATION.md`. Do not proceed to Phase 3 before this acceptance path is checked.

## Build and GitHub Actions

Use JDK 17, Android SDK platform 35 and build-tools 35.0.0. Set `ANDROID_HOME` or untracked `local.properties` with `sdk.dir`, accept SDK licenses, and run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows: `gradlew.bat`. APK: `app/build/outputs/apk/debug/app-debug.apk`. Reports: `app/build/reports`. Room schemas: `app/schemas`.

GitHub Actions runs tests, lint and APK assembly for pushes to main/codex branches and PRs into main. It uploads **ReelGenerator-debug** and **verification-reports**. **Run workflow** supports manual builds from a selected branch now that the workflow is on main. Artifacts follow GitHub retention limits; rebuild when an artifact expires. No signing secrets are committed.

Codex Cloud needs an Android SDK and network access for build dependencies (including Robolectric's Android test runtime). Routine generation has no Internet permission and runs on the phone.

## Scope and limitations

Phase 2 intentionally uses preset captions, one source clip per reel and simple usage-based selection. It does not perform semantic matching, visual analysis, model downloads, AI text generation or multi-clip editing. Choose a library appropriate to the category. Captions can repeat across batches. Folder names are not presented as visual understanding.

The foreground worker uses `mediaProcessing` on Android 15+ and `dataSync` for older releases. Android still controls service/job time limits and background starts. Each export has a ten-minute timeout. Output publishing and Room checkpoints complete before honoring cancellation, so a reel that finishes during cancellation may still be saved. Completed results persist, including when the rest of a batch fails; the app shows the latest batch, and earlier outputs remain in Gallery.

Some cloud document providers may require their own network access. Select downloaded local footage for offline use. Metadata scanning is not visual analysis; frame/embedding caching starts in Phase 4. Device encoder support still determines which source formats can be exported.

References: [Media3 Transformer](https://developer.android.com/media/media3/transformer/getting-started), [WorkManager foreground workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).
