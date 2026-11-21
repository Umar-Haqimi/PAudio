# PAudio on-device test checklist

Everything here needs a real phone and real earphones; none of it can be verified in CI or in the cloud container. It consolidates the SPEC §7.3 checklist and every "device-only check" recorded in `docs/PROGRESS.md` (M6, M7a, M7b, M7c, M8a, M9a, M9b, M10, M11) and `docs/DECISIONS.md` (§4-§8, §19-§24).

## How to use

- Run on at least **two devices**: one Pixel-class device and one Samsung or other OEM device, each with **wired/USB-C and Bluetooth** earphones (SPEC §7.3, §9). Record the results in the table at the end.
- Tick a box only if the **expected result** was observed. If it was not, write the observed behaviour next to the item; do not tick.
- Use a debug build for logs (`adb logcat`), but verify the final behaviour with a release (R8) build signed with your own key.
- Copy rules apply to what you read in the app: results must say "estimated", "approximate" or "may"; nothing should say "diagnosis", "detected hearing loss" or "your hearing is damaged" (DECISIONS §17). Note any screen that does.
- Safety: keep the volume low when you start. The tests are designed not to exceed the calibrated level, but stop if anything is uncomfortably loud.

Device under test: ____________ Android/API: ____ Earphones: ____________ (wired / USB-C / Bluetooth, codec ____)  Build: ________

---

## 1. Engine and effect (system-wide EQ)

- [ ] **DynamicsProcessing creates on a real session.** Start a local player, turn PAudio on, play. Expected: Home shows Active with the player's name; no "refused its effect" error. (DECISIONS §19; `DynamicsProcessingHardwareTest`, `@RequiresHardware`)
- [ ] **Channel mapping by ear.** Apply -30 dB to channel 0 only (hardware test or debug build). Expected: the **left** ear gets quieter; right unchanged. (SPEC §7.3; channel 0 = left)
- [ ] **Readback and the band ladder.** Check logcat or the diagnostics for the ladder step (MAIN -> FB51 -> SPEC40). Expected: on most devices MAIN holds; on AIDL-HAL devices a step down is acceptable. Record the ladder and the device. (DECISIONS §1, §19)
- [ ] **Audible result.** Switch Personalized/Original on a track with sustained highs. Expected: a clear but click-free change; no distortion at a loud passage (preamp and limiter protect against clipping).
- [ ] **Enable/disable clicks.** Toggle Personalized/Original ten times while music plays. Expected: no audible click or pop. (DECISIONS §3, §19)
- [ ] **Updating while playing.** Move the strength slider on Home during playback. Expected: smooth change, no dropouts, no clicks.
- [ ] **Priority against another EQ app.** Run Wavelet or Poweramp EQ (or another system-wide EQ) at the same time. Expected: PAudio shows **Conflict (another EQ app)** or the other app loses control; no crash; tapping retry behaves sensibly; stopping the other app lets PAudio recover. (SPEC §7.3, DECISIONS §3, §13)
- [ ] **Priority the other way round.** Start PAudio while the other EQ app is already running and then close the other app. Expected: Conflict at creation, and `retry` works afterwards.
- [ ] **Legacy mode.** Settings > Legacy (whole device), play a player that is not processed in Session mode. Expected: the state shows Legacy and the sound changes, or a clear "refused" message. Switching back to Per app must not leave both active (never double EQ). (SPEC §2.2 #14)
- [ ] **Session mode with no announcing player.** Use a player that does not announce its session. Expected: after about 60 s of music, the hint suggests Legacy mode or the compatibility list. (DECISIONS §19)
- [ ] **Speaker output.** Play through the phone speaker. Expected: status "Speaker output (not processed)"; the sound is unchanged. Plug in/connect earphones: processing starts with the right profile.
- [ ] **Device switch with a playing app.** While music plays, switch wired <-> Bluetooth and back. Expected: each headset gets its own profile; **no stale curve** from the previous headset is audible; a headset without a profile shows the notification with "Create profile" / "Use ...". (DECISIONS §20)
- [ ] **Unknown headset prompt.** Connect an unseen headset. Expected: one notification per headset ("Don't ask for this device" persists); audio is not blocked meanwhile.
- [ ] **Wired collision question.** With two listeners that both have a profile bound to the same wired jack, connect the wired headset. Expected: the "Which headphones are these?" dialog; the answer applies until disconnect. (DECISIONS §20.1)
- [ ] **Idle sessions.** Pause a player for more than 10 minutes, then resume. Expected (known limitation): a player that does not announce a new session may lose the effect and the hint appears; note what happens. (DECISIONS §19)
- [ ] **16-session cap and spoofing.** Optional: open many player apps/sessions. Expected: no crash; the oldest sessions are evicted; no unbounded growth.
- [ ] **Bypass while PAudio plays.** Start music through another app with Personalized on, then run the hearing test / A/B / preference screens. Expected: the system-wide EQ is **bypassed** while those screens play sound (status "Bypassed (testing)") and **restored** when you leave. After the app is force-stopped mid-test, the engine is not left bypassed on the next service start. (SPEC §2.2 #1, DECISIONS §19)

## 2. USB DAC and sample rate

- [ ] **Mixer rate per route.** With a USB DAC that runs at a different rate than the phone's built-in output (for example 44.1 vs 48 kHz or 96 kHz), compare `PROPERTY_OUTPUT_SAMPLE_RATE` with the actual mixer rate for each route. Expected: the curve is planned for the real rate; no audible tilt or level step after switching routes. (DECISIONS §20.1)
- [ ] **USB headset path.** Run the test tones and the engine through a USB-C DAC/headset. Expected: calibration lands in the target window; the volume lock holds; `getStreamVolumeDb` for the USB route is sane or the app falls back to index steps. (DECISIONS §4)
- [ ] **Hardware volume DACs.** On a DAC with its own volume control, change the hardware volume during a test. Expected: the test is not silently corrupted: note whether PAudio can detect it and what it shows.

## 3. Bluetooth

- [ ] **Codec level check.** With a Bluetooth headset, run calibration and the test. Expected: tones are audible at the calibrated level; no missing 14 kHz tone due to codec low-level pruning (SPEC §2.2 #13: system volume is lowered during calibration to keep the digital level high). Note the codec (SBC/AAC/LDAC/LC3).
- [ ] **Codec comparison.** Measure the 14 kHz threshold on Bluetooth and wired with the **same earphone model** (or note the difference). (SPEC §7.3)
- [ ] **Absolute volume.** On a Bluetooth sink with absolute volume (AVRCP), check calibration. Expected: calibration re-measures and lands in the window, or shows the "volume range doesn't suit testing" message; no endless loop. (DECISIONS §4)
- [ ] **Address anonymisation.** Pair a Bluetooth headset; open Profiles. Expected: the headset appears with a stable key; only the last 5 characters of the address are shown/stored anywhere (check an exported profile). Compare behaviour on API 28-33 (full address delivered by the OS) and on newer builds (anonymised). (DECISIONS §7)
- [ ] **Dead object recovery.** Turn the Bluetooth headset off mid-test, then on again. Expected: the test pauses with clear copy; resuming re-opens the stream (maximum 3 re-opens, then an audio error), no crash. (PROGRESS M9a)
- [ ] **Bluetooth switching.** Move between two paired headsets during playback. Expected: the profile follows the active device.

## 4. Boot and service lifecycle

- [ ] **Foreground service starts (API 34-37).** Complete onboarding. Expected: the persistent notification appears and the engine runs; the `specialUse` service starts without a crash on API 34, 35, 36 and 37 devices. (DECISIONS §8)
- [ ] **Boot, opt-in on (API 35 and newer).** Enable "Start on boot", reboot. Expected: either the service runs after boot, or the notification "**Tap to start PAudio**" appears and tapping it starts the service. No crash on Android 15 or newer. (SPEC §7.3)
- [ ] **Boot, opt-in off.** Reboot. Expected: nothing starts; no notification.
- [ ] **Boot after "Turn off".** Press "Turn off" in the notification, reboot with opt-in on. Expected: the service does not revive itself. An explicit start in the app clears that.
- [ ] **Boot with notifications denied.** Deny the notification permission, reboot. Expected: if Android blocks the service start, the fallback is silent and Home shows "Engine not running" or equivalent.
- [ ] **Sticky restart after process kill.** Kill the app process (`adb shell am kill my.umarh.paudio`) while playing. Expected: the service restarts, restores mode, Personalized/Original and the profile, and is **not** left bypassed. (DECISIONS §20)
- [ ] **Notification actions from the shade.** Personalized <-> Original, Open app, Turn off all work from the shade and the lock screen (content is private on the lock screen).
- [ ] **Notification permission denied.** Expected: the denial copy on Home; the engine still runs.
- [ ] **`<queries>` app labels (API 30+).** With a visible player, the notification says "Active in <app name>"; for apps not visible it falls back to the package name. (PROGRESS M7c)
- [ ] **Battery behaviour.** Leave it running for a few hours with the screen off. Expected: no excessive drain; the service survives Doze (the app never asks for battery-optimization exemption).

## 5. Hearing test

Run both Quick and Thorough on each earphone type.

- [ ] **Channel check level and ear order.** The channel-check tone (-45 dBFS, raised in 5 dB steps to at most -30 dBFS) is audible but **not loud** on real earphones at 0.5 x max volume. Expected: the left/right question is correct; **swapping the buds is caught** ("Your earbuds may be swapped or in mono mode"); mono audio is caught. (PROGRESS M9a, SPEC §7.3)
- [ ] **No clicks at extremes.** At the highest test levels at 1 kHz and 14 kHz, tones start and end without audible clicks (25 ms ramps). (SPEC §7.3, §2.2 #4)
- [ ] **Calibration lands in the window.** Expected: calibration reaches the target window within two reruns, or falls back to the volume-keys prompt; the volume lock holds.
- [ ] **Refused `setStreamVolume`.** On a device or state where Android refuses the volume change (safe-volume warning, headset volume limits), expected: PAudio notices from the read-back, shows "We couldn't change the volume automatically..." or asks for the volume keys, **plans from the real volume index**, and restores the original index afterwards. It never loops. (DECISIONS §5, PROGRESS M9a)
- [ ] **Safe-volume / CSD.** On a device with sound-dose (CSD) warnings, expected: no unprompted volume jump goes unnoticed; if CSD lowers the volume mid-test, the test pauses ("The volume changed...") rather than recording wrong levels. Note region/Android version. (DECISIONS §5)
- [ ] **Volume keys pause the test.** Press a volume key mid-test. Expected: the test pauses with clear copy, and resume restarts only the interrupted point; resume restores the locked index or asks for the keys.
- [ ] **Disconnect mid-stimulus.** Unplug the headset or turn Bluetooth off mid-tone. Expected: the test pauses; no loud burst on the phone speaker.
- [ ] **Phone call / audio focus loss.** Receive a call or start another app's playback mid-test. Expected: the test pauses; the system-wide EQ is restored when you leave the test.
- [ ] **App backgrounded.** Switch apps mid-test. Expected: the test pauses; a pause longer than 5 minutes aborts the test and everything is restored. (DECISIONS §23)
- [ ] **Pause reasons and resume copy.** Verify each pause message is understandable and resuming is always a deliberate tap.
- [ ] **Environment warnings.** With mono audio on (including the Samsung `mono_audio` setting, which PAudio does not check), a non-zero audio balance, or spatial audio on: expected blocking warnings for the settings PAudio can read; **record whether the Samsung `mono_audio` setting is missed**. DND shows live status. (DECISIONS §6, PROGRESS M6)
- [ ] **Noise meter.** Grant the microphone permission on the pre-test page; check Quiet / OK / Too noisy thresholds in a quiet room, with music, and with talking. Expected: the meter hides if the permission is denied and stops when you leave the page (the microphone indicator on screen goes off). Nothing is stored.
- [ ] **TalkBack during the test.** With TalkBack on, run a test. Expected: stimuli are heard clearly through TalkBack speech; the "Heard"/"Not heard"/"Level saved" speech may overlap the next gap (400-1200 ms): record whether it interferes with the next tone. No announcements during a stimulus. (SPEC §2.2 #20, DECISIONS §23)
- [ ] **Haptic timing (Thorough dial).** Turn the dial: a haptic tick fires only in a silent gap or after the response, never during a pulse. Check on a device with strong haptics.
- [ ] **UI sounds off.** Tap the hear button and the answer buttons. Expected: no system click sound or vibration during stimulus playback.
- [ ] **Thorough dial usability.** The dial works with a finger, with the Louder/Quieter buttons and with TalkBack custom actions, and shows no numbers.
- [ ] **Timing.** Quick takes about 6-8 minutes and Thorough about 7-9 minutes including calibration and the channel check. Record the actual times. (DECISIONS §18, §23)
- [ ] **Test-retest.** Two **Thorough** runs in the same session with the same headset. Expected: at least 90 % of points within +-10 dB. (SPEC §7.3, §9)
- [ ] **Reliability prompt.** Answer "heard" at random in a Quick test. Expected: the guessing prompt appears (redo or keep) and the saved test is marked unreliable.
- [ ] **Volume restore.** After completing, aborting or killing a test, the system volume is back at the original index (note: after process death mid-test the calibrated volume remains, a known gap).

## 6. Results, A/B and preference test

- [ ] **Click-free A/B swap.** On Results, toggle Original/Personalized repeatedly while a clip plays, on wired and Bluetooth. Expected: no clicks or dropouts (20 ms duck). (PROGRESS M9b, M7b)
- [ ] **Loop seam.** Let each clip (Discipline, Echoplex, 34 Ghosts IV) loop several times: the Results clip (about 30 s) and the preference loop (the first 12 s of each clip). Expected: the seam is not annoying (the loop is a re-seek, **not** sample-gapless, so a tiny gap is possible; record how audible). Also listen once to confirm the clip cut points do not cut a vocal or section boundary. (DECISIONS §12, PROGRESS M7b)
- [ ] **Loudness match by ear.** On real earphones, compare Original and Personalized and each preference option. Expected: no option sounds clearly louder than the others; a +6 dB bass option is not "louder" in a way that biases the choice. (DECISIONS §21, SPEC §2.1.5)
- [ ] **Audio focus.** Start a call or another media app while the A/B plays. Expected: Play/Pause follows the real player state; the bypass token is released when playback stops and the screen is left.
- [ ] **User's own file.** Pick mp3, m4a, flac and ogg files through the picker. Expected: each plays; an unreadable file shows the inline message and falls back to the demo clip; no storage permission is requested.
- [ ] **Bypass lifetime.** While Results or a preference round is on screen and the app goes to the background, the system-wide engine is restored; coming back re-creates a paused player. (DECISIONS §21, §24)
- [ ] **Preference gate by ear.** Choose A/B is enabled only after about 2 s of **real audible** playback on each side. Check with a stopwatch that the gate opens after roughly 2 s per side and not earlier (`listenTicks` accuracy), also across focus loss and pausing.
- [ ] **Preference rounds.** Complete a test: nine comparisons at most, "Can't tell" ends an axis, "Try another song" cycles clips and keeps the round; the final fine-tune sliders change the sound live; Save is reflected in the system-wide sound.
- [ ] **Treble axis.** With the dark "34 Ghosts IV" clip, check whether the 8 kHz treble difference is audible at all; note whether "Can't tell" is the common outcome. (DECISIONS §12)
- [ ] **System-wide engine during preference screens.** Shown as bypassed only while the A/B screen is on screen.

## 7. Hearing health

- [ ] **Estimated wording.** Open Hearing health after a test. Expected: numbers read "About X dB (estimated)"; the grade is labelled approximate; wording never says "diagnosis", "detected hearing loss" or "your hearing is damaged". (DECISIONS §17)
- [ ] **Disclaimer.** The disclaimer is always visible, including inside the "How to read this" sheet, cannot be dismissed, and stays after scrolling and at 200 % font size.
- [ ] **Audiogram colours and shapes.** Right ear red circle, left ear blue cross; readable in light and dark themes and on an OLED at low brightness.
- [ ] **Trend.** After two tests with the same profile and headset, the trend chart appears; with a different headset it does not mix them.
- [ ] **Empty and error states.** No test yet, a deleted profile and an incomplete test each show the right message.

## 8. Profiles, export/import, settings

- [ ] **Export and import with a real file manager.** Export a profile to local storage and to a cloud storage provider through the picker; import it back, on the same and on another phone. Expected: the conflict dialog (replace / import as Any) works; malformed or oversized files show an inline error.
- [ ] **Move/duplicate/delete.** Moving offers a retest; delete asks for confirmation and removes the tests.
- [ ] **Device transfer.** Use a device-to-device transfer to a new phone. Expected: profiles transfer; cloud backup does not contain them.
- [ ] **Theme.** The profile seed colour changes the app theme; dynamic wallpaper colours (Android 12 and newer) and the static scheme (Android 9-11) both render.
- [ ] **About screen.** All attributions appear (Nine Inch Nails CC BY-NC-SA with the no-monetization note, Roboto Flex OFL, Material Symbols Apache 2.0, library licenses) and match the README.

## 9. Accessibility

- [ ] **TalkBack, whole app.** Navigate onboarding, Home, hearing test, Results, Hearing health, preference test, Profiles and Settings with TalkBack. Expected: every control has a sensible label; the butterfly and EQ graph have a spoken description (wings labelled in text); the hear button works with a double tap.
- [ ] **TalkBack, level dial.** Custom actions raise and lower the level; no numbers are announced.
- [ ] **200 % font scale.** Set the largest font size (and display size). Expected: no clipped or overlapping text on the pre-test page, hear button screens, Results, A/B card, preference round card and fine-tune sliders; the disclaimer scrolls inside its capped area; buttons remain reachable.
- [ ] **Touch targets.** Primary controls are at least 48 dp.
- [ ] **Contrast.** Light and dark themes, with the seed colour of an extreme profile, remain readable.
- [ ] **Reduced motion.** With animations turned off (animator duration scale 0), the butterfly, EQ graph and hear button do not animate. (PROGRESS M8a)
- [ ] **Landscape and large screens.** The app is usable in landscape and on a tablet or foldable (no cut-off dial or buttons).
- [ ] **Colour-blind safety.** Audiogram ears and WHO bands are distinguishable by shape and label, not by colour alone.

## 10. Release build smoke test

- [ ] Run a minified, signed release build through the complete flow: onboarding, Quick test, Results, preference test, Hearing health, export/import, system-wide EQ with a player, reboot. Expected: no R8-related crash, nothing missing from the About screen. (SPEC §6, §9)
- [ ] `adb logcat` during the flow shows no thresholds, curves, listener names, device names or package names.
- [ ] **Full flow on the minified release APK (M12).** Build with `./gradlew :app:assembleRelease` (unsigned unless `keystore.properties` or the `PAUDIO_KEYSTORE_*` variables exist; sign with `apksigner` and a throw-away key for the test, see DECISIONS §26). Install over a clean profile and run the whole flow above. Expected: navigation works to every destination (type-safe routes are R8-kept), profile **export then import round-trips** (JSON fields intact), Room opens (no `PAudioDatabase_Impl` missing), Hilt injects every screen, the A/B player plays the demo clip.
- [ ] **Logcat privacy grep (M12, SPEC §9).** Capture `adb logcat -d` across the release flow and run `grep -iE "threshold|\bhl\b|profile|listener|[0-9A-F]{2}(:[0-9A-F]{2}){5}" capture.txt`. Expected: no hearing value, name, device name or Bluetooth address. The static check `verifyNoSensitiveLogging` guards the sources; this guards what libraries print.
- [ ] **Backup rules (M12).** `adb shell bmgr backupnow my.umarh.paudio` then inspect the transport: the database, SharedPreferences and `datastore/` are NOT in the cloud backup. Device-to-device transfer to a second phone keeps profiles. (docs/PRIVACY.md)
- [ ] **Release APK size and start-up (M12).** Cold start to Home in under ~1.5 s on a mid-range device, no jank in the butterfly draw-in or EQ graph toggle (GPU rendering profile or `dumpsys gfxinfo` janky frames under ~5 %).
- [ ] **TalkBack and stimuli (M12, trap 20).** With TalkBack on, run the Quick test: nothing is spoken while a tone plays (focus changes, "Heard"/"Not heard" are only spoken after your answer), and the hear button is operable with double-tap.

---

## Results log

| Device | Android / API | Earphones | Sections passed | Failed items and notes | Date | Tester |
|---|---|---|---|---|---|---|
| | | | | | | |
| | | | | | | |
