# Android Student Haven

Initial native Android port of StudentStudyHaven, written in **Java with XML
layouts**, using **MVVM, repositories, focused use cases, and Room**.

This is a separate project and Git repository. The Swift/iOS project is not a
build dependency and is not modified by this app.

The launcher icon uses the same artwork as the iOS AppIcon asset catalog
(`ios-marketing-1024@1x.png`), copied into Android resources. Android applies its
launcher-specific shape mask; the illustration is not replaced or redrawn.
The source contains JPEG data despite its catalog filename, so the Android copy
uses `.jpg` to keep release resource compilation valid without changing image bytes.

## Status and scope

This version is **local-first**, not a feature-complete iOS replacement.
Debug builds include real Laravel account authentication for local development.
The `feature/class-sync` branch adds a separate account-scoped Synced Classes
workspace. Signing in alone does not upload or move existing local study data.
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
- Debug-only account class workspace with offline edits, durable retry state,
  change-feed downloads, deletion tracking, and explicit local/server conflict choices.

Deferred iOS features:

- Production cloud accounts, Apple sign-in, password reset, CloudKit migration, cloud backup,
  synchronization of main study data, and shared study groups/messages/member search/QR invites.
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
authentication or password recovery actions are shown. Optional local-backend
account controls are available only in debug builds under Profile.

The local profile is **separate from any backend account**. Study data is stored in this
app's private device storage. Uninstalling or clearing app data removes it.
Automatic backups and device transfers are excluded; there is no export yet.
The app requests Internet access for backend authentication, but no camera or
notification permissions.

## Local Laravel authentication (debug only)

Optional registration, login, logout, session restoration, and a backend connection
test are available. They do not change the original Room tables or associate
existing study data with an account. There is deliberately
no automatic import/upload or profile overwrite. Anyone using the device still
sees the same local classes and notes, including after switching backend accounts.

In the separate `Student_Haven_Backend` project, configure PostgreSQL and migrate
as described in its README, then start:

```sh
php artisan serve --host=127.0.0.1 --port=8088
```

With the emulator or USB-connected Android device running:

```sh
adb reverse tcp:8088 tcp:8088
./gradlew installDebug
```

Debug builds use `http://127.0.0.1:8088/api/v1/`; `adb reverse` forwards device
loopback to the Mac. Repeat the reverse command after restarting/reconnecting a
device. With multiple devices use `adb -s <serial> reverse ...`. The Laravel
server can remain bound to Mac loopback; do not expose it on your LAN.

Open **More > Profile > Backend Account**. Test the connection while signed out,
create an account (matching passwords, at least 12 characters and at most
72 UTF-8 bytes), or sign in. Test the connection again to verify the authenticated
profile. Sign out contacts Laravel to revoke the current device token. A failed
network logout keeps the sign-in so you can retry; **Forget local sign-in**
removes it only from this device without revoking the server token.

Tokens expire after 30 days. The account screen reports expired/revoked sessions
and clears the saved token after an authenticated 401. Saved sign-ins are not
proof that the server session is still valid; use the connection test to verify.
Validation errors, throttling, malformed responses, and unavailable servers are
shown explicitly. No password is persisted or saved during Activity recreation.
Passwords must be reentered after rotation. Account screenshots are blocked.

The token and cached account identity are AES-GCM encrypted with an
Android Keystore key in private preferences; backups remain disabled. Local
name/college preferences and Room data are independent of that encrypted session.
Do not use production credentials with this development HTTP endpoint.
Cleartext HTTP is permitted **only for loopback in debug builds**. Release builds
have no backend URL or account entry point and do not include the HTTP exception.
Configure a real HTTPS service and complete integration of the main study space
before production use.

Default emulator tests exercise HTTP error handling using a temporary local
server and test encrypted storage without contacting Laravel. The real-backend
UI workflow is opt-in and creates/deletes a temporary account:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.backendIntegration=true
```

Start Laravel and set up `adb reverse` first. Use a disposable local development
database, never a production service. The live test skips if a sign-in already
exists to avoid overwriting it. It verifies registration, invalid login, profile
verification, persistence/recreation, token revocation, expired-session handling,
and unchanged local profile/study counts. If a test is interrupted, inspect the
local backend for leftover `android-...@example.com` or `sync-...@example.com`
test accounts and delete them.

## Account class synchronization (debug only)

Use the backend's `backend-class-sync` branch with its latest migrations, then
open **More > Profile > Backend Account > Synced Classes** after signing in.

- This is a separate class-only workspace, **not the main Classes tab**. Notes,
  cards, assignments, grades, main profile, and existing device-only classes do not
  move or synchronize. This separation is intentional for the first sync stage.
- Add or edit a class with **Save offline**. **Sync now** explicitly uploads pending
  changes and downloads the account's latest changes. There is no background job,
  automatic reconnect sync, or real-time listener yet; tap it on each device.
- A second emulator/device signed into the same local account sees the class
  after Sync now. Configure `adb reverse` separately on each device.
- **Copy device-only classes** requires confirmation and copies only class metadata.
  Originals and their related records remain untouched. Imports are atomic and
  deduplicated by source class per account, including previously deleted copies.
  A later edit to an original does not update its independent imported copy.
- Offline classes, pending writes, acknowledged versions, remote conflicts, and
  pull cursors are stored in a separate Room database keyed by backend user ID.
  Switching accounts selects a different workspace; signed-out/expired sessions
  cannot open it. Local cache remains for the next sign-in to the same account.
  It is app-private device storage, not separately encrypted per account.
- Tokens are needed for network calls; offline editing uses a saved, unexpired
  session. Server-side revocation is detected on the next request.
- Requests have durable UUID mutation IDs. If a response is lost, the exact
  payload is retried rather than creating duplicate classes. Until that dispatched
  write is acknowledged, further editing/deletion of that row is blocked with an
  explicit message. You can still create/edit other rows.
- A **Resolve conflict** action compares the local and server versions, including
  deletion. Cancel keeps both unresolved. **Keep local** queues the chosen version
  against the server's version; **Use server** discards the pending local edit.
  A newer change can cause another conflict. No last-write-wins overwrite occurs.
- Restoring class metadata after a server deletion cannot restore that class's
  deleted server notes, cards, or assignments. Those features are not synced here.

Room schema exports cover both databases. The original study database stays at
version 1 and is not destructively migrated. Do not reset/reseed the backend to
reuse account IDs while keeping device caches: reset only the separate sync cache
for disposable testing, or use a fresh emulator. An installation points to one
backend environment; multi-server switching is not supported.

The opt-in live tests use two independent Room stores/sessions as two devices,
exercise offline failure/retry, lost acknowledgements plus reopening, concurrent
edits, local/server conflict choices, edit/delete conflicts, account isolation,
and import consent through the actual UI. Backend tests verify pagination and
the version preconditions on ordinary course update/delete routes.

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
