# ReelGenerator — Phase 1

Native Kotlin / Compose app for Android 10+. Select a video, choose Lifestyle, Motivation, Travel or Humor, and generate one muted MP4 entirely on the phone.

## Phase 1 scope

- Android document picker with persisted access to one video.
- Media3 Transformer: first up to 12 seconds, 1080 × 1920 center crop without stretching, 30 fps requested, H.264, no audio.
- Category-specific static test text, burned in with a dark panel and safe margins.
- Private temporary export, then pending MediaStore publication to `Movies/Upload Reels`. Failed copies are deleted; success requires a nonempty MediaStore item.
- Progress, cancellation, rotation support, playback and Android sharing.

Keep the app open while rendering. AI, folder libraries, Room, five-reel batches and background generation are intentionally deferred. Static text does not analyze footage. The app has no Internet or broad storage permission.

## Install and test from an Android phone

1. Open this GitHub repository in your browser and sign in.
2. Open **Actions → Android APK**, then the latest successful run for `codex/phase-1` (or `main` after merging).
3. Download the **ReelGenerator-debug** artifact. Use desktop-site mode if the artifact section is hidden.
4. Extract the ZIP in Files, tap `app-debug.apk`, and allow **Install unknown apps** for Files when prompted. Install and open ReelGenerator.
5. Select a locally stored landscape video at least one second long. Choose Travel and Generate. Keep the app open.
6. Tap **View Reel**. Confirm vertical crop, readable text, silent audio, and duration at most 12 seconds. Open Gallery → Albums → **Upload Reels** and play the saved MP4.
7. Repeat with portrait and square sources and all four categories. Test cancellation and retry, rotation during rendering, and selection after reopening.
8. Test deleted/revoked sources, corrupt video, low storage and process interruption. Expect an error or retry prompt, never false success or an incomplete visible video.
9. Use Share to send to Instagram if installed, review and add music. Nothing is posted automatically.

A debug APK is for testing. No signing secrets are committed. An older APK signed with a different key must be uninstalled first. Artifacts expire under GitHub's retention settings; rerun the build if needed.

## Build / Codex Cloud

Use JDK 17 and Android SDK platform 35 / build-tools 35.0.0. Set `ANDROID_HOME` or untracked `local.properties` with `sdk.dir`, accept SDK licenses and run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows: use `gradlew.bat`. APK: `app/build/outputs/apk/debug/app-debug.apk`. Test/lint reports: `app/build/reports`.

GitHub Actions runs these checks on pushes to main/codex branches and PRs into main, then uploads **ReelGenerator-debug** and **verification-reports**. Once this workflow is on the default branch, **Run workflow** supports manual builds. Codex Cloud needs an Android SDK and dependency network access during setup; normal app operation is offline.

## Known limits and verification

Physical-device export/Gallery acceptance has not yet been run. A CI pass alone does not verify hardware codec behavior. Record phone model, Android version, source codec/dimensions and each acceptance result before proceeding to Phase 2.

Rendering survives rotation but is not a foreground service and does not resume after process death. Retry after interruption. Abrupt termination can leave private cache files or pending MediaStore rows; Android manages their eventual eviction/expiry. Encoder/HDR support varies by device and unsupported footage may fail. Source selection is retained using persisted URI permission; some document providers do not support it.

Implementation follows the [Android Media3 Transformer documentation](https://developer.android.com/media/media3/transformer/getting-started).
