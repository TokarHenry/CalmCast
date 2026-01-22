# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Repo overview
CalmCast is a single-module Android app (`:app`) written in Kotlin and Jetpack Compose, using Mudita Mindful Design (MMD) UI components.

Key tech:
- UI: Jetpack Compose + Navigation Compose
- Architecture: MVVM (single `PodcastViewModel`) + State/StateFlow
- Networking: OkHttp + iTunes Search + RSS parsing (XmlPullParser)
- Persistence: Room (`PodcastDatabase`) for podcasts, downloads, playback positions
- Audio playback: Media3 (ExoPlayer + MediaSessionService)

## Local setup notes
- The app reads `TADDY_API_KEY` from a local `.env` file and injects it into `BuildConfig` (see `app/build.gradle.kts`). If you need that feature, create a `.env` file at the repo root with:
  - `TADDY_API_KEY=...`
- There is no `gradlew` script currently checked in. If you can’t use `./gradlew`, run Gradle via Android Studio or a system Gradle (`gradle ...`).

## Common commands
All commands are shown using the Gradle wrapper (`./gradlew`) when available. If it’s missing, replace `./gradlew` with `gradle`.

### Build
- Debug APK:
  - `./gradlew :app:assembleDebug`
- Release APK:
  - `./gradlew :app:assembleRelease`
- Clean:
  - `./gradlew clean`

### Install / run
- Install to a connected device/emulator:
  - `./gradlew :app:installDebug`

### Lint
- Android Lint:
  - `./gradlew :app:lintDebug`

### Tests
Note: this repo currently has no `app/src/test` or `app/src/androidTest` directories, but the Gradle tasks exist.

- Unit tests:
  - `./gradlew :app:testDebugUnitTest`
- Run a single unit test class:
  - `./gradlew :app:testDebugUnitTest --tests 'com.calmcast.podcast.SomeTestClass'`
- Instrumentation tests (device/emulator required):
  - `./gradlew :app:connectedDebugAndroidTest`
- Run a single instrumentation test class:
  - `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.calmcast.podcast.SomeInstrumentedTest`

### Room schema generation
Room schemas are configured to output to `app/schemas` (see `room { schemaDirectory(...) }` in `app/build.gradle.kts`). Running a build will update schema JSONs.

## High-level architecture
### Entry points
- `app/src/main/AndroidManifest.xml`
  - `com.calmcast.podcast.CalmCastApplication` (Application)
  - `com.calmcast.podcast.MainActivity` (Compose UI)
  - `com.calmcast.podcast.PlaybackService` (Media3 `MediaSessionService`)

### UI layer (Compose)
- `MainActivity.kt` is effectively the “app shell”:
  - Creates the Room database + DAOs
  - Creates `PodcastViewModel` via `PodcastViewModelFactory`
  - Sets up Navigation Compose destinations (`subscriptions`, `search`, `detail/{podcastId}`, `downloads`, `settings`)
  - Hosts top app bar + bottom navigation using Mudita MMD components

Screens are under `app/src/main/java/com/calmcast/podcast/ui/**` and are wired into the nav graph in `MainActivity.kt`.

### ViewModel (single orchestrator)
- `app/src/main/java/com/calmcast/podcast/ui/PodcastViewModel.kt`
  - Owns all screen state exposed to Compose (`subscriptions`, `searchResults`, `currentPodcastDetails`, playback state, downloads, settings state)
  - Orchestrates:
    - searching + fetching RSS episodes
    - subscribing/unsubscribing
    - download lifecycle (start/pause/resume/cancel/delete)
    - playback via `MediaController` connected to `PlaybackService`
    - persisting playback positions periodically

### Data/persistence
- Room database:
  - `PodcastDatabase` (`app/src/main/java/com/calmcast/podcast/data/PodcastDatabase.kt`)
  - Entities: `Podcast`, `PlaybackPosition`, `Download`
  - DAOs: `PodcastDao`, `PlaybackPositionDao`, `DownloadDao`
  - Migrations live in `app/src/main/java/com/calmcast/podcast/data/Migrations.kt`

Important modeling detail:
- Episodes are NOT persisted in Room. They’re fetched from the RSS feed on demand and cached in-memory in `PodcastRepository`.

Subscriptions:
- `SubscriptionManager` stores the list of subscribed podcast IDs in SharedPreferences (`calmcast_subscriptions`). Podcast metadata is stored in the `podcasts` table.

Settings:
- `SettingsManager` wraps SharedPreferences (`calmcast_settings`) and exposes StateFlows used by `PodcastViewModel`.

### Networking
- `ItunesApiService` (`app/src/main/java/com/calmcast/podcast/api/ItunesApiService.kt`)
  - iTunes Search API for discovery
  - Fetches/parses RSS feeds directly (XmlPullParser)
- HTTP caching:
  - `CalmCastApplication` builds a cached OkHttp client with a dedicated `http_rss_cache` directory.

### Downloads
- `AndroidDownloadManager` (`app/src/main/java/com/calmcast/podcast/data/download/AndroidDownloadManager.kt`)
  - Uses OkHttp with `Range` requests to support resuming partial files
  - Persists progress/status in the Room `downloads` table
  - Uses `StorageManager` to pick internal vs removable external storage based on `SettingsManager`.

### Playback
- `PlaybackService` (`app/src/main/java/com/calmcast/podcast/PlaybackService.kt`)
  - Hosts ExoPlayer + MediaSession
  - Publishes playback notifications
  - Forwards ExoPlayer errors through a static callback (`setErrorCallback`) so the UI can surface e.g. connectivity issues

### App foreground refresh
- `AppLifecycleTracker` listens to the process lifecycle. On `ON_START` it triggers a refresh callback if one is registered (MainActivity wires this to `PodcastViewModel.reloadData()`).
