# Player compatibility

PAudio's default **Session mode** only processes apps that announce their audio session to Android (the `OPEN_AUDIO_EFFECT_CONTROL_SESSION` broadcast). Apps that do not announce it need **Legacy mode** (whole-device, session 0), which Android deprecates and which only works on some devices. This table is filled in by testing on real devices (see [HARDWARE_TEST.md](HARDWARE_TEST.md)); it is a template and no player has been tested yet.

How to fill a row: play music in the player with PAudio on and Personalized selected, then check:

- **Announces session:** yes if Home shows the player under "Active in ..." in Session mode; no if it stays "Waiting for a player" (after about 60 s of music, PAudio shows the hint to try Legacy mode).
- **Session-mode works:** yes if switching Personalized/Original audibly changes the sound in Session mode with no Conflict or Error state.
- **Legacy needed:** yes only if Session mode does not work but Legacy mode does; "no" if Session mode is enough; "n/a" if neither works.
- **Notes:** version tested, per-device quirks, offload/gapless/spatial audio settings, Bluetooth codec, anything surprising.

Test one player at a time, with other EQ apps off, once with wired and once with Bluetooth earphones.

| Player | Version | Device / Android | Announces session (yes/no) | Session-mode works | Legacy needed | Notes |
|---|---|---|---|---|---|---|
| Spotify | | | | | | |
| YouTube Music | | | | | | |
| YouTube (app / browser) | | | | | | |
| Poweramp | | | | | | |
| VLC | | | | | | |
| Local files player (system default) | | | | | | |
| Samsung Music or the OEM default player | | | | | | |
| Chrome / Firefox (web audio) | | | | | | |
| Podcast app (e.g. Pocket Casts) | | | | | | |
| Other: | | | | | | |

## What the code and decisions already establish

- PAudio **ignores its own package** and never processes its own test tones or A/B player (those hold a bypass token instead). It also ignores invalid session numbers (0 or negative), and a CLOSE whose package differs from the OPEN's. (DECISIONS §19)
- It keeps at most **16 live sessions**; the oldest are evicted. A session is released when closed, or when idle for more than 10 minutes while no music is active. A player that stays paused for more than 10 minutes and then resumes **without announcing a new session** can lose the effect; the no-session hint then appears. (DECISIONS §19)
- The session receiver is registered at run time by the running service, not in the manifest, because Android does not deliver implicit broadcasts to manifest receivers. Only players that actually send the broadcast can be processed in Session mode. (DECISIONS §13)
- **Another EQ app** that controls the same effect produces the Conflict state rather than a fight (priority 100: it beats priority-0 users but not apps that use the maximum priority). (DECISIONS §3)
- **Speaker output is never processed**, whichever player is used.
- Player-side features can defeat any equalizer: some players use offload or their own DSP paths; note them in the Notes column when found.
- PAudio does not use the `DUMP` permission in this release, so it cannot discover sessions that are not announced (a possible later option, SPEC N1).
