# Android Student Haven

Initial native Android port of StudentStudyHaven, written in **Java with XML
layouts**, using **MVVM, repositories, focused use cases, and Room**.

This is a separate project and Git repository. The Swift/iOS project is not a
build dependency and is not modified by this app.

The launcher icon uses the same artwork as the iOS AppIcon asset catalog
(`ios-marketing-1024@1x.png`), copied into Android resources. Android applies its
launcher-specific shape mask; the illustration is not replaced or redrawn.

## Status and scope

This first version is **local-first**, not a feature-complete iOS replacement.
There is no simulated login or pretend cloud sharing.

Implemented:

- Create, edit, and delete classes, including course code, professor, and location.
- Create, edit, delete, tag, and search class notes.
- Generate flashcards using the iOS app's `Term is definition` and
  `Term: definition` patterns.
- Create/edit/delete flashcards; reveal answers and record reviews.
- SM-2 review scheduling with the same ease adjustment, passing threshold,
  first/second intervals, and integer truncation as the Swift implementation.
  Scheduling uses calendar days, including daylight-saving transitions.
- Create/edit/delete assignments with due dates, optional scores, maximum points,
  and relative weights. Graded assignments determine the current weighted grade.
  Extra credit is supported. An ungraded class shows no grade rather than an F.
- Local name and college profile, dashboard counts, and persistent Room data.
- Lifecycle-aware screen data, restored page/class/search selection, and
  editor drafts restored across Activity recreation.
- iOS-inspired black background, purple accent (#BA54D4), large navigation
  titles, grouped cards, centered empty states, and full-screen grouped editors.
- Bottom tabs for Home, Classes, Notes, and Flashcards, plus a More screen for
  Grades, Study Groups, and Profile (the overflow structure used by a seven-tab
  SwiftUI TabView on iPhone).
- Profile avatar/initials and statistics; blue/purple flashcard question/answer
  cards, session progress, and explicit review ratings.
- Searchable college picker using the ten college names in the Swift model,
  with custom college entry and a persisted selection.

Deferred iOS features:

- Cloud accounts, Apple sign-in, password reset, CloudKit migration, cloud backup,
  cross-device sync, and shared study groups/messages/member search/QR invites.
- Class timetable editing and overlap detection.
- Linked-note navigation/knowledge graph.
- Group study sessions, study timers, and notifications.
- GPA, required-score projections, and extended grade analytics.
- College statistics/facts and rich onboarding.

The Android layouts now follow the existing SwiftUI screens, but are not
pixel-identical. Android system dialogs, typography, and keyboard behavior remain
native. The college dashboard shows real local study counts and shortcuts instead
of fabricated college facts or enrollment statistics. Grades show the implemented
weighted class grade rather than a nonfunctional GPA display. Study Groups is an
explicitly unavailable screen until cloud sharing is implemented. No fake
authentication, password actions, or online account controls are shown.

The local profile is **not an authenticated account**. Data is stored in this
app's private device storage. Uninstalling or clearing app data removes it.
Automatic backups and device transfers are excluded; there is no export yet.
The app does not request Internet, camera, or notification permissions.

## Open and build

Open this directory in Android Studio and let Gradle sync. Use:

- JDK 17 or 21.
- Android SDK Platform 36 and Build Tools (Gradle will identify missing packages).
- Android 8.0 / API 26 or newer for execution.
- The checked-in Gradle 8.14 wrapper; Android Gradle Plugin 8.11.1.

For terminal builds, configure `JAVA_HOME` and `ANDROID_HOME` for your machine:

```sh
./gradlew testDebugUnitTest exportDebugApk lintDebug
```

The exported installable debug APK is `dist/student-haven-debug.apk`.
Compiler output and reports live under
`~/.gradle/student-haven-builds/<project-path-hash>/app/` rather than Documents.
This avoids duplicate/conflict files in synced Documents folders; Gradle prints
the report paths. Android Studio uses the same configured build directory.
`exportDebugApk` builds and copies the APK back into this project's `dist/`.
With an emulator or device connected:

```sh
./gradlew connectedDebugAndroidTest
./gradlew installDebug
adb shell am start -n com.studenthaven.android/.presentation.MainActivity
```

Use the bottom tabs; Grades and Profile are under More. Create a class first.
Notes, cards, and assignments are scoped to the class picker below the title.
Tap a class, note, or assignment to edit it; deletion is inside the editor.
An existing note's Generate Flashcards action atomically saves the current draft
and creates its cards. Invalid definitions leave the draft open.
Tap a flashcard to review it or use Start Studying for a session. Long-press a card
or use Edit Flashcards to edit/delete it. Tap the card to flip it; review ratings
appear only after revealing the answer. Close leaves unreviewed cards unchanged.

This is a development build, not a Play Store release. The application ID is
currently `com.studenthaven.android`; confirm the production ID before publishing.

## Architecture

```text
presentation/  Activity, Fragment, editor DialogFragment, shared ViewModel
     |          LiveData state, SavedStateHandle, explicit operation errors
     v
domain/        Plain-Java generation, review scheduling, and grade use cases
data/          Repository contract, Room implementation, entities, and DAO
```

`HavenApplication` is the composition root. The Activity and Fragments do not
access the database directly. `StudyViewModel` delegates storage to the repository,
runs writes on a serial background executor, and exposes loading/write errors.
Room observations refresh the UI; there is no main-thread database access and no
destructive migration fallback.

This is pragmatic rather than strict Clean Architecture: repository observations
use AndroidX LiveData and simple Room entities serve as local data models.
The domain algorithms are independent of Android UI and Room. Separate domain
models/DTO mapping can be added when a real remote data source requires them;
there is no speculative multi-module or dependency-injection framework.

Course deletion cascades to its notes, cards, and assignments after confirmation.
Deleting a note preserves generated cards and clears their source-note reference.
Flashcard batch insertion and note-save-plus-generation are transactional through Room. Checked-in Room schemas
provide a baseline for explicit migrations when the schema changes.

## Tests

- JVM tests exercise definition extraction, weighted grades, invalid numerical
  inputs, letter-grade thresholds, SM-2 progression/reset, and daylight saving.
- Device tests exercise Room reopening/persistence, foreign-key cleanup, missing
  updates, and the class → note → generation → review → grade → delete UI flow.
- UI tests check validation, editor draft restoration on Activity recreation,
  bottom-tab selection, More navigation, college selection/search, the explicit
  local-only group screen, and review-answer restoration after recreation.

Dependencies are pinned for a reproducible initial build. Lint may report newer
library versions; upgrades should be deliberate and tested.

## Git

The public repository is [bholsinger09/Android_Student_Haven](https://github.com/bholsinger09/Android_Student_Haven),
with `main` as its primary branch. Build output, local SDK paths, IDE state, and
signing keys are ignored. Keep the Gradle wrapper and Room schema files in version control.

Before cloud work, choose a backend and decide whether the iOS app will migrate
to it. Apple's private CloudKit database is not a drop-in Android data source.
