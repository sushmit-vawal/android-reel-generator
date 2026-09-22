# Phase 1 verification

Local validation on 2026-09-23:

- `testDebugUnitTest lintDebug assembleDebug`: successful.
- Four unit tests passed; no failures or skipped tests.
- Lint: no errors. Advisory warnings include newer dependency versions, backup configuration and KTX suggestions.
- APK signature verified using Android `apksigner`.
- APK metadata verified: `com.reelgenerator`, version `0.1.0`, minimum API 29, target API 35.
- No Android devices were connected (`adb devices` returned an empty list).

Physical-device installation, actual Media3 encoding, crop/overlay appearance, cancellation, and Gallery playback remain **unverified**. Follow the phone acceptance steps in the README and record results before Phase 2. These unit tests cover export policy; they do not execute Android codecs.

The local machine used Gradle 8.11.1 and JDK 20; GitHub Actions is configured for JDK 17. Java dependency downloads on this machine required the Windows trusted certificate store. This environment-specific setting is not part of the repository.
