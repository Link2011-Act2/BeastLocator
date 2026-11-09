# BeastLocator Gold Core migration

Only the app APK updater was changed. Gold809 is the sole Goldy dependency; XML/Views, Material components and Markwon remain. Existing location, geofencing, sound and widget implementation files are unchanged. The original SettingsActivity bytes and staged release-file deletions are preserved.

| Previous responsibility | Final owner |
| --- | --- |
| GitHub HTTP/version parser/release selection | Gold with UpdatePolicies.beastLocator(), Common versions and list feed |
| AppUpdateManager executor/cache/single ignore tag | Thin application-owned Gold facade, preferences state store, idempotent legacy tag union |
| Activity-owned Downloader / SingleUseValue | Application UpdateTransferRuntime + SDK createResumable() |
| APK selection and identity | Single + Published; no speculative ABI selector |
| Raw file/signature/installer/FileProvider path | SDK verification, persistent request, installer and limited provider |
| Execution and notifications | Separate UIDT on34+, Foreground WorkManager below34; host namespace/IDs |
| Update screen/history | Existing XML, Markwon and expandable notes; one explicit action appropriate to state |
| Retained APK management | No normal management card/release/reopen dialog; SDK reconciliation on package replacement and normal app entry |

Manual checks publish to Gold even after the initiating screen is recreated; a destroyed screen detaches its callback. About observes Gold's state and uses View update as primary when a candidate exists, including ignored candidates. Merely checking never forces a dialog or navigation. The Main banner observes notification state separately.

Legacy settings remain in github_release_updates. Migration unions dismissed_update_tag into Gold's multiple-tag set, preserves developer values and audits the old check time. No trusted catalog/Asset is synthesized from an old DTO. A failed write does not mark migration complete. IntDev options are gated using actual PackageManager version and never alter APK verification.

The host keeps SDK resumable metadata, transport and verification unchanged. No automatic installer launch after completion/restore. Permission return uses the SDK's fresh PackageManager permission query. A handed-off request becomes a normal explicit Install on return; duplicate current handoffs are blocked. Only SDK-confirmed installed/superseded requests clear matching host transfer keys, preserving another Asset's paused transfer.

Capacity/percentage rendering follows Elief's decimal formatter without importing Elief. Tests cover boundaries, locale, invalid totals and rounding below100%. Normal updater messages use concise Japanese, English and simplified Chinese.19 unused old updater strings were removed after reference checks; location/live-update strings were not changed.

Public Gold requires compileSdk37, so compileSdk was raised from36 while min26/target36 remain. Existing normal Release behavior remains unminified; the explicit -PverifyUpdateR8=true verification switch exercised the same Release configuration with R8. No signing guard bypass or key access. The named unsigned R8 APK is5157099bytes.

Dependency audit: no Elief, Pyrite, Compose UI or Compose runtime implementation. Existing activity-ktx1.13.0 brings navigationevent's runtime-annotation1.9.0 artifact; it was not newly introduced by Gold and is annotations only. No Compose compiler/UI setup was added. Release manifest contains no Debug test Activity, preserves location|mediaPlayback services and uses a separate dataSync foreground service and Gold provider.

Verification (2026-11-09 JST):
- Full66 JVM tests: no failures/errors/skips. Debug and test APKs, unsigned Release R8, Lint Debug/Release pass. Lint197/199 warnings, no Error/Fatal.
- API36:12 host cases +5 permission process stages +6 actual HTTP Range process stages pass. Initial background fixture close failure was fixed using a dedicated Debug-only Activity and only the failed case rerun. A Range-driver package typo initially loaded no actual test; corrected before the successful protocol runs.
- API36 signed self APK resumed4194304/13382167bytes with206; 200 fallback explained the restart; tampered prefix discarded and restarted at0. Completed files passed full Gold verification and request preparation.0 automatic installer launches,1 explicit callback.
- API28: real Foreground WorkManager/Home/other-app/screen-off/recreation/notification cancellation/retry case passed; separate-process206 resumed3145728/13382167bytes and passed full verification/preparation.0 automatic installer launches,1 explicit callback.
- Actual SDK signature rotation, real package replacement/held-URI behavior and independent public Consumers are reused from verified809 evidence rather than repeated per host.

Limitations: API26 unavailable; real GPS/Geofence/sound hardware behavior, formal production-signed updates and external installer final success were not exercised. Host installer callbacks in controlled fixtures are recorded instead of actually installing the test APK. Real package replacement was verified in the shared SDK fixture. Force-stop/vendor restrictions are not claimed to allow perpetual automatic execution.

Evidence: build/phase5/beast-final-gates.log; core-only-dependencies.log; beast-host-api36-v1.log; beast-background-api36-v2.log; beast-lifecycle-additions-api36.log; api36-permission-v1; api36-{range,ignore-range,tamper}-v2; beast-background-api28.log; api28-range-v1. All app changes remain uncommitted/unpushed.
