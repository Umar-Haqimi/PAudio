# PAudio privacy statement

Short version: PAudio keeps everything on your phone. It has no network access, no account, no analytics, no advertising and no crash reporting. Nothing leaves the device unless you export a profile file yourself.

PAudio is not a medical device (see the README). Hearing results are treated as health-adjacent personal data, so they are handled conservatively.

## No network, no tracking

- The app does **not** declare the `INTERNET` permission, so Android does not let it open network connections. The build fails if that permission (or `BLUETOOTH_CONNECT`, `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE`, any location permission, or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) appears in the merged release manifest: `./gradlew :app:verifyPermissions`.
- That task also enforces an allow-list, so nothing else can be added by accident (including by libraries).
- There is no analytics, telemetry, advertising or crash-reporting library.

## Permissions PAudio declares

The merged release manifest may contain only these (enforced by `verifyPermissions`):

| Permission | Why |
|---|---|
| `MODIFY_AUDIO_SETTINGS` | Required by Android to attach audio effects to other apps' audio sessions or to the global mix. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the system-wide equalizer running in the background (foreground service of type `specialUse`). |
| `POST_NOTIFICATIONS` | Shows the persistent notification (engine state and actions), the "which headphones are these?" prompt and the boot fallback. Optional: if you deny it, PAudio keeps working and Home says so. |
| `RECEIVE_BOOT_COMPLETED` | Start on boot. Off by default; only acts if you turned the option on. |
| `RECORD_AUDIO` | Optional, asked only if you tap to use the noise meter on the pre-test page. See below. |

PAudio does not ask for storage, media, Bluetooth, location, contacts or battery-optimization permissions. Your own songs for the A/B comparison and exported/imported files go through the system file picker, which grants access only to the file you choose.

## What is stored, and where

All of it is app-private storage on the device (not on shared storage), protected by Android's file-based encryption.

- **Room database:** listeners (a name you choose, default "Me"), headsets (the key, a display name, type, product name and, for Bluetooth, only the **last 5 characters** of the address, never the full address), profiles (name, compensation strength, bass/presence/treble settings, Personalized/Original state, seed colour), hearing tests (mode, time, duration, volume index, calibration level, an output-device snapshot, "ANC on" checkbox, thresholds per ear and frequency, the individual trial log, reliability) and preference tests (results and the list of comparison rounds).
- **DataStore:** app settings (engine mode, start on boot, onboarding done, default compensation strength, active listener, which headset maps to which profile).
- **SharedPreferences (service):** a flag that you turned the engine off and the list of headsets for which you chose "Don't ask again".

Hearing data is never written to logs. Release builds strip `Log.v/d/i`, and the code logs only exception class names and generic phrases (no thresholds, curves, listener names, device names or package names).

### Backup

`allowBackup` is on, but the backup rules exclude the database, SharedPreferences and DataStore from **cloud backup**. They are included in **device-to-device transfer** when you move to a new phone. To keep a copy yourself, use profile export.

## Microphone (noise meter only)

- The microphone is used only for the optional room-noise meter ("Quiet / OK / Too noisy") on the pre-test page, and only after you grant `RECORD_AUDIO` (a runtime permission).
- Samples are reduced in memory to a single loudness value. They are **never recorded, written to disk, logged, transmitted or kept**.
- Recording stops when you leave the pre-test page. If you deny the permission, the meter is hidden and the test works without it.

## Other apps' audio

To equalize your music apps, PAudio attaches an audio effect to the audio sessions they announce. It does not receive, read, record or store the audio itself, and it does not know what you are listening to. It sees an app's package name only to list it in the notification ("Active in ...") and ignores PAudio's own package. Package names are not stored or logged.

## The `<queries>` entry

The manifest has a `<queries>` element asking for launcher apps (an intent with `MAIN` / `LAUNCHER`). It needs no permission and exists only so the notification can show a music app's name instead of its package name on Android 11 and later. Apps that are not visible are shown by package name. PAudio does not store or transmit the list.

## Boot receiver and notifications

- The boot receiver handles only the system's `BOOT_COMPLETED` broadcast, and only does anything if you enabled "Start on boot" and finished onboarding. It starts the service, or, if Android does not allow that, posts a "Tap to start PAudio" notification.
- Notification actions (Personalized/Original, Open app, Turn off) use explicit, immutable intents, and the notification is private on the lock screen.
- The service listens for the system's audio-session broadcasts with a receiver that other apps could in principle spoof; PAudio validates each event (session number, package, a limit of 16 live sessions) so a forged event can at worst create an inert effect.

## Export and import of profiles

- Export writes a JSON file (schema v1) to a location **you** pick through the system file picker. It contains the listener name, the headset's key/name/type/last-5-address, the profile settings, and the hearing and preference test data (and, if present, the trial log). It does not contain the full Bluetooth address. After you save it, it is an ordinary file under your control: treat it as health-adjacent data, and note that anyone you give it to can read it.
- Import reads a file you pick. It is treated as untrusted input: maximum 2 MB, strict parsing, out-of-range numbers are clamped or the file is rejected, and limits on the number of tests and trials. A malformed file is refused with a message; it never crashes the app or changes your data partway.
- Nothing is uploaded; there is no sharing service.

## Deleting your data

Delete a profile in the app (this removes its tests), or clear the app's storage or uninstall PAudio in Android settings. Exported files are separate and must be deleted by you.

## Changes

If PAudio ever needed a network permission or a new data type, this document and the permission allow-list would have to change first; the build would otherwise fail.
