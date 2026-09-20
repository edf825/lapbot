# Lapbot Product Requirements

This document records the intended behavior of the Lapbot Android app. It is the source of truth for the requirements implemented during the initial Alpha Race Hub integration.

## Live Timing

- Connect to the Buckmore Alpha Race Hub live timing feed using its authenticated Pusher WebSocket protocol.
- Connect to track 3 of the Daytona Sandown Park Clubspeed live-score feed using its legacy SignalR long-polling protocol.
- Treat Daytona as a lap-only circuit: retain completed laps observed while connected and do not synthesize sector data.
- Fetch the current timing snapshot before applying live updates.
- Merge sparse competitor, lap, and sector patches without discarding fields omitted by an update.
- Detect stream sequence gaps and fetch a replacement snapshot.
- Preserve existing timing data when a connect, reconnect, refresh, or sequence-gap request unexpectedly returns an empty snapshot.
- Honor an explicit `Clear` message from the live stream.
- Keep a configurable tail of the latest decoded JSON updates for diagnostics. The initial snapshot is not included in this tail.

## Background Operation

- Streaming and text-to-speech announcements must continue while the app is in the background.
- A foreground `dataSync` service owns the repository, WebSocket, and text-to-speech engine.
- Display an ongoing notification while streaming, including a Disconnect action.
- Handle Android's foreground data-sync timeout by stopping the stream and notifying the user that the app must be reopened.

## Connection Recovery

- Group track selection, connection status, Disconnect or Retry, and access to advanced settings in one coherent session control.
- Give initial track selection and Retry the highest visual emphasis and use lower emphasis for Disconnect and advanced settings.
- Auto-reconnect is enabled by default, persisted, and configurable through a full-width interactive row in Advanced settings.
- Reconnect with exponential backoff.
- Default reconnect policy:
  - Initial delay: 500 ms.
  - Maximum delay: 60 seconds.
  - Give-up period: 10 minutes.
- Allow the initial delay, maximum delay, and give-up period to be configured.
- Reset the reconnect backoff after the connection has remained healthy for 30 seconds.
- Stop retrying when the configured give-up period expires and allow the user to reconnect manually.

## Navigation

- Live Timings is the entry destination and provides track selection, connection controls, and real-time race data.
- Offer Buckmore Park and Daytona Sandown Park GP Circuit in the track selector.
- Start connecting automatically as soon as the user selects a track, then transition the same session card through connecting, live, reconnecting, or retry states.
- Do not require a separate Connect action after track selection.
- Present Race Engineer as a floating action button on Live Timings and keep it disabled until live timing is connected.
- Open Race Engineer as a full navigation destination rather than a tab or modal.
- Tapping a competitor row in Live Timings opens that competitor's driver details.
- Keep the Race Engineer landing screen minimal: Driver in Focus, Engineer Radio Messages, Engineer Settings, and Pitlane Mode.
- Driver pages return to Live Timings; Engineer Settings and Pitlane Mode return to Race Engineer.
- Debug tools are available only in debuggable builds and are contained in a dedicated modal.

## Live Timings

- Show a dense, scrollable timing table containing position, kart number, driver name, lap number, and total lap time, plus three sector times when the selected track provides them.
- Total lap time must appear before sector times because it has higher priority.
- Keep table rows compact enough to show as much of the field as practical.
- While the current lap is incomplete, fill each missing total or sector cell from the immediately previous lap.
- Render previous-lap fallback values in a muted grey while rendering fields received for the current lap normally.
- Replace fallback values independently as each current-lap field arrives.

## Valid Laps

- A lap is valid for calculated metrics and performance tones only when it has all of the following:
  - Total lap time.
  - Sector 1 time.
  - Sector 2 time.
  - Sector 3 time.
- Exclude incomplete laps from every best/recent/theoretical metric, even if they contain a total lap time, while retaining them for display.
- This exclusion prevents partial updates with implausibly low total times from distorting comparisons.
- The race overview may still show the current in-progress raw lap fields as they arrive.

## Driver Details

- Show the selected kart/driver name and timing comparison table.
- Show a dense lap-history table beneath the comparison table.
- Lap history includes partial laps and updates as individual total and sector fields arrive.
- Order lap history by lap number descending, with the most recent lap first.
- Lap-history columns are lap number, total lap time, Sector 1, Sector 2, and Sector 3, in that order.

## Metric Window

- Driver metrics have an optional `Since lap` number for endurance races where multiple drivers share a kart.
- The setting is shared between the Driver, Engineer Settings, and Pitlane Mode views.
- A blank value includes the full valid lap history.
- A value includes valid laps whose lap number is greater than or equal to the configured number.
- A value greater than the current lap number is valid and supports configuring the app in anticipation of a driver swap.
- Until a valid lap reaches the configured number, use only the most recent valid lap as the metric window rather than showing no data.
- The lap-history table remains the full history; the setting filters calculated driver metrics.

## Comparison Metrics

- The selected driver's most recent valid lap is the baseline for displayed deltas.
- Show these rows:
  - Most recent valid lap in the metric window.
  - Previous valid lap in the metric window.
  - Driver best valid lap in the metric window.
  - Theoretical best assembled from the best valid individual sectors in the metric window.
  - Best valid lap across the race.
  - Best of each competitor's most recent valid lap.
- Show total lap time and delta before sector times and deltas.
- Delta is `selected most recent - metric`.
- Positive deltas mean the selected lap is slower and are red.
- Negative deltas mean the selected lap is faster and are green.
- Include the source driver and lap number for each comparison where applicable.

## Race Engineer

- Race Engineer is a dedicated screen opened from the Live Timings FAB while connected.
- Driver in Focus allows a kart number to be entered or selected from the current timing field.
- Engineer Settings is a child screen containing all announcement, coaching, metric-window, voice, and tone configuration.
- Pitlane Mode is a child screen showing the live objective, timing comparisons, and lap history intended for another person monitoring the race. Keep it disabled until a driver is in focus.
- Keep Engineer Settings available without a selected driver; require a driver selection before showing driver-specific Pitlane Mode information or testing lap tones.
- Target announcements by normalized kart number rather than competitor ID or driver-name matching.
- Allow a kart already present in live timing to be selected from a list sorted numerically by kart number.
- Allow a kart number to be entered manually before that kart appears in live timing.
- Show a waiting state for a configured kart with no current timing row and resolve it automatically when the kart appears.
- Keep the kart target through driver swaps or competitor-ID changes.
- Announce the first live completed lap when a previously absent selected kart appears, while still suppressing historical laps loaded in an initial snapshot.
- Show the same timing comparison and lap history available on the Driver page in Pitlane Mode, using the metric window configured in Engineer Settings.
- Provide a persistent, default-on `Engineer Radio Messages` switch on the Race Engineer screen. It enables or disables all spoken Race Engineer feedback, including lap calls, optional sector calls, and coaching observations.
- Announce a newly received total lap time for the selected competitor without waiting for all sector data.
- Speak lap times to two decimal places by truncating rather than rounding. For example, `61.499` seconds is passed to TTS as "61 point 49".
- After the lap time, speak concise racing comparisons: for example, "point 22 slower than last" and "point 57 off your best". Say "quicker than last", "new best by", "same as last", or "matches your best" when applicable.
- Provide independent `Speak last comparison` and `Speak best comparison` switches in Engineer Settings, enabled by default.
- Provide a persistent, default-off `Speak gaps` switch for every supported track. Provide a separate persistent, default-off `Include kart numbers` option so an adjacent-position call can identify the occupying kart, for example, `Gap to P3, kart 12, 1 point 20`. On a selected-driver lap completion, announce the authoritative interval to the immediately preceding and following race positions when both timing measurements describe the same completed lap. Prefer a provider interval, otherwise subtract provider gaps-to-leader; Buckmore may fall back to complete accumulated same-session lap histories. Suppress unavailable, negative, lapped, incomplete, or temporally mismatched comparisons rather than estimating them.
- Provide one default-off `Speak coaching` switch in Engineer Settings. It is independent from `Speak sector timing`: coaching may speak a concise objective-sector observation without reading the raw sector time.
- Provide a persistent coaching-detail choice: Low, Medium, or High. Low is the default and preserves the sparsest cadence; higher levels increase insight frequency without lowering evidence standards or exceeding the spoken-message budget.
- Treat a sector within 100 ms (one tenth) of the driver's fastest repeatable sector pace as personally consistent, not necessarily high performance. At High coaching detail, use varied, precise recognition and reinforce consecutive qualifying attempts.
- Reserve `front-running performance` for repeatable sector pace within 0.5% of the credible front-running session reference.
- Build that reference from up to three credible faster drivers within 0.5% of the fastest repeatable whole-lap pace. Require at least three clustered samples, use the median contributor pace per sector, and stabilize cohort membership across two focused-driver lap evaluations.
- Identify relative opportunities by subtracting the median usable sector deficit from each sector's benchmark-relative deficit. Require at least +0.75 percentage points and 150 ms of additional loss, confirm the primary sector twice, and describe the time as an indicator rather than guaranteed recoverable pace.
- Provide external opportunity coaching at every detail level: concise focus changes at Low, progress and approximate additional loss at Medium, and full percentage/baseline context plus combined personal-consistency insights at High.
- Recognize external-opportunity progress only after three repeatable post-advice samples materially reduce the additional loss. A benchmark change starts a new progress baseline and must not be presented as driver progress.
- Treat the sum of the driver's best valid session sectors as a distinct demonstrated-optimal lap. Require at least three structurally valid complete laps, describe the difference to the best complete lap as a lap-assembly opportunity rather than repeatable or guaranteed pace, surface it even when no sector objective exists, and recognize when a later best lap materially closes that gap.
- Maintain per-driver session context from representative timing attempts: recent and preceding pace windows, consistency, demonstrated potential, repeatable fast pace, one stable objective, objective progress, and recently spoken observations.
- Treat credible one-off fast sectors as demonstrated potential but not repeatable pace. Exclude compromised timing and defer isolated slow contextual samples until repeated evidence establishes a pace change.
- Keep coaching concise. Route sector and lap candidates through one speech budget so a final-sector update and lap completion produce one prioritised announcement and at most one cue.
- Rotate categorized phrase banks for new best, improvement, consistency, slower laps, first laps, sector gains, and sector losses without repeating any of the latest three templates.
- Omit sector coaching when the largest change is below 100 ms, and never infer cornering, braking, throttle, or driving technique from sector timing alone.
- Offer persistent Female and Male British voice preferences, adjustable delivery speed, and a preview action.
- Speak each announcement as one naturally punctuated utterance. Prefer an enhanced network voice, falling back to an installed voice when it is unavailable.
- Provide a default-off sector timing option. After sectors 1 and 2, speak the sector time truncated to two decimal places. When `Speak best comparison` is enabled, append "new PB" for a strictly faster sector or an equal or slower sector's truncated deficit to the earlier personal best, for example, "16 point 42, point 12 off best".
- Provide a persistent, default-on `Sector tones` switch. It controls the sector and lap-completion cue without disabling spoken sector timing.
- Compare each announced sector against the selected driver's earlier personal-best sector. When Sector tones are enabled, start `best_sector.wav` for a strictly faster time or `sector.wav` for an equal or slower time, then begin the numeric readout one second later. Never play both for one sector.
- On every selected-driver lap completion, when Sector tones are enabled, start `sector.wav` and begin the announcement one second later. Substitute `best_sector.wav` for a strict new personal-best lap. Normal lap comparison tones remain after the spoken announcement.

## Performance Tones

- Provide a persistent `Performance tones` switch, enabled by default, that controls automatic and test tone playback.
- Group the tone comparison metric and Tone configuration with the playback switch, and visually disable dependent controls when playback is off.
- After each spoken lap time completes, play a five-tone performance sequence.
- TTS announcements do not depend on tone metric availability. If that same lap lacks complete metric data, speak the lap time and omit its tones.
- Play one 440 Hz reference tone followed by total lap, Sector 1, Sector 2, and Sector 3 comparison tones.
- Raise pitch when the latest value is faster than the configured metric and lower pitch when it is slower.
- Map each 100 ms of delta to one semitone, rounded to the nearest semitone and clamped to one octave above or below 440 Hz.
- Separate tones with a 75 ms gap and apply a short fade at each edge to prevent clicks.
- Allow the comparison metric to be selected from previous lap, driver best, theoretical best, race best, and best recent.
- Compare a newly completed lap against the benchmark that existed before that lap. A new best must therefore be compared with the old best, not itself.
- Treat previous lap as the exception: lap N compares with lap N-1 from the updated history rather than the pre-lap `Previous lap` metric, which would incorrectly be N-2.
- Wait until TTS has completed before playing lap comparison tones; on tracks with sectors, also wait until the same lap has complete sector data.
- Apply the `Since lap` metric window to previous lap, driver best, and theoretical comparisons.
- If the selected metric is unavailable, do not play a potentially misleading tone sequence.
- Default reference, lap, and sector tone durations to 200 ms, 300 ms, and 150 ms respectively, and allow each category to be configured from 50 to 1,000 ms.
- Keep duration controls and a `Test latest lap` action in a dedicated Tone configuration dialog.
- Testing plays tones for the selected competitor's latest valid lap without requiring a spoken announcement.

## Diagnostics

- Log stream lifecycle, snapshot application, ignored empty snapshots, sequence gaps, explicit clears, and failures under the `LapbotStream` tag.
- Log foreground service creation, commands, timeout, and destruction under the `LapbotService` tag.
- Diagnostic logging must identify why timing data was replaced or preserved without dumping private authentication values.

## Verification

- Unit tests cover sparse patch merging, valid-lap filtering, lap metrics, newest-first history, metric-window fallback, reconnect behavior, and announcement truncation.
- The debug build and Android test sources must compile before installation.
- Verify important navigation and table ordering on a connected Android device when one is available.
