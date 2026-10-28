# Zotero Android - Code Structure & Dependencies

## Project Overview

- **Name**: Zotero Android
- **Package**: `org.zotero.android`
- **App ID**: `org.zotero.android`
- **Min SDK**: 23 | **Target SDK**: 36 | **Compile SDK**: 37
- **Version**: 1.0.0 (versionCode 283)

## Build System

- **Build Tool**: Gradle (Kotlin DSL)
- **Android Gradle Plugin**: 9.3.1
- **Kotlin Version**: 2.4.10
- **Architecture**: Multi-plugin dependency management via `buildSrc`

### Gradle Files
- `settings.gradle.kts` - Root project configuration
- `build.gradle.kts` - Top-level build with shared configuration
- `app/build.gradle.kts` - App module build configuration
- `buildSrc/` - Custom build plugins and dependency definitions

### Product Flavors
| Flavor | App Name | App ID Suffix |
|--------|----------|---------------|
| `dev` | Zotero Debug | `.debug` |
| `internal` | Zotero | (none) |

---

## Code Structure

```
app/src/main/java/org/zotero/android/
├── androidx/          # AndroidX compatibility utilities
├── api/               # API models and responses
├── appupdate/         # App update functionality
├── architecture/      # Core app architecture
│   ├── content/       # Content providers
│   ├── core/          # Core utilities
│   ├── coroutines/    # Coroutine helpers
│   ├── crashreporting/# Crash reporting setup
│   ├── di/            # Dependency injection (Hilt modules)
│   ├── logging/       # Logging configuration
│   ├── navigation/    # Navigation graph/routing
│   ├── serialization/ # JSON serialization
│   └── ui/            # UI theme/styling
├── attachmentdownloader/  # File attachment downloads
├── backgrounduploader/   # Background upload service
├── citation/          # Citation processing
├── database/           # Realm database models
├── files/             # File handling utilities
├── helpers/           # General helper classes
├── ktx/               # Kotlin extensions
├── loaders/           # Content loaders
├── pdf/               # PDF viewer integration
├── pdfworker/         # PDF processing worker
├── screens/           # UI screens (25+ screens)
├── styles/            # Citation/export styles
├── sync/              # Sync engine
├── translator/        # Translator functionality
├── uicomponents/     # Reusable UI components
├── webdav/            # WebDAV integration
└── websocket/         # WebSocket communication

app/src/test/          # Unit tests
```

### Key Screens (25+)
`addbyidentifier`, `addnote`, `allitems`, `citation`, `citbibexport`, `collectionedit`, `collectionpicker`, `collections`, `creatoredit`, `dashboard`, `downloadedfiles`, `filter`, `itemdetails`, `libraries`, `loading`, `login`, `mediaviewer`, `onboarding`, `reader`, `retrievemetadata`, `root`, `scanbarcode`, `settings`, `share`, `sortpicker`, `tagpicker`, `webview`

---

## Dependencies

### How Dependencies Are Organized

Dependencies are defined in `buildSrc/src/main/kotlin/Libs.kt` and applied via custom Gradle plugins in `buildSrc/src/main/kotlin/dependencyplugins/`.

### Dependency Plugins (applied in `app/build.gradle.kts`)
```kotlin
id("kotlinDependencies")        // Kotlin stdlib, coroutines, serialization
id("androidXDependencies")       // AndroidX libraries
id("composeDependencies")        // Jetpack Compose
id("daggerAndHiltDependencies")  // Dependency injection
id("networkDependencies")       // Networking (Retrofit, OkHttp)
id("testDependencies")          // Testing libraries
```

---

### Kotlin & Coroutines (`kotlinDependencies`)
| Library | Version |
|---------|--------|
| kotlin-reflect | 2.4.10 |
| kotlinx-serialization-json | 1.11.0 |
| kotlinx-collections-immutable | 0.5.1 |
| kotlinx-coroutines-core | 1.11.0 |
| kotlinx-coroutines-android | 1.11.0 |

### AndroidX (`androidXDependencies`)
| Library | Version |
|---------|--------|
| androidx-core-ktx | 1.19.0 |
| androidx-fragment-ktx | 1.8.9 |
| androidx-appcompat | 1.7.1 |
| androidx-preference-ktx | 1.2.1 |
| androidx-vectordrawable | 1.2.0 |
| androidx-activity-ktx | 1.13.0 |
| androidx-constraintlayout | 2.2.1 |
| androidx-lifecycle-viewmodel-compose | 2.11.0 |
| androidx-lifecycle-runtime-ktx | 2.11.0 |
| androidx-lifecycle-livedata-ktx | 2.11.0 |
| androidx-lifecycle-process | 2.11.0 |

### Jetpack Compose (`composeDependencies`)
| Library | Version |
|---------|--------|
| compose-ui | 1.11.4 |
| compose-ui-util | 1.11.4 |
| compose-ui-tooling | 1.11.4 |
| compose-foundation | 1.11.4 |
| compose-material3 | 1.5.0-alpha02 |
| compose-material3-window-size | 1.5.0-alpha24 |
| compose-activity | 1.13.0 |
| compose-constraintlayout | 1.1.1 |
| compose-navigation | 2.9.8 |
| compose-runtime-livedata | 1.11.4 |
| compose-ui-viewbinding | 1.11.4 |
| material-icons-extended | 1.7.7 |

### Dependency Injection - Hilt (`daggerAndHiltDependencies`)
| Library | Version |
|---------|--------|
| hilt-android | 2.60.1 |
| hilt-android-compiler | 2.60.1 |
| hilt-navigation-compose | 1.4.0 |

### Networking (`networkDependencies`)
| Library | Version |
|---------|--------|
| retrofit2 (core) | 3.0.0 |
| retrofit2-converter-gson | 3.0.0 |
| retrofit2-converter-scalars | 3.0.0 |
| retrofit2-kotlinx-serialization-converter | 1.0.0 |
| okhttp3 (core) | 5.4.0 |
| okhttp3-logging-interceptor | 5.4.0 |
| okhttp-digest | 3.1.1 |

### Other Direct Dependencies (in `app/build.gradle.kts`)
| Library | Version |
|---------|--------|
| material | 1.14.0 |
| firebase-crashlytics-ktx | 19.4.4 |
| nutrient (PSPDFKit) | 2024.4.0 |
| gson | 2.14.0 |
| media3-exoplayer | 1.10.1 |
| media3-ui | 1.10.1 |
| coil-compose | 3.5.0 |
| commons-text | 1.15.0 |
| timber | 5.0.1 |
| joda-time | 2.14.3 |
| eventbus | 3.3.1 |
| keyboardvisibilityevent | 3.0.0-RC3 |
| play-services-code-scanner | 16.1.0 |

### Testing Dependencies (`testDependencies`)
| Library | Version |
|---------|--------|
| junit | 4.13.2 |
| mockito-core | 5.18.0 |
| mockk | 1.14.5 |
| kluent-android | 1.73 |
| androidx-test-ext-junit | 1.3.0 |
| androidx-test-runner | 1.7.0 |
| androidx-arch-core-testing | 2.2.0 |

---

## Build Plugins Summary

| Plugin | Purpose |
|--------|---------|
| `com.android.application` | Android app module |
| `kotlin-android` | Kotlin support |
| `kotlin-kapt` | Kotlin annotation processing |
| `kotlin-serialization` | Kotlinx serialization |
| `kotlin-parcelize` | Parcelable generation |
| `kotlin-plugin.compose` | Compose compiler |
| `com.google.devtools.ksp` | Kotlin Symbol Processing |
| `com.google.dagger.hilt.android` | Hilt DI |
| `realm-android` | Realm database |
| `com.github.triplet.play` | Google Play publishing |
| `com.google.gms.google-services` | Firebase |
| `com.google.firebase.crashlytics` | Crashlytics |

---

## Key Architecture Patterns

- **UI**: Jetpack Compose with Material 3
- **Navigation**: Navigation Compose
- **Dependency Injection**: Hilt with navigation-compose support
- **Database**: Realm
- **Networking**: Retrofit + OkHttp + Kotlinx Serialization
- **Async**: Kotlin Coroutines + Flow
- **Image Loading**: Coil
- **PDF**: Nutrient (PSPDFKit)
- **Video**: Media3 ExoPlayer
- **Logging**: Timber
- **Crash Reporting**: Firebase Crashlytics
