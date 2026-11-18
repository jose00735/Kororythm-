# Kororythm — Architecture Blueprint

_Nov 17, 2026 · living version: https://claude.ai/artifact/8G9purtHvHZQCbdq2Ng2Yf_

## Vision and scope

Kororythm is an Android music player for FLAC and MP3 with a classic Winamp layout and a neon-grid look from 2000s sci-fi. It plays audio bit-perfect, manages a library through wizards, and adds DJ-style transitions and plug-in animations for DACs and headphones.

**MVP (first build):**

- Play FLAC (16/24-bit, up to 192 kHz) and MP3, gapless, with a playlist
- Classic main panel, 10-band EQ, playlist editor and spectrum/oscilloscope visualizer
- Neon-grid skin and the parrot icon
- Import wizard (folders to library)
- DAC and headphone detection with their animations

**Later:** album-creation wizard, DJ decks and FX, tag editing, ReplayGain scanning, skin packs, home-screen widget, Android Auto.

**Out of scope for v1:** streaming services, video, iOS and desktop builds.

The skin is an original design in the neon-grid style. It uses no film logos, names, fonts or characters, so the project stays safe to publish open source.

## Tech stack

Native Android in Kotlin, with Jetpack Compose for the UI and AndroidX Media3 for playback. Compose's Canvas and AGSL shaders (Android 13+) handle the glow, grid and animations. Min SDK 26 (Android 8); bit-perfect DAC output turns on only on Android 14+.

| Layer | Choice | Why |
| --- | --- | --- |
| Language / UI | Kotlin + Jetpack Compose, Canvas + AGSL shaders | Custom neon drawing at 60 fps, easy animation APIs |
| Playback | Media3 ExoPlayer + MediaSessionService | Gapless, background play, lock screen, notification, Bluetooth and headset buttons |
| FLAC decode | Media3 FLAC extension (libflac) | Reliable 24-bit and high sample rates on every phone |
| MP3 decode | Media3 built-in (MediaCodec) | Standard, hardware where available |
| DSP | Custom Media3 `AudioProcessor` chain, float output on | Same EQ sound on every phone (no vendor `audiofx` quirks) |
| Spectrum tap | Media3 `TeeAudioProcessor` + FFT | No microphone permission (the system Visualizer needs one) |
| DAC output | `AudioMixerAttributes` with `BIT_PERFECT` (API 34+) | Sends audio untouched to USB DACs |
| Device events | `AudioManager.registerAudioDeviceCallback` | Plug/unplug of USB DACs, wired and Bluetooth headphones |
| Library DB | Room (SQLite) with an FTS4 table | Fast search, Kotlin Flow updates |
| Scanning | MediaStore + Storage Access Framework folders, WorkManager jobs | Finds music, survives the app closing |
| Tags | JAudioTagger (Android fork) | Read and write ID3 and FLAC Vorbis comments |
| Metadata lookup | MusicBrainz + Cover Art Archive over OkHttp/Retrofit, Coil for images | Free, open data for the wizards |
| DJ native (phase 3) | Oboe (C++) + SoundTouch for time-stretch | Low latency for scratch, key lock and decks |
| DI / build | Hilt, Gradle, GitHub Actions | Signed APK and Play Store bundle (AAB) |

Sources: [Android: improve audio playback](https://developer.android.com/media/platform/improve-audio-playback), [AOSP: preferred mixer attributes on USB](https://source.android.com/docs/core/audio/preferred-mixer-attr)

## System architecture

Commands go down to the playback service; state and sound data come back up.

```mermaid
flowchart TB
  subgraph UI["App UI (Jetpack Compose)"]
    S[Screens + skin]
    WZ[Wizards]
    AN[Plug animations]
    VZ[Visualizer]
  end
  BR["MediaController + Flows: commands go down; state, spectrum, devices come up"]
  subgraph CORE["Playback service and core modules (Kotlin)"]
    direction LR
    EXO["ExoPlayer<br/>gapless queue"] --> DEC["Decode<br/>FLAC + MP3"] --> PROC["Processors<br/>EQ, ReplayGain"] --> DJ["DJ FX<br/>transitions, FX"] --> SINK["Audio sink<br/>AudioTrack, DAC"]
    LIB["Library<br/>Room + FTS4, scan"] --> EXO
    META["Metadata<br/>tags, MusicBrainz"]
    DEV["Device watch<br/>USB DAC, headphones"]
    SINK --> FFT["Visualizer FFT<br/>Tee tap, 60 fps"]
  end
  UI <--> BR <--> CORE
```

Audio runs left to right inside the playback service. The library feeds content URIs into ExoPlayer's queue, the tee tap copies samples to the FFT for the visualizer, and device events go up to the UI to trigger the animations.

## Audio engine

The engine is Media3 ExoPlayer running inside a `MediaSessionService`, so music keeps playing with the screen off and shows on the lock screen. Audio is decoded to float and passes through Kororythm's own processor chain before the system audio sink. In bit-perfect mode the chain is bypassed.

1. **Source:** ExoPlayer playlist built from library tracks (content URIs). Gapless is native in Media3; it reads LAME/iTunes gap info for MP3 and is sample-exact for FLAC.
2. **Decode:** FLAC through the Media3 FLAC extension (libflac); MP3 through MediaCodec. Float output is on, so 24-bit sources keep full resolution.
3. **Processor chain** (custom `AudioProcessor`s; each stage can be toggled):
   - ReplayGain (track or album) with a peak limiter
   - Preamp + 10-band graphic EQ (Winamp bands 60 Hz to 16 kHz), as biquad peaking filters
   - DJ transition FX (see DJ module)
   - Optional "Boost" profile: loudness curve + soft clipper, with 3 dB headroom
   - `TeeAudioProcessor` tap: copies samples to the FFT
4. **Output:** Media3's audio sink to `AudioTrack`. With a USB DAC on Android 14+, Kororythm sets preferred mixer attributes to the file's native rate and bit depth, and to `BIT_PERFECT` when the phone supports it.
5. **Visualizer:** FFT (2048 points, Hann window) on a background thread; 64 bars + oscilloscope samples go to Compose through a `StateFlow` at 60 fps.
6. **UI link:** the Compose screens talk to the service through a Media3 `MediaController`, so the widget, notification, Bluetooth buttons and the app all control the same player.

**Player state machine:** Stopped → Loading → Playing ⇄ Paused → Stopped, plus Transitioning (when DJ FX runs between tracks). Every command (play, seek, next) is a message on one channel, so state changes stay ordered.

**Bit-perfect rule:** USB DAC on Android 14+ that offers BIT_PERFECT + EQ, ReplayGain, Boost and DJ FX all off + file format the DAC supports. The UI shows a "BIT-PERFECT" lamp only when all of these hold.

## Device detection and plug animations

A `DeviceWatcher` registers `AudioManager.registerAudioDeviceCallback`. Each added or removed `AudioDeviceInfo` is sorted by its type, and the app reads its name and supported sample rates and encodings to show what it can do.

| Kind | How it's detected | Animation | Engine action |
| --- | --- | --- | --- |
| USB DAC | `TYPE_USB_DEVICE` or `TYPE_USB_HEADSET` | **"Power Surge":** neon lines race in from the screen edges, a panel shows the DAC name and its max format (e.g. 32-bit / 384 kHz), a BOOST gauge sweeps to full, plus a short haptic pulse | Offers bit-perfect mode (Android 14+), loads that DAC's saved EQ profile |
| Wired headphones | `TYPE_WIRED_HEADPHONES` or `TYPE_WIRED_HEADSET` | **"Wings Up":** the parrot spreads its wings, its headphones glow, a ring pulses out from the cups | Loads the headphone EQ profile |
| Bluetooth headphones | `TYPE_BLUETOOTH_A2DP` or `TYPE_BLE_HEADSET` | "Wings Up" in blue, with the codec shown when the phone reports it | Loads that headset's profile |
| Phone speaker / other | Anything else | Short cyan scan line | Loads the default profile |

- **Unplug:** the reverse animation (lines retract, wings fold). Media3 pauses on `ACTION_AUDIO_BECOMING_NOISY`, so music never blasts from the speaker.
- **Debounce:** events within 500 ms are merged, because USB DACs often show up as several devices.
- **Profiles:** stored per device (type + name) in Room: EQ, preamp, output mode, volume.
- **USB permission:** using a DAC through the normal Android audio path needs no USB permission prompt; Kororythm never talks to the USB device directly.
- **Honesty note:** "Boost" is a loudness and EQ profile plus headroom management. The real quality gain from a DAC comes from bit-perfect output, which is what the BIT-PERFECT lamp shows.

## DJ module

Pressing Next (or a track ending) runs the chosen **transition**, so skipping a track sounds like a DJ mix instead of a hard cut. A DJ panel slides up from the player screen to show the controls. Phase 1-2 transitions run in Kotlin with two ExoPlayer instances and volume ramps; the full DJ deck in phase 3 moves to a native Oboe engine.

| Transition | What you hear | Default length |
| --- | --- | --- |
| Cut | Instant switch (classic Winamp) | 0 s |
| Crossfade | Equal-power fade between both tracks | 4 s |
| Scratch | Short vinyl-scratch sample, then the next track drops | 0.8 s |
| Rewind | Current track spins backward fast, then the next track drops | 1.2 s |
| Echo out | Delay tail on the outgoing track, next one fades in | 3 s |
| Filter sweep | Low-pass sweep closes the old track, high-pass opens the new one | 4 s |
| Vinyl brake | Pitch and speed fall to zero like a turntable stopping | 1 s |
| Beat-match (later) | Matches BPM and lines up beats before crossfading | 8 bars |

**DJ panel (phase 3):**

- Two decks (A/B) with a crossfader, waveform and BPM readout
- Pitch/tempo ±8% with key lock (time-stretch through a WSOLA/phase-vocoder stage)
- Hot cues (4 per track) and loops (1, 2, 4, 8 beats)
- FX pads: echo, flanger, filter, bitcrush, gate
- BPM and beat grid worked out at import time and stored in the library
- Button sounds: short UI clicks and "whoosh" sounds for skin buttons, which you can turn off. They play through SoundPool, separate from the music stream, so they never end up in the mix.

All transition sounds are original or CC0 samples, kept in `app/src/main/assets/sfx/`.

## Library and wizards

The library is a Room database inside the app's private storage, with tables for tracks, albums, artists, playlists, device profiles and DJ data (BPM, cues). Music files stay where they are; the database stores their content URIs and tags. Scans and analysis run as WorkManager jobs, so they finish even if you leave the app.

**Import wizard ("Upload to the Grid"):**

1. Grant the music permission (READ_MEDIA_AUDIO) and pick folders through the system folder picker; new files are picked up automatically
2. Scan: walk the folders in parallel, read tags and cover art, hash audio for duplicates
3. Review: tracks with missing tags are flagged, with one-click MusicBrainz suggestions
4. Analyze (in the background): duration, ReplayGain, BPM, waveform preview
5. Done: summary screen with counts, plus the "Upload complete" animation

**Album-creation wizard ("Compile Program"):**

1. Pick tracks from the library or a folder
2. Set album title, artist, year, genre and cover (file, or Cover Art Archive search)
3. Order tracks by drag and drop, with auto-numbering and disc numbers
4. Choose to write tags into the files (Android asks you once for write access to that folder) or keep the album as library-only
5. Optional: export as a folder copy with a clean naming scheme (`Artist/Year - Album/NN - Title.flac`)

**Other wizards:** first-run setup (permissions, music folders, test tone, notification style), smart-playlist builder (rules like genre = X and BPM > 120), and a DAC setup wizard that runs on first plug-in.

Search uses FTS across title, artist, album and genre, and returns results as you type.

## UI, skin engine and icon

**Screens (Winamp layout on a phone):** the player screen stacks the three classic Winamp panels vertically: main panel (transport, seek bar, time display, kbps/kHz readout, mini visualizer), EQ panel and playlist panel. Each panel can roll up to a thin "shade" bar, like Winamp's windowshade mode. In landscape and on tablets they sit side by side. Library, wizards and the DJ deck are full screens. A mini-player bar follows you through the app.

**Neon Grid skin (default):**

- Black background with a dark-blue perspective grid floor, cyan (#00E5FF) and orange (#FF6A00) light lines
- Thin rounded chrome with a soft outer glow; buttons light up on press with a short scan line
- Readouts in a geometric display font (an open-licence font such as Orbitron, bundled)
- Visualizer modes: light-cycle bars, oscilloscope trail, and a tunnel grid that pulses on the beat
- Startup: the grid draws itself from a horizon line, then the panels fade in

**Skin engine:** a skin is a folder with `skin.json` (colours, fonts, layout positions) and vector sprites, mapped onto a Compose theme. Skins can be swapped live without restarting. Later, a classic .wsz importer can map old Winamp bitmaps onto the layout.

**Icon:** an original parrot with its wings spread, wearing over-ear headphones, drawn in neon line-art (cyan body, orange headphones). It's built as an adaptive icon: the parrot is the vector foreground layer, the background is a dark grid, and a one-colour layer supports Android 13 themed icons. The splash screen uses an animated vector of the parrot flapping its wings once. The same parrot is the mascot in the "Wings Up" headphone animation.

**Outside the app:** media notification and lock-screen controls through the MediaSession, a home-screen widget (Jetpack Glance) styled as a mini Winamp bar, Bluetooth and headset buttons, and Android Auto later.

## Repo layout

```
kororythm/
  app/                          # Android app module (Compose UI, Hilt setup)
    src/main/java/.../ui/
      player/                   # main, EQ and playlist panels, shade mode
      library/                  # library screens, search
      wizards/                  # import, album, first-run, dac
      dj/                       # DJ panel and decks
      anim/                     # power-surge, wings-up, boot-grid
      viz/                      # Canvas/AGSL visualizers
      skin/                     # skin engine, neon-grid skin
    src/main/res/               # adaptive icon, animated splash, fonts
    src/main/assets/sfx/        # transition and UI sounds (CC0/original)
  core/playback/                # MediaSessionService, ExoPlayer setup, queue
  core/dsp/                     # AudioProcessors: eq, replaygain, boost, tee+FFT
  core/devices/                 # DeviceWatcher, mixer attributes, profiles
  core/library/                 # Room DB, MediaStore/SAF scanner, WorkManager jobs
  core/meta/                    # JAudioTagger, MusicBrainz, cover art
  core/dj/                      # transitions (Kotlin), later Oboe JNI bridge
  native/dj-engine/             # C++ Oboe + SoundTouch (phase 3)
  widget/                       # Glance home-screen widget
  docs/ARCHITECTURE.md          # this blueprint
  .github/workflows/            # build, test, signed APK/AAB
```

## Roadmap

Phase 1 starts today; each phase opens only when its gate passes.

```mermaid
flowchart LR
  P1["1 · Core player<br/>FLAC + MP3 decode<br/>Media3 + gapless<br/>Player screen + EQ<br/>Neon Grid skin, icon"]
  P2["2 · Library + wizards<br/>SQLite library<br/>Import wizard<br/>Album wizard<br/>Plug animations"]
  P3["3 · DJ module<br/>Transitions on Next<br/>Decks + crossfader<br/>BPM, cues, loops<br/>FX pads"]
  P4["4 · Release v1.0<br/>Bit-perfect lamp<br/>Signed APK + CI<br/>Widget + skin packs<br/>Play Store release"]
  P1 -- "Gate: 24/192 FLAC plays" --> P2
  P2 -- "Gate: 1,000 tracks import" --> P3
  P3 -- "Gate: clean mixes" --> P4
```

Today's build target is phase 1: an APK that opens a FLAC file, plays it in the background through Media3 with a working seek bar and EQ, and wears the Neon Grid skin. Phase 2 starts once 24-bit/192 kHz FLAC plays with no dropouts.

## Risks and open decisions

- **Bit-perfect depends on the phone.** The `BIT_PERFECT` mode is optional for phone makers and only exists on Android 14+. Fix: check `getSupportedMixerAttributes` per DAC and show the lamp only when it's really on; otherwise fall back to the DAC's native rate.
- **Vendor audio quirks** (Samsung, Xiaomi and others resample or add effects). Using our own `AudioProcessor` EQ instead of the system `audiofx` keeps the sound the same on every phone.
- **Battery:** a 60 fps visualizer and shader glow drain power. Fix: drop to 30 fps on battery saver and stop drawing when the screen is off.
- **Background limits:** Android can kill apps in the background. The `MediaSessionService` runs as a foreground service with the media notification, which Android allows for playback.
- **Time-stretch and scratch** need native code (Oboe + SoundTouch through JNI), which is why the full DJ deck waits for phase 3. SoundTouch is LGPL, so it ships as a separate shared library.
- **Storage access:** writing tags needs folder permission through the system picker; the album wizard asks once per folder.

## v1.0 implementation notes

What was built, and where it differs from the plan above.

| Area | As built |
| --- | --- |
| Modules | One `app` module with packages per area (`playback`, `dsp`, `dj`, `library`, `tags`, `devices`, `widget`, `ui/*`) instead of separate Gradle modules |
| FLAC decoding | Platform MediaCodec decoder through Media3 (the Media3 FLAC extension isn't published to Maven) |
| DJ engine | Kotlin instead of native Oboe: a second "ghost" ExoPlayer plays the outgoing song through `OutroFxProcessor` while the main player moves on; the incoming song fades in inside `KoroDspProcessor`. Tempo/key lock uses Media3's built-in Sonic time-stretch |
| DJ decks | No two-deck crossfader screen; the DJ panel drives transitions, tempo, FX pads and hot cues on the main deck |
| Tag writing | Own FLAC (Vorbis comment + PICTURE) and ID3v2.4 writers (`tags/TagIo.kt`) instead of JAudioTagger, which needs desktop Java APIs; verified with mutagen and ffmpeg |
| Tag search | MusicBrainz + Cover Art Archive, plus Apple's iTunes Search API |
| Analysis | `AnalysisWorker` decodes each track once: EBU R128 loudness (levelling gain toward −18 LUFS) and tempo by onset autocorrelation |
| Bit-perfect | `BitPerfectController` sets `AudioMixerAttributes` BIT_PERFECT per song; `SwitchingAudioSink` swaps to a float, no-processing AudioSink for 24-bit files. EQ, FX, transitions and levelling pause while it's active |
| Skins | `SkinState` + palette roles; every drawing primitive asks the current skin, so switching repaints the whole app |
| Widget | Jetpack Glance mini player |
| Database | Room v2 adds `bpm`, `gainDb`, `analyzed` and `hot_cues` (migration keeps existing libraries) |

## v1.1 implementation notes

| Area | As built |
| --- | --- |
| Downloads | `downloads/`: `YtEngine` wraps youtubedl-android 0.18.1 (yt-dlp + Python + FFmpeg as native libs, `useLegacyPackaging` so they're extracted). `DownloadWorker` (WorkManager, foreground `dataSync`) runs `-f bestaudio -x` and either `--audio-format mp3 --audio-quality 0` or keeps the stream; cancel kills the yt-dlp process |
| Saving | `MusicStore` inserts into MediaStore with `RELATIVE_PATH=Music/Kororythm/` (`IS_PENDING` while copying); Android 8–9 writes the file and runs the media scanner. URIs are normalised to `content://media/external/audio/media/<id>`, the form the scanner uses, so rescans don't drop the row |
| Honest quality | The best audio stream's codec/bitrate is shown before downloading and written into the MP3 as `TXXX:KORORYTHM_SOURCE = youtube;opus;135`; `Quality.withSource` caps the file's bitrate at that value |
| Share sheet | `SEND text/plain` intent filter on `MainActivity`; the first URL in the text opens Downloads and is read at once |
| Playlists | Room v3: `playlists`, `playlist_tracks(playlistId, position, trackId)` with cascading foreign keys, so deleting a track or playlist cleans up. Order is rewritten in one transaction on reorder |
| Upgrade quality | `Quality.compare` (pure, unit-tested) + `Upgrader`: FLAC bit depth/rate read from STREAMINFO (Android only reports them on 12+), new file written next to the old one with its tags/cover/lyrics, old file deleted (`createDeleteRequest` / `RecoverableSecurityException` consent / SAF `deleteDocument`), then the same library row is repointed (`Scanner.replaceFile`) so ids, hot cues and playlist entries survive; analysis is reset |
| Lyrics | `lyrics/Lyrics.kt`: LRC parser (several stamps per line, 1–3-digit fractions, `[offset:]`) and LRCLIB client (`/api/get`, then `/api/search` picking the closest duration within 10 s, synced preferred). Stored in the `lyrics` table by URI; `TagIo` writes FLAC `LYRICS=` or MP3 `USLT` on request and otherwise keeps existing lyrics |
| Cover flow | Custom layout (not a LazyRow) so the centre cover is drawn and hit-tested last; `graphicsLayer` rotationY ±55°, drag with velocity-based snap, reflections via an offscreen layer with a `DstIn` gradient |
| Packaging | ABI splits (arm64-v8a, armeabi-v7a); R8 keeps `com.yausername.**` and Jackson-annotated members |
| License | GPL-3.0 for the app (required by youtubedl-android); see `NOTICE.md` |

## v1.2 implementation notes

| Area | As built |
| --- | --- |
| Database | Room v4 adds `genreMain`, `subGenre`, `genreChecked` to `tracks` (ALTER TABLE migration, checked against the schema). Rescans keep analysis and genre unless the file's genre changed |
| Needs tags | One SQL "badness" score (artist/album/file-name title = 2, year/genre/cover = 1); listed when ≥ 2 |
| Genre lookup | `GenreWorker` (WorkManager, network required, unique + append): MusicBrainz recording search → tags (weight 3), release group genres (2, cached per album), artist genres (1, cached per artist); iTunes `primaryGenreName` if nothing mapped; the file's genre weighs 6. Network failures return `retry()` so songs aren't marked "no genre" while offline |
| Taxonomy | `GenreMap`: ordered regex rules map any genre name to one of 16 families; the family with the most weight wins, and its strongest non-generic name becomes the sub-genre. Pure and unit-tested |

