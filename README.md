# CallCtrl

**A private, offline phone app that replaces your phone's dialer and call screen.**

[![Latest release](https://img.shields.io/github/v/release/VoidedKN0X/CallCtrl?label=download)](https://github.com/VoidedKN0X/CallCtrl/releases/latest) ![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) [![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE) ![Telemetry-free](https://img.shields.io/badge/telemetry-none-brightgreen) ![Offline](https://img.shields.io/badge/internet-none-brightgreen) [![Sponsor](https://img.shields.io/badge/sponsor-GitHub-ea4aaa?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/VoidedKN0X)

CallCtrl is a full phone app: a keypad with contact search, your call history, your contacts with a number, and the call screen for incoming and ongoing calls. Calls themselves still go through your phone's own telephony, so your SIM, carrier features and emergency calls work exactly as before. It never goes online.

Part of the [Ctrl series](#the-ctrl-series) by VKNXWorks.

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/VoidedKN0X/CallCtrl"><img src=".github/assets/badge_obtainium.png" alt="Get it on Obtainium" height="80"></a> <a href="https://voidedkn0x.github.io/CtrlF-Droid/"><img src=".github/assets/badge_ctrlf-droid.png" alt="Get it from our F-Droid repo" height="80"></a>

## Features

- **Works fully offline.** CallCtrl doesn't have the internet permission and never goes online. It uses Android's own telephony, call log and contacts, and keeps no copy of them
- **Call screen** for incoming, outgoing and waiting calls, also on the lock screen: answer, decline, decline with a text message, mute, keypad tones, speaker, headset and Bluetooth, hold, add a call, swap and merge into a conference
- **Keypad** with letters, tones and vibration you can turn off; searches your contacts by name on the keypad letters or by number as you type. Long-press 0 for +, long-press 1 for voicemail, and the call button brings back the last number you called
- **Recents:** your call history with calls in a row grouped, missed calls in red and a Missed filter. Tap a call for its details: call back, message, add to or open in contacts, copy, block and delete
- **Contacts** with a phone number, favourites on top, search, and a choice of number for contacts with several
- **Missed call notifications** with Call back and Message, and the caller's name only on the lock screen when your phone allows private notification content
- **Blocked numbers:** block and unblock numbers from Recents or Settings; your phone blocks their calls and texts for every app
- **Two SIMs:** choose the SIM for each call when there's no default, and see which SIM a call is on
- **The screen turns off at your ear**, and the call keeps a notification with hang up while you use other apps
- **Works with other apps:** phone number links and "Call" buttons in other apps open CallCtrl's keypad with the number filled in
- **App lock** with its own PIN and optional fingerprint unlock (incoming calls can always be answered), and **Hide in screenshots** / **Hide in recent apps** (both off until you turn them on)
- **Ctrl / Signal look**, shared by every Ctrl app: dark and light, following your phone's dark theme setting, high contrast, one violet accent, Space Grotesk and JetBrains Mono; landscape, tablet and foldable layouts

## Requirements

- Android 15 or newer, on a 64-bit ARM phone (nearly every phone from the last several years)
- Works on any Android ROM as a normal app: stock Android, LineageOS, GrapheneOS, CalyxOS, /e/OS and the phone makers' own systems. It doesn't need root or to be built into the system.
- **Google-free and de-Googled:** no Google Play services, Firebase or other Google libraries, and the app never contacts Google.

## Install

1. Open the [latest release](https://github.com/VoidedKN0X/CallCtrl/releases/latest) on your phone, scroll down to **Assets** and tap the file that ends in `.apk` (for example `CallCtrl-v0.1.1.apk`). The "Source code" zip and tar.gz files are not the app; you don't need them.
2. Open it on your phone. Android may ask you to allow installing apps from your browser or file manager.
3. Updates install over the old version. Your settings are kept.

**Automatic updates:** on your phone, tap one of the buttons above.

- **Obtainium:** install [Obtainium](https://github.com/ImranR98/Obtainium) first. The button adds CallCtrl and installs every new release for you.
- **F-Droid:** adds the [CtrlF-Droid repo](https://voidedkn0x.github.io/CtrlF-Droid/) to F-Droid, Droid-ify or Neo Store, with every Ctrl app and automatic updates.

If the Obtainium button doesn't open the app, add `https://github.com/VoidedKN0X/CallCtrl` in Obtainium by hand.

**Make it your phone app:** the first time you open CallCtrl it asks to become your default phone app. You can also do it later in **Settings > Phone app** or in Android's **Settings > Apps > Default apps > Phone app**. To go back, pick your old phone app there. CallCtrl works as a normal app on any Android 15+ ROM; it doesn't need to be built into the system.

## Getting started

- **Keypad:** type a number or a name on the keypad letters; tap a suggestion or the call button.
- **Recents:** tap the phone icon to call back, tap a call for its details. The bin at the top clears the history.
- **Contacts:** tap a contact to call; long-press to open it in your contacts app.
- **During a call:** mute, keypad, speaker (or Bluetooth), hold, add a call, then swap or merge. The bar at the top of CallCtrl leads back to the call.

Settings and About are behind your profile picture at the top right of every tab.

## Is it safe to install?

- **Open source.** All of CallCtrl's code is in this repo, under the GPL-3.0 licence. Anyone can read it.
- **Built by GitHub, not on a personal computer.** Every release on the Releases page is built by [the build workflow](.github/workflows/build.yml) on GitHub Actions, straight from the code in this repo. Each build also checks that the app has no network permission and no Google libraries.
- **Always signed with the same key.** Android refuses to install an update signed with a different key over CallCtrl, and the CtrlF-Droid repo only accepts APKs signed with this key. The SHA-256 fingerprint of CallCtrl's signing certificate is:

  ```
  80:98:04:45:55:FD:7F:38:11:BB:9D:E7:39:AC:AC:33:77:8D:22:B2:DA:B3:C3:5D:9D:5E:8C:B9:68:C5:1C:13
  ```

  Advanced users can check it with [AppVerifier](https://github.com/soupslurpr/AppVerifier).
- **Check a download.** Each release's notes end with the APK's SHA-256, so you can check the file you downloaded (`sha256sum CallCtrl-vX.Y.Z.apk`). With the [GitHub CLI](https://cli.github.com/), `gh attestation verify CallCtrl-vX.Y.Z.apk -R VoidedKN0X/CallCtrl` proves the APK was built by this repo's build workflow from the tagged commit. The signing-certificate fingerprint above stays the check Android and AppVerifier use.

**Permissions.** CallCtrl has **no internet permission**: it can't go online at all. It asks only for what it needs, when it needs it:

| Permission | What CallCtrl uses it for |
| --- | --- |
| Make and manage phone calls (`CALL_PHONE`, `READ_PHONE_STATE`) | Making calls, and reading which SIMs can make them. |
| Read and write the call log (`READ_CALL_LOG`, `WRITE_CALL_LOG`) | Showing your call history, and deleting calls you choose. Nothing is ever added to or changed in it. |
| Read your contacts (`READ_CONTACTS`) | Names and photos of callers, keypad search and the Contacts tab. |
| Notifications, full-screen alerts (`POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`) | The incoming call screen and banner, the ongoing call and missed calls. |
| Keep awake (`WAKE_LOCK`) | Turning the screen off while the phone is at your ear during a call. |
| Use biometric hardware (`USE_BIOMETRIC`) | Optional fingerprint unlock for the app lock. |
| Hide other apps' floating windows (`HIDE_OVERLAY_WINDOWS`) | Hide other apps' floating windows over the PIN screen, and over the lock screen and confirmations, only while they show (never over the call screen). Granted at install, without a prompt. It isn't a network permission. |

`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` also shows up in the app's details: it's an internal permission Android adds to every app built for Android 13 and newer, and only keeps CallCtrl's own broadcasts private. Speaker, earpiece and mute go through Android's call service, and keypad vibration through the system, so CallCtrl needs no audio-settings or vibrate permission.

See [Privacy](#privacy) for what happens to your data.

## App lock

Turn it on under **Settings > Privacy and security > App lock** (tap your profile picture at the top right, then **Settings**). It's off until you turn it on.

- **Its own app PIN** of at least 6 digits, separate from your phone's screen lock. Very obvious PINs such as 111111 or 123456 are refused.
- **Unlock with fingerprint** (optional) only accepts strong biometrics, checked by the phone's secure hardware. The PIN is still needed after the phone restarts and at least every 72 hours.
- **The PIN is never stored.** It's stretched with 600,000 rounds of PBKDF2 and mixed with a key held in the phone's secure hardware.
- **Wrong PINs:** after 5 tries you wait 30 seconds, doubling up to 15 minutes. Restarting the app or the phone, or changing the clock, doesn't skip the wait.
- **Lock again:** as soon as you leave the app (the default), after 1, 5 or 15 minutes or 1 hour away, or only when the app restarts.
- **Forgot your PIN?** It can't be recovered. The lock screen offers to reset CallCtrl's own settings and turn the lock off. Your call history and contacts are not deleted: they belong to your phone, not to CallCtrl.
- **Calls are never locked:** the incoming call screen and the ongoing call always open without the PIN, and show only the call.
- **Missed calls:** **Call back** on the missed-call notification asks you to unlock the phone first when it's locked, so nobody can call out from the lock screen. On the lock screen the notification only shows how many calls you missed, unless your phone is set to show private notification content.

**Hide in screenshots** and **Hide in recent apps** (both off by default): the first keeps CallCtrl out of screenshots and screen recordings, the second shows a blank card in the recent-apps view while screenshots still work. With the app lock on, the recent-apps view always shows a blank card.

## Privacy

**Telemetry-free and Google-free.** CallCtrl has no analytics, crash reporting, usage stats, ads or tracking of any kind, and no Google libraries or services. It has **no internet permission**, works fully offline and never connects to anything: there are no network destinations at all. Every build checks that the app has no network permission and no Google libraries.

- Calls go through your phone's own telephony. Your call history stays in the phone's call log and your contacts in its contacts storage; CallCtrl keeps no copy, its own files hold only its settings.
- A text you send when declining a call goes out as a normal SMS through your phone.
- Copied numbers are marked as sensitive, so Android doesn't show them in the clipboard preview.
- CallCtrl is left out of Android's cloud backup and its phone-to-phone transfer.
- The Support, Report a bug, Source code and Licence links on the You page open in your browser, only when you tap them.

## How CallCtrl is made

CallCtrl is a one-person project by VoidedKN0X (VKNXWorks). Much of the code is written with the help of AI (Claude, by Anthropic). I decide what the app does, review the changes and use the app myself. Every change is listed in the [changelog](CHANGELOG.md), and you can read every line of code here.

## Build from source

You need [Android Studio](https://developer.android.com/studio), or JDK 17 and the Android SDK.

1. Clone the repo:
   ```bash
   git clone https://github.com/VoidedKN0X/CallCtrl.git
   cd CallCtrl
   ```
2. Build the app:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows, use `gradlew.bat assembleDebug`.
3. The APK is in `app/build/outputs/apk/debug/`. Copy it to your phone and install it.

Or open the folder in Android Studio and press **Run** with your phone connected.

**Dependency verification:** every library and Gradle plugin the build downloads is checked against its SHA-256 in `gradle/verification-metadata.xml`, and the build stops if one doesn't match. When you add or update a dependency or plugin, run the **Dependency verification metadata** workflow from the Actions tab, check the diff of the file it prints, and commit the new `gradle/verification-metadata.xml` in the same push.

## Support the project

CallCtrl is free and open source. If it's useful to you, you can support development on [GitHub Sponsors](https://github.com/sponsors/VoidedKN0X). In the app, tap your profile picture: **About** at the bottom of the You page has **Support CallCtrl** and **Report a bug**, which opens the [issue tracker](https://github.com/VoidedKN0X/CallCtrl/issues). Please report bugs and ideas with the [bug report and feature request forms](https://github.com/VoidedKN0X/CallCtrl/issues/new/choose). Starring the repo helps too.

Found a security problem? Please don't open a public issue; see [SECURITY.md](SECURITY.md).

## The Ctrl series

Native Android apps for self-hosted services and everyday tools, all with the same design.

| App | What it's for |
| --- | --- |
| [AdCtrl](https://github.com/VoidedKN0X/AdCtrl) | Control your AdGuard Home DNS server |
| [ArrCtrl](https://github.com/VoidedKN0X/ArrCtrl) | Radarr, Sonarr, Prowlarr, Bazarr, qBittorrent, autobrr and Cleanuparr in one app |
| [BeamCtrl](https://github.com/VoidedKN0X/BeamCtrl) | TV remote for phones with an IR blaster |
| **CallCtrl** (this app) | Phone app: dialer, call history and in-call screen |
| [CameraCtrl](https://github.com/VoidedKN0X/CameraCtrl) | Private camera with RAW (DNG) on every lens |
| [ChatCtrl](https://github.com/VoidedKN0X/ChatCtrl) | Matrix chat client for matrix.org and self-hosted Synapse |
| [ClockCtrl](https://github.com/VoidedKN0X/ClockCtrl) | Alarms, timers, stopwatch and world clock |
| [ContactCtrl](https://github.com/VoidedKN0X/ContactCtrl) | Contacts: browse, edit, import and export |
| [CraftyCtrl](https://github.com/VoidedKN0X/CraftyCtrl) | Manage Minecraft servers on Crafty Controller |
| [DateCtrl](https://github.com/VoidedKN0X/DateCtrl) | Calendar for the calendars on your phone and your accounts |
| [FilesCtrl](https://github.com/VoidedKN0X/FilesCtrl) | File manager with NFS, SMB, SFTP, FTP and WebDAV |
| [FrigateCtrl](https://github.com/VoidedKN0X/FrigateCtrl) | Watch and review your Frigate NVR cameras |
| [GalleryCtrl](https://github.com/VoidedKN0X/GalleryCtrl) | Private, offline gallery with an encrypted vault |
| [ImmichCtrl](https://github.com/VoidedKN0X/ImmichCtrl) | Photos and backup for your Immich server |
| [JellyCtrl](https://github.com/VoidedKN0X/JellyCtrl) | Movies, shows, music and Live TV from Jellyfin |
| [LauncherCtrl](https://github.com/VoidedKN0X/LauncherCtrl) | Minimal, private home screen |
| [LijnCtrl](https://github.com/VoidedKN0X/LijnCtrl) | Live De Lijn bus and tram departures |
| [LumenCtrl](https://github.com/VoidedKN0X/LumenCtrl) | Private, offline photo and video editor |
| [MailCtrl](https://github.com/VoidedKN0X/MailCtrl) | Email for Gmail and Proton Mail |
| [MapsCtrl](https://github.com/VoidedKN0X/MapsCtrl) | Private, offline maps and navigation |
| [MemoCtrl](https://github.com/VoidedKN0X/MemoCtrl) | Notes on your Memos server |
| [NaviCtrl](https://github.com/VoidedKN0X/NaviCtrl) | Music player for Navidrome servers |
| [PaperCtrl](https://github.com/VoidedKN0X/PaperCtrl) | Scan and browse documents on Paperless-ngx |
| [PrintCtrl](https://github.com/VoidedKN0X/PrintCtrl) | Bambu Lab printers through Bambuddy, and Spoolman spools |
| [ProxCtrl](https://github.com/VoidedKN0X/ProxCtrl) | Manage your Proxmox VE server |
| [RecorderCtrl](https://github.com/VoidedKN0X/RecorderCtrl) | Private, offline voice recorder |
| [RideCtrl](https://github.com/VoidedKN0X/RideCtrl) | Offline maintenance, fuel and mod log for your vehicles |
| [SeerrCtrl](https://github.com/VoidedKN0X/SeerrCtrl) | Request movies and shows on Seerr / Jellyseerr |
| [ShutterCtrl](https://github.com/VoidedKN0X/ShutterCtrl) | Remote control and photo transfer for Canon cameras |
| [SoundCtrl](https://github.com/VoidedKN0X/SoundCtrl) | JBL headphones and speakers, without the account |
| [SumCtrl](https://github.com/VoidedKN0X/SumCtrl) | Offline calculator, unit converter and work tools |
| [SynapseCtrl](https://github.com/VoidedKN0X/SynapseCtrl) | Administer your Matrix Synapse server |
| [TalkCtrl](https://github.com/VoidedKN0X/TalkCtrl) | Cardo intercom settings, radio and updates |
| [TapoCtrl](https://github.com/VoidedKN0X/TapoCtrl) | Tapo plugs and bulbs on your home network |
| [TextCtrl](https://github.com/VoidedKN0X/TextCtrl) | Private SMS and MMS messaging |
| [TrueCtrl](https://github.com/VoidedKN0X/TrueCtrl) | Monitor and manage your TrueNAS server |
| [WakeCtrl](https://github.com/VoidedKN0X/WakeCtrl) | Keep the screen awake, per app or while charging |
| [WrtCtrl](https://github.com/VoidedKN0X/WrtCtrl) | Manage your OpenWrt router |

## License

CallCtrl is licensed under the [GNU General Public License v3.0](LICENSE). © VKNXWorks

Fonts, bundled in the app (nothing is downloaded): [Space Grotesk](https://github.com/floriankarsten/space-grotesk) and [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), both under the SIL Open Font License 1.1 ([FONT-LICENSE-SpaceGrotesk.txt](FONT-LICENSE-SpaceGrotesk.txt), [FONT-LICENSE-JetBrainsMono.txt](FONT-LICENSE-JetBrainsMono.txt)).
