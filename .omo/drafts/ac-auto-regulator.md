# Draft — ac-auto-regulator

status: awaiting-approval
intent: CLEAR
pending-action: write `.omo/plans/ac-auto-regulator.md` (DONE) -> run Momus high-accuracy review -> await user start (`$start-work`)

## Request (as evolved)

Build a **vendor-agnostic** Android app that periodically: opens a user-designated temperature-source screen, reads the room temperature (no AI/OCR), and if outside a threshold band, opens a user-designated AC-remote screen and taps calibrated buttons to nudge the setpoint.

## Decisions locked (from user)

- Generic, not Tuya-specific. Source app AND AC app are both user-configured.
- Launcher supports all three target types: App (package) + Deep-link (URI) + Captured-shortcut (with fallback). [user: "#1"]
- Validate the exported-gate with a throwaway app BEFORE committing the launch-layer design. [user: "#2"] -> Wave 0 spike.
- "Let the user choose the button area to press and define certain actions." [user, confirmed] -> calibrated tap-targets + bound actions via AccessibilityService.
- No AI / no heavy OCR -> deterministic template digit matching, with an accessibility node-text fast path when available.

## Empirical evidence (verified on emulator-5554, Android 11)

- Tuya temp page = `gzlminiapp` WebView; `uiautomator dump` -> 0 matches for `24.5`, all text/content-desc empty -> temperature is a DRAWN GRAPHIC. Screenshot confirmed 24.5C visible.
- Cold reproduction: force-stopped Tuya, fired captured Intent from home -> landed on exact device page. Capture-and-replay works.
- AC app = Haier U+ (`com.haier.uhome.uplus`) `HainerActivity`, hybrid WebView; screen full of controls exposes only 4 clickable nodes -> buttons NOT reliable accessibility nodes -> coordinate taps required.
- Captured intents:
  - Tuya: `VIEW cmp=com.tuya.smartiot/com.thingclips.smart.hometab.activity.shortcut` extras `{url=thingSmart://pinned_shortcut, shortcut_dev_id=6cd07555416b69c0e3zu8u, type=1, shortcut_home_id=205535327}`
  - Haier: `VIEW dat=http://uplus.haier.com/... cmp=com.haier.uhome.uplus/.launcher.ShortCutLauncherActivity`

## Repo facts

- `com.vnote.appilot`, Jetpack Compose scaffold, minSdk 30 / targetSdk 36, Kotlin + Compose BOM. Blank slate (MainActivity + theme only).
- Source root: `app/src/main/java/com/vnote/appilot/`.

## Open (non-blocking; defaults adopted)

- Polling interval default = 10 min, configurable (reversible internal -> defaulted).
- v1 = single source/actuator pair (multi-profile deferred).

## Tooling note

- No `node`/`bun` on PATH -> scaffold script not run; artifacts hand-authored to the same shape.

## Approval gate

Plan written to `.omo/plans/ac-auto-regulator.md`. Momus high-accuracy review: REJECT (3 blockers) -> all fixed -> re-review PASS. Awaiting user `$start-work`. Execution begins only on explicit start.

## Launch spike outcome

**Result matrix** (emulator-5554, Android 15; no SecurityException on any path → exported gate is OPEN):

| Launch Method | Tuya | Haier |
|---|---|---|
| explicit-component | ✓ WORKS (gzlminiapp GZLLoadingActivity = device deep page) | ✓ no SecurityException, lands ShortCutActivity but BLANK (captured data-URI PATH redacted by Uri.toSafeString() in dumpsys, unrecoverable without root; real full intent captured intact at pin-time via ACTION_CREATE_SHORTCUT / LauncherApps EXTRA_SHORTCUT_INTENT) |
| getLaunchIntentForPackage | wrong-page (FamilyHomeActivity / app home) | wrong-page (MainActivity / app home) |
| raw VIEW deep-link | ActivityNotFound (thingSmart:// not exported) | wrong-page (UpSchemeLaunchActivity → MainActivity / home) |

**Chosen default ranking:**
1) CapturedShortcut/explicit-component (primary, only path landing the exact deep page) 2) getLaunchIntentForPackage (App-package fallback to home) 3) raw VIEW deep-link (tertiary, vendor-dependent)

**Wave 6 default (Wave 2 `Launcher` must implement):**
Wave 2 `Launcher` must try CapturedShortcut/explicit first, fall back to getLaunchIntentForPackage on SecurityException/failure.
</parameter>
</invoke>
