# OpenGuard Android app (MVP — Phase 0 + Phase 1)

Free open-source Android app for tracking **your own** phones, with visible
consent and a persistent notification while tracking runs. Kotlin + Jetpack
Compose, MVVM, single Gradle app module.

## Before first run

Set the API base URL in
`app/src/main/java/com/openguard/core/common/Constants.kt`
(`AppConfig.API_BASE_URL`). It defaults to the placeholder
`https://openguard-api.<account>.workers.dev/` and the app refuses to make
network calls until the real deployed Worker URL is set.

## Layout

Single Gradle module (`:app`). The blueprint's `core/*` and `feature/*`
layers are **packages inside this module**, not Gradle modules — splitting
them into Gradle modules is deferred until the app grows enough to need it.

```
app/src/main/java/com/openguard/
  MainActivity.kt            NavHost (login/register/devices/consent/map/history/settings)
  OpenGuardApp.kt            Application + manual DI container
  core/common/               Result wrapper, AppConfig + TrackingConfig constants
  core/network/              Retrofit ApiService + DTOs, AuthInterceptor (+ token
                             refresh authenticator), ApiClient, TokenStore
                             (EncryptedSharedPreferences)
  core/database/             Room outbox queue (LocationEntity + DAO + AppDatabase)
  core/security/             DeviceKeys — STUB for Phase 2 (see file)
  core/location/             LocationService (foreground, type location),
                             UploadWorker (WorkManager), permission helpers
  feature/auth/              Login/Register screens + AuthViewModel + AuthRepository
  feature/devices/           Device list + visible consent screen + DeviceViewModel
  feature/tracking/          MapScreen (MapLibre + OSM raster), HistoryScreen
  feature/settings/          Settings (privacy, battery guide, logout, delete account)
  ui/                        Material 3 theme
```

## Build

CI: `.github/workflows/android.yml` (repo root) builds a debug APK on every
push/PR touching `android/**` and uploads it as an artifact. It provisions
Gradle 8.7 via `gradle/actions/setup-gradle` and runs `gradle
:app:assembleDebug` — no wrapper jar is committed, so CI does not use
`./gradlew`.

Local: install Gradle 8.7 (or run `gradle wrapper --gradle-version 8.7` once
to generate a wrapper), then `gradle :app:assembleDebug` from `android/`.
Needs JDK 17 and an Android SDK (API 34). This VM has neither, so no local
build was run here — see "Verification" below.

## Dependency versions

Pinned toolchain: Gradle 8.7, AGP 8.5.2, Kotlin 2.0.21, KSP 2.0.21-1.0.25,
compileSdk 34, targetSdk 34, minSdk 26, JDK 17.

Library versions are the **newest compatible with that toolchain**, not the
absolute latest releases: the latest 2026 Jetpack releases require
compileSdk 35–37 and AGP 9.x (e.g. Navigation 2.10's release notes state
"Updated Compose compileSdk to API 37… minimum AGP version of 9.2.0"),
which conflicts with the pinned compileSdk 34 / Gradle 8.7. Raising the
toolchain is a deliberate future step, not a version bump.

| Dependency | Version | Notes |
| --- | --- | --- |
| Compose BOM | 2024.09.00 | Compose 1.7.2 / Material 3 1.3.0; last BOM for compileSdk 34 |
| core-ktx | 1.13.1 | |
| lifecycle (runtime-ktx, viewmodel-compose) | 2.8.7 | |
| activity-compose | 1.9.2 | |
| navigation-compose | 2.7.7 | |
| room (runtime, ktx, compiler via KSP) | 2.6.1 | |
| work-runtime-ktx | 2.9.0 | |
| retrofit + converter-gson | 2.11.0 | |
| okhttp | 4.12.0 | |
| play-services-location | 21.3.0 | |
| maplibre android-sdk | 11.11.0 | per the official MapLibre README |
| security-crypto | 1.0.0 | EncryptedSharedPreferences (stable API) |

Current stable releases deliberately NOT used (need newer compileSdk/AGP):
Room 2.8.5, WorkManager 2.12.0, Navigation 2.10.2, security-crypto 1.1.0,
Compose BOM 2026.09.00.

## Design decisions (doc-verified)

- Foreground service: `android:foregroundServiceType="location"` in the
  manifest + `FOREGROUND_SERVICE_LOCATION` permission (Android 14
  requirement), started via `ServiceCompat.startForeground(..., 
  FOREGROUND_SERVICE_TYPE_LOCATION)`. The notification is always visible
  while tracking and has a Stop action.
  https://developer.android.com/develop/background-work/services/fgs/service-types
- Permissions: foreground location first, background location only
  afterwards in a separate step (Android 11+ ignores a combined request);
  background grant happens in Settings ("Allow all the time").
  https://developer.android.com/about/versions/11/privacy/location
- `POST_NOTIFICATIONS` is a runtime permission on Android 13+.
  https://developer.android.com/develop/ui/views/notifications/time-sensitive
- Uploads: periodic WorkManager work, 15-minute minimum interval,
  `NetworkType.CONNECTED` constraint, exponential backoff; queued Room rows
  are deleted only after the server confirms the batch.
  https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
- Location: `FusedLocationProviderClient.requestLocationUpdates` with
  `LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)`.
  https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder
- Map: MapLibre `MapView` in `AndroidView`, style from JSON
  (`Style.Builder().fromJson`) with an OpenStreetMap raster source and
  attribution; marker is a `CircleLayer` over a `GeoJsonSource`.
  https://maplibre.org/maplibre-native/android/examples/configuration/
- AGP 8.5 requires Gradle 8.7 and caps at API 34; Kotlin 2.0.21 supports
  Gradle ≤ 8.8 and AGP ≤ 8.5; as of Kotlin 2.0 the Compose compiler ships
  with the Kotlin plugin (`org.jetbrains.kotlin.plugin.compose`).
  https://developer.android.com/build/releases/agp-8-5-0-release-notes
  https://developer.android.com/develop/ui/compose/bom
- CI: `gradle/actions/setup-gradle` inputs `gradle-version` and
  `build-root-directory` (build lives in `android/`, not repo root).
  https://docs.gradle.org/current/userguide/github-actions.html

## What is stubbed / not in MVP

- `core/security/DeviceKeys.kt` — Keystore device-key helper, stub only
  (Phase 2). MVP auth is bearer tokens.
- No boot receiver: tracking does not auto-resume after reboot yet.
- No launcher icon resource yet (default icon); add an adaptive icon later.
- `GET /api/v1/audit` and `GET /api/v1/privacy` are not called — their
  response shapes are not in the API contract; Settings shows static
  privacy text instead.
- OSM tiles are fetched directly from tile.openstreetmap.org, which is
  fine for personal MVP use; switch to a dedicated tile provider before
  any wider release (OSM tile usage policy).
- Deliberately NOT built: Vault, ring/lock, remote erase, SMS commands,
  FMD/Traccar coupling, geofencing, any hidden surveillance.

## Verification (no Android SDK on this VM)

No Gradle build was run here. Instead, every file was re-read for syntax
errors, all imports were checked against declared dependencies, the
manifest was checked against every permission/service the code uses, and
the Gradle files were checked for consistent plugin ids and catalog
references. The GitHub Actions workflow is the first real build; see the
build report for the list of files with residual uncertainty.
