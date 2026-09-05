# Lapbot

Native Android live timing and audio-announcement app for Buckmore Park and Daytona Sandown Park, built with Kotlin and Jetpack Compose.

See [PRODUCT_REQUIREMENTS.md](PRODUCT_REQUIREMENTS.md) for the live timing, metrics, announcements, and connection behavior specification.

## Requirements

- JDK 17
- Android SDK Platform 36
- Android Studio (latest stable) or the Android CLI

## Open and run

Open this folder in Android Studio and run the `app` configuration on an emulator or connected Android device.

From a terminal, build a debug APK with:

```bash
./gradlew assembleDebug
```

The generated APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Project details

- App name: Lapbot
- Package: `com.example.lapbot` (replace this before publishing)
- Minimum Android version: API 24 (Android 7.0)
- Target/compile Android version: API 36

## Usage

1. Choose `Buckmore Park` or `Daytona Sandown Park GP Circuit` in Live Timings. Lapbot connects automatically and displays the active timing session.
2. Wait for the status to change to `Live`.
3. Once connected, tap the `Race Engineer` floating action button and choose the driver in focus.
4. Open `Engineer Settings` for lap-call, comparison, voice, and tone configuration.
5. Enable `Pitlane Mode` in the Race Engineer sheet for the live objective, comparison, and lap-history view used by someone monitoring the race.
6. Optionally disable performance tones, choose their comparison metric, configure tone durations, or set `Metrics since lap` for a driver stint.

Daytona's Clubspeed feed provides completed lap times but no sector splits. On
that circuit Lapbot hides sector-only columns, coaching, controls, and tones;
lap history is collected from the updates received while connected.

Lap announcements and tones continue while the app is backgrounded.

## Replay a past session

Debug builds include a `Replay session 837888` action in the `Debug tools`
dialog on the disconnected Live Timings screen. It replays public total lap times from John Reeves's kart 5 result at
approximately 2× real speed, using sector-proportional update spacing for audio
clarity. Timing fields still arrive separately like the live feed.

The finished-session result does not expose every historical sector split, so
the replay synthesizes sectors that add up to each real total lap time. Select
kart 5 in `Race Engineer` and enable `Engineer enabled` to exercise speech, comparison
tones, and background operation without an active race.

Race Engineer also offers optional session-aware coaching. It builds a
stable sector objective from repeated pace, tracks progress after the objective
is set, and speaks sparingly when it finds meaningful evidence. Spoken raw
sector times and coaching are independent, so coaching can remain on without
reading every split. Replay-sector coaching is useful for testing the experience
only because replay splits are synthesized.

Announcements use naturally paced sections and an adjustable British voice.
Users can choose a Female or Male voice and preview it from Engineer Settings
screen. Lapbot prefers the enhanced Google network voice and falls back to an
installed voice when the enhanced variant is unavailable.

After sectors 1 and 2, optional sector timing says the two-decimal sector time.
When Speak best comparison is enabled, it adds "new PB" for a strict personal
best; equal or slower sectors instead announce their deficit to the personal
best, for example, "16 point 42, point 12 off best". The default-on Sector tones switch optionally starts `sector.wav`,
or the distinct `best_sector.wav` for a new personal best, one second before
the readout.

When Sector tones are enabled, every completed lap starts `sector.wav`, then
begins its lap announcement one second later. A strict new personal-best lap
uses `best_sector.wav` instead. The configured lap comparison tones still
follow the speech.
