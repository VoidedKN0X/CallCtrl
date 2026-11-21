# Changelog

What changed in each CallCtrl release. Every release on the [Releases page](https://github.com/VoidedKN0X/CallCtrl/releases) also lists the exact changes in that build.

To publish a new version: bump `versionCode` and `versionName` in `app/build.gradle.kts`, add a section here, and push to `main`. Pushes without a version bump only build; they don't make a release.

## Unreleased

Changes on `main` that aren't in a release yet. They ship with the next version.

### Project

- **Checksum list regenerated without the old entries.** `gradle/verification-metadata.xml` was regenerated on CI with the fixed workflow and committed exactly as printed (its SHA-256 matches the log). It now lists only the 382 components the build downloads today; the leftovers from earlier library and build-tool versions are gone.
- **The checksum list no longer collects old entries.** The hand-started workflow that regenerates `gradle/verification-metadata.xml` used to add the new checksums to the existing file, so entries for library versions that are no longer used stayed in it. It now clears the old entries first and writes only what the build uses, and prints the file's SHA-256 so the copy that gets committed can be checked against the log. Nothing in the app changes.
## v0.4.0 (2026-11-21)

Built and checked by CI only: nothing in this release had been run on a phone when it was published. It is the first release built with the new build tools (Android Gradle Plugin 9, stricter code shrinking), so update over your installed version and check that your data and settings are still there.

**This encrypts long call recordings that were left as normal files until now. An older version of CallCtrl cannot read them afterwards (details below).** Built and checked by CI only: nothing here had been run on a phone when it was pushed.

### Security

- **Long call recordings are now encrypted too.** Until now a finished recording above 32 MB (roughly 10 to 15 minutes of FLAC, about 3 minutes of stereo WAV) stayed a normal audio file in CallCtrl's storage, because encrypting it needed the whole file in memory at once. Such recordings are now encrypted in pieces of 1 MB, so neither encrypting nor opening one ever holds more than a piece in memory. Same key and cipher as before (AES-256-GCM under the key in `data.key`, which is locked by a key in the phone's secure hardware). Recordings up to 32 MB are encrypted exactly as in 0.3.0, and recordings that are already encrypted are never rewritten.
- **How the pieces are protected.** The file starts with a marker (`CALLCTRL-REST2`), the piece size, the length of the recording and a random file number. Every piece is encrypted on its own with its own random starting value, and is tied to that header, to its position in the file and to whether it is the last piece. So pieces that were swapped, repeated, left out, cut off, added or taken from another recording, and any change to the header or to a single bit, are noticed and the recording is refused instead of played wrong.
- **Long recordings you already have are encrypted the same careful way as short ones**, one at a time, in the background, only when no call is going on, the next time CallCtrl starts or you open Recents or Recordings. The encrypted copy is first written next to the original and synced to storage, then read back, decrypted and compared byte for byte with the original, and only then put in its place in one step. If anything fails or the app is stopped halfway, the original stays as it is and still plays. Three things are specific to long recordings: it needs free space for a second copy of the recording while it works (if there isn't enough, plus 50 MB to spare, the recording simply stays a normal file for now); it stops at once when a call starts and tries again later; and a recording whose encryption failed for another reason is not tried again until the next time CallCtrl starts, so it doesn't rewrite hundreds of megabytes over and over.
- **Playing, sharing and "Save a copy to Music" still give a normal audio file.** For a long encrypted recording, playing and sharing first decrypt it into a temporary copy in CallCtrl's cache (removed as before: the playing copy when you close the recording, when the app locks and at the next start; a shared copy after an hour). The copy only gets the recording's name once every piece checked out. "Save a copy" decrypts while it copies; if a piece is damaged the copy is thrown away and you get "couldn't save".
- **What you may notice:** opening a long recording to play or share it now takes a moment (the whole file is decrypted first; before, long recordings were normal files and opened at once), and it needs as much free space as the recording is big. If there isn't room, you get the existing "can't open" message and the recording itself is untouched.
- **If the key is lost**, long encrypted recordings behave like the short ones: they are kept as they are, counted in the "encrypted recordings can't be opened" notice with **Try again**, and never removed by "Delete after".
- **An older CallCtrl can't play them.** CallCtrl 0.3.0 and older see a long encrypted recording as a normal audio file that won't play, and sharing it gives an unreadable file. Before going back to an older version, use "Save a copy to Music/CallCtrl" (or Share) on the recordings you want to keep.

### Faster

- **Typing on the keypad no longer waits for your contacts.** On every digit, the app walked through every contact on the main thread, twice: once for the suggestions and once to see whose number it is (comparing phone numbers properly is slow). Both now happen in the background. What you may notice: the suggestions, the contact's name under the number and the "Add to contacts / Message" row update a moment after the digit appears instead of in the same instant. The name under the number is only ever shown for exactly the digits on screen; the "Add to contacts" row keeps its last state for that moment so it doesn't blink with every digit. Which suggestions you get is unchanged.
- **Recents with call recordings is lighter.** To show which calls have a recording, every call was compared with every recording, starting with the slow phone-number comparison. The time of the call is now checked first, which rules out nearly every pair with two simple comparisons. The result is the same.
- **Searching contacts prepares the search text once per letter**, not again for every contact. Same results.
- **Scrolling the Contacts tab reuses rows better.** Rows and letter headings are marked as different kinds of list item, so each is reused only for its own kind. Nothing looks different.

### Changed

- **Newer build tools and libraries; nothing changes in how CallCtrl looks or works.** CallCtrl is now built with the current stable toolchain: Android Gradle Plugin 8.11.1 → 9.5.1, Gradle 8.14.3 → 9.9.2, Kotlin 2.2.0 → 2.4.21 (with its Compose and serialization compiler plugins) and Compose BOM 2025.07.00 → 2026.09.00 (Compose 1.12.1, Material 3 1.4.0). The libraries move to the versions the newest Ctrl apps use: activity-compose 1.10.1 → 1.13.0, core-ktx 1.16.0 → 1.18.0, lifecycle 2.9.1 → 2.10.0, navigation-compose 2.9.1 → 2.9.8, datastore-preferences 1.1.7 → 1.3.0, kotlinx-serialization-json 1.9.0 → 1.13.0 and kotlinx-coroutines-android 1.10.2 → 1.11.0. The Shizuku library (13.1.5) is unchanged. No library was added, and the app is still Google-free (CI checks it).
- **Compiled against the Android 17 SDK.** Compose 1.12 needs `compileSdk` 37, so that goes from 36 to 37. The Android versions CallCtrl runs on and targets are unchanged (`minSdk` 35, `targetSdk` 36), so Android treats the app exactly as before.
- **The app is shrunk by a newer R8.** Android Gradle Plugin 9 compiles Kotlin itself (the separate Kotlin Android plugin is gone from the build files) and brings the R8 version that understands Kotlin 2.4. The release APK is about 4.0% larger (3.46 MB → 3.60 MB). The newer R8 shrinks more strictly than the old one, so the release build needs a check on a phone before the next version is published. CallCtrl's own keep rules are unchanged, including the one for the call-recording capture service that Shizuku starts by name.
- The checksums Gradle verifies every download against (`gradle/verification-metadata.xml`) were regenerated on CI for the new versions. Built and checked by CI only; not yet run on a phone.

### Project

- A unit test (`ChunkedSealedFileTest`, 16 tests) covers the new format: several pieces, lengths that are an exact number of pieces, an empty file, every single flipped bit, a file cut off at every possible length, extra data at the end, pieces swapped, repeated, dropped or taken from another file (with and without the header changed to match), wrong keys, bad headers, that already encrypted files are never rewritten, and that encrypting which is stopped or fails at any point leaves the normal file and no temporary file. These tests, and the existing `SealedFileTest`, were run on a computer and pass; CI builds the app but doesn't run unit tests, and none of this has run on a phone.
- **The signing key is no longer available to the build.** Until now the release key and its passwords were present in the same GitHub Actions job that runs Gradle, so any build plugin or library could in principle have read them. The build job now has no secrets at all and produces an unsigned APK. A separate job, which checks out no code and runs no Gradle, signs it with `apksigner`, and then refuses to hand it on unless it is signed by exactly the certificate published in SECURITY.md and README.md and its contents are identical to what the build job made. Same key and same signature scheme (v2) as before, so updates will install as usual. This changes how CallCtrl is built and signed, not the app itself. Seen working on CI for builds that don't publish; the publishing step runs with it for the first time at the next release.
- **The build checks that the published fingerprint and the enforced one are the same.** If `build.yml`, SECURITY.md and README.md ever disagree about the signing certificate, the build fails.
- **Builds no longer use a saved Gradle cache, and the Gradle plugin portal is no longer a plugin source.** Other Ctrl apps had builds fail with "Dependency verification failed ... from repository Gradle Central Plugin Repository", and keep failing until the caches were deleted by hand. The cause (found on DateCtrl): when Maven Central can't be reached for a moment, Gradle takes the Kotlin plugin "marker" file from the Gradle plugin portal instead, the portal serves a different file under the same name, verification rightly refuses it, and the failed run then saved that state as the cache for every later run. Now every build and the code scan start from a clean Gradle home, and `settings.gradle.kts` lists only Maven Central and Google's Maven for plugins. Dependency verification itself is unchanged and as strict as before. Builds take a minute or two longer.

## v0.3.0 (2026-11-20)

**This release encrypts some of what the app stores on the phone. An older version cannot read it afterwards, so export or back up anything you need before you update (details below).** Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Security

- **Finished call recordings are now encrypted in CallCtrl's storage.** A recording is still written as a normal audio file while the call lasts; the recorder itself is unchanged. Afterwards, in the background and only when no call is going on, CallCtrl replaces the audio file with an encrypted copy (AES-256-GCM). The key is 32 random bytes kept in `data.key` in CallCtrl's private files, itself encrypted by a key in the phone's secure hardware (Android Keystore, StrongBox when the phone has it) that can't be copied off the phone. No PIN or fingerprint is needed for it, and it is separate from the app lock. What this protects: the audio in files copied off the phone (a storage dump, a backup tool with root). What it does not protect: someone who can use the unlocked phone or the running app; the app lock is still what keeps people out of the app.
- **Recordings you already have are encrypted too**, one at a time, the next time CallCtrl starts or you open Recents or Recordings. Each file is first written out encrypted next to the original, read back, decrypted and compared byte for byte with the original, and only then put in its place in one step. If anything fails, or the app is stopped halfway, the original recording stays as it is and CallCtrl tries again later. No recording is ever without a readable copy.
- **Playing, sharing and "Save a copy to Music" work as before and still give a normal audio file.** To play an encrypted recording, CallCtrl decrypts it into a temporary file in its cache, which is removed when you close the recording, when the app locks and when it next starts. Sharing an encrypted recording hands the other app a decrypted copy from the cache with the same file name; that copy is removed after an hour. Opening a long encrypted recording can take a moment before Play responds.
- **Deliberately not encrypted, and why:**
  - *Recordings larger than 32 MB* (roughly 10 to 15 minutes of FLAC, about 3 minutes of stereo WAV, hours of Opus or AAC): encrypting and opening a file needs all of it in memory at once, and CallCtrl must never run out of memory around a call. These stay normal audio files, as before.
  - *The small notes file next to each recording* (number, name, times, format): the list, "Delete after" and the recorder read and write it, also during calls, and nothing there may wait for the key.
  - *The file names* (date, direction and name or number, like `20261113-142501_in_Anna.flac`): they are the names your shared and saved copies get.
  - *Your call screening rules* (`screening.json`): they are read the moment a call comes in, and screening must never wait for a key.
  - *Settings.* Your call history and contacts are the phone's own and were never CallCtrl's files.
- **If the key is ever lost** (for example the phone's secure key storage was reset, or CallCtrl's files were copied to another phone), encrypted recordings can't be opened. CallCtrl then keeps them exactly as they are, shows "encrypted recordings can't be opened" with a **Try again** button at the top of the Recordings screen, does not let "Delete after" remove them, never makes a new key over the old one, and keeps recording new calls (those stay normal files). You can still delete such recordings yourself.
- **An older CallCtrl can't play encrypted recordings.** If you go back to 0.2.1 or older, encrypted recordings show in the list but won't play, and sharing them gives an unreadable file. Before downgrading, use "Save a copy to Music/CallCtrl" (or Share) on the recordings you want to keep.

### Project

- A unit test (`SealedFileTest`) covers the encrypted file format, the step that replaces a normal file with an encrypted one, and the key rules. CI builds the app but doesn't run unit tests.

## v0.2.1 (2026-11-20)

Built and checked by CI only: nothing in this release had been run on a phone when it was published.

### Faster

- **Searching contacts no longer stutters with a big address book.** For every contact, on every letter you typed, the app rebuilt the small text pattern it uses to ignore accents ("é" finds "e"). It is now built once and reused. The same goes for the pattern that works out the initials shown for contacts without a picture, which ran for every such row while scrolling. Search results and initials are exactly the same as before.
- **Keypad suggestions do less work per digit.** The keypad spelling of each name (Anna = 2662) is joined into one string when contacts load, instead of again for every contact on every digit you type. The suggestions are the same.
- **The Contacts tab doesn't regroup the list while you use it.** Favourites and the A-Z sections were worked out again every time the list was laid out; now only when the contacts or the search text change.
- **Grouping call history is lighter.** To decide whether two calls belong in one group, the calendar day of every call was worked out twice; now once. The groups are the same.

### Privacy

- **The app is hidden from screenshots from its very first frame when it opens locked.** The "not in screenshots or screen recordings" protection was switched on a moment after the first frame was drawn, so the very first frame of the lock screen was not covered. It is now set before anything is drawn when the app lock is on (and when "Hide in screenshots" is already known to be on). Nothing changes while the app is open.

### Not changed

- CallCtrl has no home-screen widgets and no Quick Settings tiles, so there was nothing to remove.
- Call handling, call screening and the call recorder are untouched.

## Project updates (2026-11-19)

### Project

- **The weekly code scan no longer fails at random.** CodeQL has to watch the app being compiled, but its build could take the compiled code from Gradle's build cache instead, and then the scan stopped with "CodeQL detected code written in Java/Kotlin but this run didn't build any of it" (and GitHub mailed a failed run). The scan's build now runs with `--no-build-cache`, so the code is always compiled. No app changes, so no new version.

## v0.2.0 (2026-11-13)

Call screening: decide which calls ring, which ring silently and which are turned away, with your own rules. And a call recorder that works through Shizuku, in lossless quality. Still fully offline.

### New

- **Call screening** (Settings > Phone app > Call screening, off until you turn it on). Before a call rings, CallCtrl checks it against your rules and rejects it, lets it ring silently, or lets it through. It works while CallCtrl is your default phone app.
- **Rules by number:** "starts with" (for example +44 or 0900), one exact number, or a simple pattern where ? is one digit and * any number of digits (for example +32 4?? 12 *). Spaces and dashes don't matter, and +32 and 0 forms of the same number both match.
- **Rules for who is calling:** callers who aren't in your contacts, and hidden or private numbers.
- **Three actions:** Reject declines the call without ringing; Silence lets it ring without sound or vibration, so it ends up as a missed call; Allow always lets it through. When several rules match, Allow wins, then Reject, then Silence, so you can make exceptions (for example: silence everyone who isn't a contact, but allow your doctor's number).
- **Your callers list:** numbers you always want rejected or silenced, even when they're saved in your contacts.
- **Contacts always ring** (on by default): calls from your contacts skip the rules.
- **Rejected calls in Recents:** they're kept in your call history as blocked calls (you can turn this off), and an optional quiet notification tells you a call was rejected, without sound or a banner.
- **Emergency numbers are never screened**, whatever your rules say.
- **Import and export:** save every rule, your callers list and your blocked numbers as a JSON file, or as a CSV file (one line per rule or number) for a spreadsheet or another app, and import them again on another phone. Importing adds to what you have and checks every line; a plain list of numbers is imported as numbers to block.
- **Blocked numbers stay shared:** the blocked list is still your phone's own list, so numbers you block in CallCtrl are blocked in your messaging app too, and the other way round. Android blocks them before your screening rules are checked.
- **Call recording** (Settings > Phone app > Call recording, off until you turn it on). Android doesn't let normal apps record calls, so CallCtrl does it through [Shizuku](https://shizuku.rikka.app/), a free app you start with Wireless debugging (no root). The settings page shows where Shizuku stands (not installed, not running and how to start it, waiting for your OK, or ready) and asks Shizuku's permission for you.
- **What gets recorded:** Off, Ask each call (a Record button on the call screen), Always, Only numbers not in your contacts, or Only the contacts you pick. Recording starts when the call is answered, never while it rings, and stops when the call ends or goes on hold (you can keep it recording on hold). A second call or a conference gets its own file.
- **On the call screen:** a red bar with the recording time; tap it to stop. In every mode but Off there's a Record button to start one yourself.
- **Best quality your phone gives:** CallCtrl tries 48 kHz down to 8 kHz, stereo then mono, 32-bit float then 16-bit, and uses the best one your phone accepts for call audio (both sides of the call). When the two channels hold the same sound it saves mono, half the size and nothing lost. If your phone won't share the call audio as one stream, CallCtrl records the other person and you separately and mixes them, and tells you if only one side could be captured. It never falls back to the microphone.
- **File types:** FLAC (lossless, the default), WAV (lossless, uncompressed), or the smaller Opus (OGG) and AAC (M4A). Each recording shows its real sample rate, channels and what was captured.
- **Recordings screen** (from Settings > Call recording): play with a seek bar, share, save a copy to Music/CallCtrl, delete. Recordings also show on their call in Recents, with a small mark on the call.
- **Private by default:** recordings stay in CallCtrl's own storage, left out of every backup, behind the app lock. Optional: save a copy of each new recording to Music/CallCtrl, and delete recordings automatically after 7, 30, 90 or 365 days.
- **A short legal note** the first time you turn recording on: in Belgium you may record calls you take part in, but using or sharing them is restricted; tell the other person.
- **When something goes wrong:** if Shizuku stops during a call, or the phone runs out of space, the recording stops cleanly, what was recorded is kept, and a notification says why.

### Changed

- **"Forgot PIN?"** now also resets the call screening rules, along with CallCtrl's other settings. Your call history, contacts and blocked numbers are still not deleted. If you have call recordings, it asks whether to delete them or keep them.
- **New permission, for Shizuku only:** CallCtrl now lists Shizuku's own "moe.shizuku.manager.permission.API_V23", which is how the Shizuku app shows CallCtrl among its authorised apps. It grants nothing by itself, and it isn't a network permission.

## v0.1.5 (2026-11-12)

### Fixed

- **The caller's name is readable in the dark theme.** On the call screen the name (or number) under the picture, the name above the in-call keypad, the second caller's name and the quick-reply and audio lists were drawn in black, so on the dark background you could hardly see them. Text now takes its colour from the theme everywhere in the app: light on the dark background, dark on the light one, and it changes with your phone's dark mode.

### Changed

- **Slightly smaller download.** The bundled JetBrains Mono font (numbers, readouts and labels) no longer carries the Cyrillic, Vietnamese and maths-alphabet letters CallCtrl never shows itself, which takes about 0.1 MB off the download and 0.2 MB off the installed app. Everything looks the same; if a name or a log line shown in that font uses one of those scripts, those letters are now drawn with your phone's own font.

## v0.1.4 (2026-11-05)

CallCtrl now needs Android 15 or newer.

### Changed

- **Needs Android 15 or newer.** Android 13 no longer gets security updates and Android 14's end early 2027, so CallCtrl now supports Android 15, 16 and 17 only. On older phones the version you have keeps working, but it won't get updates.

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
