# Appilot — AC Auto-Regulator Plan

## Goal

Automate what is currently a manual loop:

1. Read the current room temperature from a **Tuya smart device** (today: via a Tuya app desktop shortcut/widget that shows the temperature directly).
2. If the temperature is **above a set threshold**, open the **air-conditioner remote-control app** and tap a button to **decrease the AC set temperature** (and symmetrically increase it when too cold).
3. Do this **periodically and automatically**, without a human checking and tapping.

User constraint / preference: prefer to do this **without AI or OCR** if possible — e.g. let the user mark a screenshot region for comparison and a coordinate for the click.

## Current repo state

- Fresh **Jetpack Compose** Android scaffold.
- Package: `com.vnote.appilot`
- `minSdk = 30` (Android 11), `targetSdk = 36`, Kotlin + Compose BOM.
- Essentially a blank slate (`MainActivity.kt` + theme files only). No automation code yet.

## Key insight: "comparison only, no OCR" has a limit

Pure region image-comparison can only answer **"is this region identical to a saved reference?"** — it **cannot** answer **"is the temperature above 26°?"**, because the temperature is a *varying number*. Comparing the crop against one saved image yields equal/not-equal, not greater/less.

To make a threshold decision you must **interpret the digits** somehow. The good news: interpreting digits does **not** require AI or a heavy OCR engine.

## Options (ranked by robustness)

### Option A — Skip the screen entirely (most robust; investigate first)

Read temperature directly instead of scraping the UI:

- **Tuya IoT Platform OpenAPI** (cloud), or
- **Local LAN protocol** (`tinytuya` / LocalTuya) — read the value off the device over WiFi, no internet.

Returns a clean number (e.g. `26.5`). No screenshot, no OCR, no fragile taps.

For the AC side, depends on how the remote app drives it:
- **Tuya IR/RF hub** → send the "temp down" IR command via the same Tuya API.
- **Phone built-in IR blaster** → Android `ConsumerIrManager`.
- **Different vendor's cloud app** → that vendor's API (or fall back to B/C for the AC side only).

### Option B — Accessibility Service (no OCR, no screenshots for reading)

An `AccessibilityService` reads on-screen text straight from the view hierarchy
(`AccessibilityNodeInfo.text`). If the Tuya widget renders the temperature as a real
text view, you read `"26.5°C"` directly — zero OCR. Taps on the AC app are dispatched
via `dispatchGesture(...)` at calibrated coordinates.

- **Caveat:** many IoT apps draw the number on a `Canvas` / `SurfaceView` / `WebView`,
  so the text node is empty and accessibility can't read it → fall back to Option C for
  *reading only*. Taps still work via accessibility regardless.

### Option C — Screenshot + deterministic digit matching (refined version of the user's idea)

- `MediaProjection` captures the screen → crop the user-selected region.
- Convert pixels to a number **without AI** via **fixed-font digit template matching**:
  capture glyph templates for `0–9` once, then match each digit slot. Deterministic,
  tiny, fast, fully offline. "OCR" only in the loosest sense — no ML, no cloud.
- This is the "user marks an area" UX, with per-digit matching layered on so a *value*
  (not just sameness) comes out.

## Cross-cutting concerns (apply to every option)

1. **Control logic needs hysteresis / deadband.** "If room > threshold, press down once
   per cycle" works. To set an *absolute* AC target you'd also need to read the AC's
   *current* setpoint (same digit problem on the AC screen). Add a deadband (e.g. act on
   >26.5, stop at <25.5) so it doesn't oscillate every cycle.

2. **Android execution reality:**
   - Periodic work: `WorkManager` min interval is 15 min; tighter polling needs a
     **foreground service** + `AlarmManager`/Handler, and must fight Doze / battery
     optimization.
   - `MediaProjection` requires a user consent prompt per session (persistable via a
     foreground service).
   - Tap coordinates are resolution / orientation dependent — store as **ratios** and
     provide a calibration screen.
   - For screen automation the screen must be **on and unlocked**, and the target app
     must be foreground to receive taps.
   - No root needed (Accessibility + MediaProjection). Root / Shizuku only makes input
     injection easier.

## Recommendation

Pursue a **fallback chain A → B → C**, likely ending in a **hybrid**:
- Read via **Tuya API** if access is available;
- else probe **Accessibility**;
- else **MediaProjection + template digits**;
- and use **Accessibility for the taps** in all screen-based cases.

This avoids AI, avoids heavy OCR, and yields the most reliable build that is actually
achievable.

## Open questions (blocking a concrete build plan)

1. **AC remote app** — Tuya IR hub, phone's own IR blaster, or a separate third-party
   app? (Decides whether taps can be skipped entirely.)
2. **Tuya temperature display** — normal/selectable text, or a stylized drawn graphic?
   (Decides Accessibility vs. template-matching; can be verified empirically on-device.)
3. **Tuya developer access** — is a Tuya IoT Platform account available / creatable for
   API access? (Unlocks Option A.)
4. **Polling interval** — every 5 / 15 min / on-demand? Phone under user control,
   possibly rooted or with Shizuku?

## Next step

Once the open questions are answered, bring in the plan agent to produce a precise,
wave-based build plan (data source layer → decision/hysteresis engine → actuation layer
→ scheduling/foreground service → calibration UI).
