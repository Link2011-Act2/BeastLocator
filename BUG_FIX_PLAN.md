# BeastLocator stabilization plan

## Baseline and invariants

- Preserve commit `48ac61d` (the existing compass/crash fixes).
- Preserve the user's uncommitted `versionCode` change in `app/build.gradle.kts`.
- A file has exactly one writer during parallel implementation.
- Cross-owner API needs are reported to the integration owner instead of editing another owner's file.
- Shared hot spots (`MainActivity`, `DestinationStore`, the foreground location service, Gradle, and the manifest) are integration-owner only.

## Root-cause groups

| Group | Root cause | Unified symptoms | Depends on |
|---|---|---|---|
| A. Location sample integrity | Location age, accuracy, source and session are not represented or validated | False arrival, stale UI, timeout never firing, old callback corrupting a new session | None; foundation for B/C/D |
| B. Arrival state machine | Arrival logic is duplicated in Activity, Service and Receivers | Different behavior by foreground/background path, one-sample arrival, repeated/rearmed arrival inconsistencies | A |
| C. App/service lifecycle | Process cold start and Activity pause/resume directly start/stop the FGS; one Boolean represents app visibility | Status/location icon flicker, overlapping location clients, OEM service races, boot failures | A, B |
| D. Background location policy | Background monitoring defaults on and always requests high accuracy at 2-4 second cadence | Battery drain, heat, privacy issue, OEM killing the app, needless tracking after arrival | A, B, C |
| E. Geofence identity and recovery | A fixed request ID and persistent coordinates are treated as authoritative OS registration state | Old destination triggers new arrival; restore/GMS reset/error can permanently disable geofencing | A, B |
| F. Async/lifecycle safety | Raw threads and unscoped Tasks outlive Activity/Receiver/Service; operations have no timeout | ANR, stale UI writes, stuck registration state, leaks and race-dependent crashes | A, B, E |
| G. Heading semantics/rendering | Device attitude, GPS course and an uninitialized 0-degree value share one field; frame loop never settles | Wrong widget arrow, OEM-dependent jumps, needless 60/120 Hz rendering | A |
| H. Sound/notification state | MediaPlayer failure paths, interval jitter and open intents are not state-safe | Stuck sound FGS, repeated sounds/notifications, Activity stack growth | B, C |
| I. Widget update policy/security | Every sample can redraw all widgets; custom exported refresh actions are unprotected | Launcher/Binder load, battery drain, external refresh DoS, stale 10-minute expectation | A, C |
| J. Persistence/migration/privacy | User settings and transient location/runtime state share one backed-up preference file | Stale restored location/geofence state, skipped permission guide, precise-location backup, lost legacy compass setting | A, E, G |
| K. OEM/GMS/resources | Google Play services and downloadable fonts are assumed available | Silent location/geofence/font failures on some Xiaomi/HONOR/China devices | C, E |
| L. Observability/tests | Exceptions are swallowed and state-machine tests are absent | OEM failures remain unexplained and fixes regress across duplicated paths | All groups |

## File ownership during parallel work

| Owner | Exclusive write scope | Must not edit |
|---|---|---|
| Integration owner (root) | `MainActivity.kt`, `DestinationStore.kt`, `BackgroundLocationUpdater.kt`, `ForegroundDistanceMonitorService.kt`, `RandomDirectionApp.kt`, `ReverseGeocoder.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, new shared location/state classes | Files assigned below unless resolving final integration conflicts |
| Geofence owner | `GeofenceHelper.kt`, `GeofenceBroadcastReceiver.kt`, `BootCompletedReceiver.kt`, `BackgroundLocationReceiver.kt`, geofence-specific tests | Core/store/service/UI files |
| Audio/widget owner | `SoundPlaybackService.kt`, `SoundEffectPlayer.kt`, `NotificationHelper.kt`, `WidgetRenderer.kt`, both widget providers, widget provider XML, audio/widget-specific tests | Manifest, store, Activity, foreground location service |
| Settings/resources owner | `SettingsActivity.kt`, `ExperimentalSettingsActivity.kt`, font/theme/value resources, new backup-rule resources, settings/resource-specific tests | Manifest, Gradle, store, MainActivity, geofence/audio/widget files |

## Integration order

1. Add shared validated location sample and centralized arrival decision APIs.
2. Migrate foreground Activity and background service to those APIs.
3. Replace Activity-driven service toggling with process visibility plus debounce; correct FGS types and adaptive request policy.
4. Integrate generation-aware geofences, error recovery and boot completion handling.
5. Replace raw/unscoped async work with bounded lifecycle-aware work.
6. Separate attitude heading from travel bearing; finish smoothing and stop idle frame callbacks.
7. Integrate sound, notification and widget throttling/error handling.
8. Add migration/backup/GMS/font compatibility changes.
9. Add and run unit, lifecycle/state-machine, build and lint checks.
10. Review the complete diff for ownership violations, duplicate state transitions, permission regressions and start/stop races.

## Release gates

- No FGS/location-icon churn on cold start, rotation, or navigation between app Activities.
- No arrival from stale, invalid, low-accuracy or wrong-generation events.
- Background high-accuracy tracking is opt-in, adaptive and stops when no feature needs it.
- Geofence state recovers after `GEOFENCE_NOT_AVAILABLE`, package replacement and process recreation.
- No Receiver performs unbounded geocoding or other long work.
- Heading remains smooth across 359/0 degrees and the renderer sleeps after convergence.
- Sound failure always releases the player and foreground notification.
- Widget refreshes are throttled and cannot be externally spammed through the private refresh action.
- Transient precise location and runtime registration state are excluded from backup.
- Debug compilation, unit tests and lint complete with no errors.

