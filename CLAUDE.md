# CallCtrl

Phone app (dialer, call screen, call history) in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings. Becomes the default phone app through RoleManager and gets calls through an `InCallService`; Android's Telecom keeps placing calls and ringing.

## Layout

- `calls/CallService.kt`: the InCallService. `calls/CallManager.kt`: call state, controls, audio route and the proximity wake lock.
- `calls/InCallActivity.kt` + `InCallScreen.kt`: the call screen (shows over the lock screen, never behind the app lock).
- `calls/ScreeningService.kt`: the CallScreeningService (bound by Telecom for the default dialer, `BIND_SCREENING_SERVICE`). `data/screening/`: rules, matching and JSON/CSV import/export (pure Kotlin, unit-tested in `app/src/test`; no regex, emergency numbers never screened), `ScreeningStore` (`filesDir/screening.json`). UI in `ui/settings/ScreeningScreen.kt`.
- `calls/CallNotifications.kt`: CallStyle notifications (incoming with full-screen intent, ongoing) and missed calls. `calls/Receivers.kt`: notification actions and Telecom's missed-call broadcast.
- `data/Phone.kt`: placing calls, SIMs, role, number formatting. `data/CallHistory.kt`: call log and blocked numbers. `data/Contacts.kt`: contacts with numbers, caller lookup, T9.
- `recording/`: the call recorder. `shell/CaptureService.kt` is a Shizuku user service (own process, shell uid) that only captures call audio (VOICE_CALL, else VOICE_DOWNLINK + VOICE_UPLINK) into a pipe; its AIDL (`src/main/aidl/.../ICallCapture.aidl`) has start/stop/format/destroy only. `ShizukuGate.kt`: Shizuku state, permission, binding. `CallRecorder.kt`: follows CallManager's calls, decides, splits files, writes through `Segment`/`Encoders` (MediaCodec FLAC/AAC/Opus, own WAV and FLAC framing). `RecordingStore.kt`: `filesDir/recordings` + `<file>.json` sidecars, MediaStore copies, auto-delete. Pure logic (`CaptureFormat.kt`, `RecordingRules.kt`, `Containers.kt`) is unit-tested. UI: `ui/settings/CallRecordingScreen.kt`, `ui/recordings/RecordingsScreen.kt`, the bar in `InCallScreen.kt`.
- `ui/dialer`, `ui/recents`, `ui/people`: the three tabs. `ui/setup/Setup.kt`: role and permission onboarding.

## Hard rules

- **No `INTERNET` permission, ever** (the manifest removes it; CI fails the build if the APK has any network permission).
- **Google-free:** no Google libraries, services or SDKs, including transitive ones (CI checks `releaseRuntimeClasspath`). No WebView. Fonts are bundled.
- **Telemetry-free:** no analytics or crash reporting.
- arm64-v8a only. Every user-facing string goes in `strings.xml` (shared Ctrl strings in `strings_ctrl.xml`).
- Settings open only from the profile picture. No settings tab, menu or gear icon, no You tab.
- Must work as a normal user app on any ROM (Android 15+), not only when built into the system.
- Never declare `android.telecom.IN_CALL_SERVICE_RINGING`: Android plays the ringtone (with per-contact ringtones).
- The call screen must stay usable when the app is locked and when the phone is locked; it shows only the call.
- Notification actions go through the non-exported `CallActionReceiver` with immutable PendingIntents.
- **Call recording is Shizuku only:** never the microphone, no fallback of any kind; without Shizuku it explains how to start it and doesn't record. The capture service exposes only start/stop/format (no command execution), is bound only while recording or about to, and CallCtrl needs no RECORD_AUDIO or microphone foreground service. Recordings stay in `filesDir/recordings` (excluded from backup) and behind the app lock. Keep `CaptureService` in R8 (Shizuku instantiates it by name).

## Dependency verification

Gradle checks every library and plugin download against its SHA-256 in `gradle/verification-metadata.xml` (checksums only, no PGP). When a dependency or plugin changes, run the `verification-metadata.yml` workflow (`gh api -X POST repos/VoidedKN0X/CallCtrl/actions/workflows/verification-metadata.yml/dispatches -f ref=main`), decode the file from its log, check the diff (only Maven Central, Google's Maven and the Gradle plugin portal) and commit it in the same push. Never turn verification off or make it lenient.

## Releasing

- A release is published only when `versionName` in `app/build.gradle.kts` is bumped. Bump `versionCode` by 1 at the same time.
- Tags are `vX.Y.Z` and releases are named "CallCtrl X.Y.Z". Never add a build number (`-build.N`, "(build N)").
- Use the same version for every change that ships together; README, licence and CI changes don't need a bump.
- Push straight to `main`; no branches or pull requests.

## Changelog

Every new version needs a detailed entry at the top of `CHANGELOG.md`, under a heading for that version (match the existing headings). CI refuses to release without one, and the entry becomes the release notes on GitHub, in Obtainium and in the CtrlF-Droid repo.

- Write for people using the app, not for developers: what's new, what changed, what was fixed.
- Cover every user-visible change in the version, one bullet each, with enough detail to understand it without opening the app.
- Group with `### New`, `### Changed`, `### Fixed` and `### Security` when there's more than a couple of items.
