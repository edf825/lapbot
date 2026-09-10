# Voice command spike

This debug-only experiment tests the microphone-to-response path without requiring a live timing session.

## Scope

- Uses Android's on-device speech recognizer while the spike activity is visible.
- Recognises `Lapbot, gaps` plus tightly scoped variants observed from Android's recognizer, including `Laptop gaps` and `Lapbox gaps`.
- Plays an acknowledgement tone.
- Speaks a canned response: `Gap to P3, kart 12, 1 point 20. Gap to P5, kart 27, point 33.`
- Stops recognition during its own response and resumes after a short cooldown.
- Does not change the production timing or announcement flow.

This is not the production always-listening implementation. Android's general speech recognizer is intentionally used only to validate the acoustic command flow before selecting and integrating a dedicated wake-word engine.

## Run on a debug device

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.lapbot/.debug.VoiceCommandSpikeActivity
```

Tap `Start listening`, grant microphone permission, and say `Lapbot, gaps`.

## Record during testing

- Recognised transcript shown on screen.
- Whether the acknowledgement tone was audible.
- Whether the complete canned response was audible in the headset.
- Missed commands and false activations.
- Whether Lapbot's response caused another activation.
