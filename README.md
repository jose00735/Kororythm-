# Kororythm

An Android music player for FLAC and MP3 with a classic Winamp layout and a neon-grid look from 2000s sci-fi.

<p>
  <img src="docs/screenshots/player-portrait.png" width="240" alt="Player screen">
  <img src="docs/screenshots/power-surge-dac.png" width="240" alt="Power Surge animation when a USB DAC is plugged in">
  <img src="docs/screenshots/wings-up-headphones.png" width="240" alt="Wings Up animation when headphones are plugged in">
</p>

Built with Kotlin, Jetpack Compose and AndroidX Media3. Full design: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Status: phase 1 (core player)

**Works now**

- FLAC and MP3 playback, gapless, in the background (lock screen, notification, Bluetooth and headset buttons)
- Winamp-style main, EQ and playlist panels, each with a "shade" button that rolls it into one line; side by side in landscape
- 10-band EQ (60 Hz – 16 kHz) with preamp, presets and the Boost profile, saved between launches
- Spectrum bars and oscilloscope (tap the visualizer to switch), fed from the audio itself, so no microphone permission
- Readout of kbps, kHz, bit depth and codec, plus an output lamp (speaker, headphones, Bluetooth, USB DAC)
- Plug animations: **Power Surge** for USB DACs (with a haptic pulse), **Wings Up** for wired and Bluetooth headphones, a scan line for other outputs, and the reverse animation on unplug (music pauses)
- Detects whether a DAC supports bit-perfect output (Android 14+) and shows "BIT-PERFECT READY"
- Parrot icon: adaptive, with an Android 13 themed-icon layer, and a splash screen where the parrot flaps its wings
- Playlist kept between launches; "Open with Kororythm" from file managers

**Not yet (later phases)**

- Library database, import and album wizards (phase 2)
- DJ transitions on Next, decks and FX (phase 3)
- Actually switching the DAC into bit-perfect mode, and full 24-bit processing (phase 4). Right now the EQ path runs at 16-bit, so 24-bit FLAC plays, but it may be reduced to 16-bit
- Bundled display font: readouts use the system monospace font for now

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # DSP tests (EQ gains, Boost, soft clip, FFT)
./gradlew :app:recordPaparazziDebug   # re-render the screenshots without a phone
```

GitHub Actions builds both APKs on every push to `main` and uploads them as an artifact.
