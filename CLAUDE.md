# CallCtrl

Phone app (dialer, call screen, call history) in the VKNXWorks Ctrl series. Kotlin, Jetpack Compose, Material 3, manual DI (`AppContainer`), DataStore for settings. Becomes the default phone app through RoleManager and gets calls through an `InCallService`; Android's Telecom keeps placing calls and ringing.

## Layout

- `calls/CallService.kt`: the InCallService. `calls/CallManager.kt`: call state, controls, audio route and the proximity wake lock.
- `calls/InCallActivity.kt` + `InCallScreen.kt`: the call screen (shows over the lock screen, never behind the app lock).
- `calls/ScreeningService.kt`: the CallScreeningService (bound by Telecom for the default dialer, `BIND_SCREENING_SERVICE`). `data/screening/`: rules, matching and JSON/CSV import/export (pure Kotlin, unit-tested in `app/src/test`; no regex, emergency numbers never screened), `ScreeningStore` (`filesDir/screening.json`). UI in `ui/settings/ScreeningScreen.kt`.
- `calls/CallNotifications.kt`: CallStyle notifications (incoming with full-screen intent, ongoing) and missed calls. `calls/Receivers.kt`: notification actions and Telecom's missed-call broadcast.
- `data/Phone.kt`: placing calls, SIMs, role, number formatting. `data/CallHistory.kt`: call log and blocked numbers. `data/Contacts.kt`: contacts with numbers, caller lookup, T9.
- `recording/`: the call recorder. `shell/CaptureService.kt` is a Shizuku user service (own process, shell uid) that only captures call audio (VOICE_CALL, else VOICE_DOWNLINK + VOICE_UPLINK) into a pipe; its AIDL (`src/main/aidl/.../ICallCapture.aidl`) has start/stop/format/destroy only. `ShizukuGate.kt`: Shizuku state, permission, binding. `CallRecorder.kt`: follows CallManager's calls, decides, splits files, writes through `Segment`/`Encoders` (MediaCodec FLAC/AAC/Opus, own WAV and FLAC framing). `RecordingStore.kt`: `filesDir/recordings` + `<file>.json` sidecars, MediaStore copies, auto-delete, encryption at rest of finished recordings (`data/crypto/`). Pure logic (`CaptureFormat.kt`, `RecordingRules.kt`, `Containers.kt`) is unit-tested. UI: `ui/settings/CallRecordingScreen.kt`, `ui/recordings/RecordingsScreen.kt`, the bar in `InCallScreen.kt`.
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
- **Call recording is Shizuku only:** never the microphone, no fallback of any kind; without Shizuku it explains how to start it and doesn't record. The capture service exposes only start/stop/format (no command execution), is bound only while recording or about to, and CallCtrl needs no RECORD_AUDIO or microphone foreground service. Recordings stay in `filesDir/recordings` (excluded from backup, encrypted at rest once finished: see below) and behind the app lock. Keep `CaptureService` in R8 (Shizuku instantiates it by name).

## Encryption at rest

- Finished call recordings (`filesDir/recordings/<audio file>`) are sealed with AES-256-GCM under the data key, in one of two layouts: in one piece up to `MAX_SEAL_BYTES` (32 MB; `data/crypto/SealedFile`, marker `CALLCTRL-REST1`), and in chunks above that (`data/crypto/ChunkedSealedFile`, marker `CALLCTRL-REST2`). The data key (`DataKeyStore`: 32 random bytes in `filesDir/data.key`, wrapped by the Keystore key `callctrl_data_at_rest` through `KeystoreWrapper`/`KeystoreAes`; no user authentication). The app lock vault and its keys are separate and untouched.
- **The recorder never sees any of this.** A recording is written as an ordinary audio file during the call (`CallRecorder`, `Segment`, `Encoders` are unchanged). `RecordingStore.sealPass` (run from `reload()`, on `Dispatchers.IO`) seals a recording only when its sidecar says `complete`, it isn't in `inProgress`, no call exists (`CallManager.calls` empty), and it wasn't shared as an ordinary file in this run. Up to `MAX_SEAL_BYTES` it is sealed in one piece (the whole file in memory); a bigger one in chunks (`sealOne`): only with room for the sealed copy next to the original, given up the moment a call starts (`abort` is asked at every chunk; the file stays ordinary and is retried), and after a real failure not retried until the next start (`bigSealFailed`).
- **`CALLCTRL-REST2`:** header `marker (14) | chunk size (int) | plain length (long) | file id (16 random)`, big-endian, then per chunk `IV (12 random) | ciphertext | tag (16)`; 1 MiB chunks, only the last may be shorter, an empty file has one empty chunk. Each chunk's associated data is the whole header + chunk index (long) + a last-chunk byte, and the file length must be exactly what the header implies, so reordered, repeated, dropped, truncated, appended or foreign chunks and any header change are refused (`SealedDataException`). Never change this layout or REST1: readers must accept ordinary files, REST1 and REST2 forever, and an already sealed file is never rewritten (not REST1 into REST2 either).
- Not encrypted on purpose: the `<file>.json` sidecars (read and written by the list, auto-delete and the recorder, also during calls), `screening.json` (read on the call-screening path, which must never wait for a key), the DataStore settings, and the file names (they are the export names).
- Readers accept all three kinds (`SealedFile.isSealed(file)` is true for both sealed layouts, `ChunkedSealedFile.isChunked` tells which): `playable()` (decrypted copy in `cache/playback`, removed when the player closes, on lock and at start), `shareable()` (decrypted copy in `cache/shared` through the FileProvider, removed after an hour) and `copyToMusic()` always give ordinary audio. A chunked recording is never read into memory: `playable()`/`shareable()` decrypt it chunk by chunk into a `.part` file that only gets the recording's name once every chunk checked out, and `copyToMusic()` streams it (a bad chunk fails the read, and the pending MediaStore entry is deleted). Without a data key nothing is sealed and everything works as before.
- Never delete or overwrite a recording that can't be opened (auto-delete skips them while the key is missing), never replace an existing `data.key`, never create a Keystore key on the decrypt path (`KeystoreAes.existing`). An ordinary file only becomes a sealed one through `SealedFile.sealInPlace` or `ChunkedSealedFile.sealInPlace` (temp file, sync, read back, compare with the file on disk, atomic rename; the chunked one does the read-back and compare by streaming), under `fileLock`, which deletes take too.
- Nothing on the incoming-call, screening or recording path may ask for the data key. Keep key use on background threads.
- `SealedFileTest` covers the one-piece format, the migration step and the key rules; `ChunkedSealedFileTest` covers the chunked format (several chunks, exact multiples, empty, every flipped bit, every truncation, moved/repeated/dropped/foreign chunks, wrong key, bad headers), that sealed files are never rewritten, and that a stopped or failed sealing leaves the ordinary file (`./gradlew :app:testDebugUnitTest`; CI doesn't run unit tests).

## Build toolchain

- AGP 9.5.1, Gradle 9.9.2, Kotlin 2.4.21, Compose BOM 2026.09.00, `compileSdk` 37 (Compose 1.12 needs it), `minSdk` 35, `targetSdk` 36. All versions are in `gradle/libs.versions.toml`. `buildFeatures { aidl = true }` stays on for the call-recording capture service.
- AGP 9 has built-in Kotlin: there is no `org.jetbrains.kotlin.android` plugin any more. The Kotlin version is pinned by the `kotlin-gradle-plugin` classpath entry in the root `build.gradle.kts`; the Compose and serialization compiler plugins use the same `kotlin` version. Don't re-add the Kotlin Android plugin.
- AGP 9's R8 is stricter (`-keep class X` without a member block no longer keeps the constructor; resources are shrunk together with code). Keep rules in `proguard-rules.pro` must name the members they need, and resources must be referenced through `R`, never by name.
- Any change here needs the dependency verification procedure below.

## Dependency verification

Gradle checks every library and plugin download against its SHA-256 in `gradle/verification-metadata.xml` (checksums only, no PGP). When a dependency or plugin changes, run the `verification-metadata.yml` workflow (`gh api -X POST repos/VoidedKN0X/CallCtrl/actions/workflows/verification-metadata.yml/dispatches -f ref=main`), decode the file from its log, check the diff (only Maven Central and Google's Maven) and commit it in the same push. Never turn verification off or make it lenient.

`settings.gradle.kts` lists only `google()` and `mavenCentral()` for plugins, not `gradlePluginPortal()`: the portal serves different files for the Kotlin plugin markers, which made verification fail whenever Maven Central had a hiccup. Don't add it back unfiltered (see the comment in that file).

## Build and signing (CI)

`.github/workflows/build.yml` has four jobs: `version` (is this a release?), `build` (Gradle, **no secrets**, `UNSIGNED_RELEASE=true` gives `app-release-unsigned.apk`; runs every check), `sign` (no checkout, no Gradle: `zipalign` + `apksigner` with the release key, then checks the signer against `EXPECTED_CERT_SHA256` and that the APK's contents didn't change) and `release` (attestation, release notes, publish).

- Never give the `build` job a secret, and never run Gradle or check out the repo in `sign`. New build checks (tests, lint, APK checks) go in `build`.
- `EXPECTED_CERT_SHA256` in `build.yml` must match the fingerprint in `SECURITY.md` and `README.md`; the build fails if it doesn't.
- No Gradle cache in any workflow (`cache-disabled: true`): a cache saved by a failed run used to break every later build.

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
