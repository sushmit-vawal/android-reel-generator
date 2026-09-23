# Phase 1 verification

Local validation on 2026-09-23:

- `testDebugUnitTest lintDebug assembleDebug`: successful.
- Four unit tests passed; no failures or skipped tests.
- Lint: no errors. Advisory warnings include newer dependency versions, backup configuration and KTX suggestions.
- APK signature verified using Android `apksigner`.
- APK metadata verified: `com.reelgenerator`, version `0.1.0`, minimum API 29, target API 35.
- No Android devices were connected (`adb devices` returned an empty list).

The owner reported successful physical verification on an Android phone on 2026-09-23 and authorized Phase 2. PR #1 was marked ready and merged into main. Device model, Android version and individual test results were not provided, so no additional measurements are claimed. These unit tests cover export policy; they do not execute Android codecs.

The local machine used Gradle 8.11.1 and JDK 20; GitHub Actions is configured for JDK 17. Java dependency downloads on this machine required the Windows trusted certificate store. This environment-specific setting is not part of the repository.

## Phase 2

Implemented on `codex/phase-2`. Local `testDebugUnitTest lintDebug assembleDebug` passed on 2026-09-23, with 17 passing tests and no lint errors. The tests cover export policy, exactly-five sequential processing, small-library reuse, corrupt-source fallback, bounded failures, cancellation, saved-slot recovery, distinct captions, Room reopening, rescan metadata/usage retention, folder eligibility, overlapping-tree deduplication and idempotent completion/cancellation checkpoints. Room schema version 1 is checked in; Phase 1 had no Room database to migrate.

Physical Phase 2 acceptance is pending. Follow the current README checklist for multiple folders, persisted Android URI access, five-reel batches, background/cancellation and error handling. Phase 1 phone acceptance does not establish these new behaviors. Robolectric exercises the database, not actual SAF providers, hardware codecs, Android service quotas or Gallery applications.
