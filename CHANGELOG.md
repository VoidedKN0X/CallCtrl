# Changelog

What changed in each CallCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/CallCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## v0.1.3 (2026-11-05)

Confirmations are readable by accessibility apps again.

### Fixed

- "Can't be undone" confirmations (such as clearing call history) can be read again by apps that use accessibility, such as password managers and automation apps. 0.1.2 hid them from those apps; now only the lock screen, the PIN screens and "Forgot PIN?" are hidden from them. Other apps' floating windows are still kept off these confirmations, and screen readers such as TalkBack keep working everywhere.

## v0.1.2 (2026-11-05)

More protection for the PIN screen and confirmations, and a way to check that a download really comes from this repo. CallCtrl still has no internet permission, and calls work exactly as before.

### Security

- **No floating windows over the PIN screen:** while the lock screen, a PIN screen, "Forgot PIN?" or a delete confirmation is open, other apps' floating windows (chat heads, overlays) are hidden, so they can't cover or imitate it. They come back as soon as it closes, keep working everywhere else in CallCtrl and are never hidden over the call screen. This uses Android's "hide overlay windows" permission, which is granted at install without a prompt. It isn't a network permission: CallCtrl stays offline.
- **PIN screens hidden from accessibility misuse (Android 14+):** the lock screen and PIN screens are hidden from apps that use accessibility access to read the screen. Real accessibility tools such as TalkBack keep working.
- **Stricter about other apps' requests (Android 16+):** CallCtrl now only accepts requests from other apps that match what it declares: the launcher, `tel:` links, "dial" and "show call log" requests, the call button, and Android's call and missed-call services.
- **Every library checked:** the build now refuses any library or build plugin whose SHA-256 doesn't match the one recorded in the repo.

### Project

- **Check a download:** this is the first release with GitHub build provenance. The release notes end with the APK's SHA-256, and `gh attestation verify CallCtrl-v0.1.2.apk -R VoidedKN0X/CallCtrl` proves the APK was built by this repo's workflow from the tagged commit. The README explains both, next to the signing-certificate fingerprint, and lists the new permission.

## Project updates (2026-11-05)

No app changes in this push, so no new version.

### Project

- **Proof of where each APK comes from:** from the next release on, the build workflow attests every APK with GitHub's build provenance (signed through Sigstore), and the release notes end with the APK's SHA-256. `gh attestation verify CallCtrl-vX.Y.Z.apk -R VoidedKN0X/CallCtrl` then proves an APK was built by this repo's workflow from the tagged commit.
- **Workflow audit:** a new zizmor workflow checks the GitHub Actions workflows for security problems whenever they change. zizmor itself is installed pinned to one version with its SHA-256.
- **Least privilege in CI:** every workflow now starts with no permissions, and each job asks only for what it needs. Only the release job can write, and it alone gets the permissions to sign the build provenance.
- **CodeQL:** a new workflow scans the app's code for security problems every week and when the app changes. Results go to the repo's Security tab; it never blocks a release.
- **Dependency verification:** a new workflow writes `gradle/verification-metadata.xml`, the SHA-256 of every library and Gradle plugin the build downloads, so the build can refuse anything that doesn't match. CLAUDE.md explains how to update it when a dependency changes.

## v0.1.1 (2026-11-05)

A privacy and security check-up: two permissions fewer, and no calling out from the lock screen.

### Security

- **Call back needs an unlocked phone**: tapping **Call back** on a missed-call notification on the phone's lock screen placed the call straight away, without unlocking. It now asks you to unlock the phone first, like **Message** already did. When the phone is unlocked it calls back right away as before.

### Privacy

- **Two permissions fewer**: CallCtrl no longer has the "change your audio settings" and "control vibration" permissions. Switching between speaker and earpiece and muting go through Android's call service, and keypad vibration through the system, so neither was ever used. Everything works as before.
- **Fewer log messages**: release builds now strip debug and info log messages from the libraries CallCtrl uses as well (CallCtrl itself writes nothing to the system log).

### Project

- README: the permissions table names every permission the app has, the Privacy section says CallCtrl is Google-free with no network destinations at all, the App lock section covers **Call back** on the lock screen, and the Ctrl series list shows all 28 apps.
- The build now also checks that the Gradle wrapper is an official Gradle release and that the APK is signed with CallCtrl's release key before it's published. `.gitignore` also keeps `google-services.json` out of the repo.

## v0.1.0 (2026-11-05)

The first release of CallCtrl: a private, offline phone app in the Ctrl series, made to replace your phone's dialer and call screen on any Android 13+ phone.

### New

- **Becomes your phone app** in one tap the first time you open it (or later in Settings), then asks for the call, call history, contacts and notification permissions it needs. You can also use the keypad without making it your phone app.
- **Incoming call screen**, also over the lock screen: Answer, Decline, and Reply with a message (three ready-made texts or your own). When you're using the phone, an incoming call shows as a banner with Answer and Decline. Your phone keeps playing the ringtone, including each contact's own ringtone.
- **During a call:** caller name, photo and number label, call timer, mute, keypad tones, speaker, headset and Bluetooth (with a choice of output when Bluetooth is connected), hold and resume, add a call, swap between two calls and merge them into a conference.
- **Call waiting:** a second incoming call shows on the call screen with Answer and Decline; answering puts the first call on hold.
- **Choose a SIM** for each call when you have two and no default, and see which SIM a call is on.
- **Screen off at your ear** during calls on the earpiece, and a call notification with Hang up while you use other apps; a bar in CallCtrl leads back to the call.
- **Keypad** with letters, tones and vibration; contact suggestions as you type, by number or by name on the keypad letters; Add to contacts and Message for new numbers; long-press 0 for +, long-press 1 for voicemail; tapping call with nothing typed brings back your last number.
- **Recents:** your call history with calls in a row grouped, missed calls in red, an All / Missed filter, call back with one tap, and Clear call history. Opening Recents marks missed calls as seen.
- **Call details:** call, message, open in contacts or add to contacts, copy the number, block or unblock, delete, and every call in the group with time and duration.
- **Contacts tab** with every contact that has a number, favourites on top, search, and a choice of number for contacts with more than one; long-press opens the contact in your contacts app.
- **Missed call notifications** with Call back and Message; on the lock screen they show the caller only when your phone allows private notification content.
- **Blocked numbers** in Settings: add and remove numbers; your phone blocks their calls and texts for every app.
- **Works with other apps:** phone number links and call buttons in other apps open the keypad with the number filled in.
- **App lock** with its own PIN (at least 6 digits) and optional fingerprint unlock; calls can always be answered without it. **Hide in screenshots** and **Hide in recent apps**, both off by default.
- **Ctrl / Signal look** in dark and light, following your phone's theme, with layouts for phones, tablets and foldables.
