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
The app opens with real Laravel sign-in/create-account authentication.
Debug builds connect to the local development backend.
The `feature/note-sync` branch extends the separate account-scoped Synced Classes
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
- Internally tested account class workspace with offline edits, durable retry state,
  change-feed downloads, deletion tracking, and explicit local/server conflict choices.
- Offline synced notes with titles/content/tags, full conflict comparisons, and
  recovery drafts when a parent class is deleted.
- Google and Microsoft browser sign-in with encrypted, restart-safe handoff state,
  explicit completion/cancellation, and the same session/sign-out lifecycle as
  password sign-in. Backend provider registrations are required before real use.

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
authentication or password recovery actions are shown. Profile offers Sign out;
the Backend Account developer panel and Local-only study space card are removed.

The local profile is **separate from any backend account**. Study data is stored in this
app's private device storage. Uninstalling or clearing app data removes it.
Automatic backups and device transfers are excluded; there is no export yet.
The app requests Internet access for backend authentication, but no camera or
notification permissions.

## Welcome, sign-in, and sign-out

Launching without a saved, unexpired sign-in shows a welcome screen with
**Sign in**, **Create account**, **Sign in with Google**, and
**Sign in with Microsoft**. Study tabs are unavailable until sign-in
succeeds. Restoring a saved session opens the main workspace. These changes do
not change the original Room tables or associate existing study data with an account.
There is deliberately
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

On the welcome screen, create an account (matching passwords, at least 12
characters and at most 72 UTF-8 bytes) or sign in. Successful authentication
opens Home. **More > Profile > Sign out** revokes the current device token,
removes the saved sign-in, and returns to welcome with the study tabs hidden.
Back navigation and relaunch cannot reopen the signed-out workspace. Signing in
again starts on Home. A failed network logout keeps the sign-in and displays an
error so it can be retried; there is no silent offline sign-out.
Signing out preserves device-only study data, account-scoped offline notes,
pending changes, and recovery drafts. The main workspace remains shared on the
device, as before; authentication is not a migration to account-scoped main data.
The experimental sync workspace is retained for automated tests, not exposed by
the app's navigation. Backend connectivity checks belong in the terminal:

```sh
curl -i -H 'Accept: application/json' http://127.0.0.1:8088/api/v1/me
```

An unauthenticated 401 is expected. Use the backend's API tests for authenticated
behavior instead of placing real tokens in shell history.

### Provider sign-in

Configure the Google and Microsoft **Web** OAuth clients using the setup section
in the sibling backend project's [README](../Student_Haven_Backend/README.md).
Only the backend stores provider secrets; no Android OAuth client secret, Firebase,
or embedded WebView is used. Microsoft accepts personal and work/school accounts
through its `common` tenant. Without credentials, the buttons show an explicit
provider-not-configured error rather than signing in.

Choose a provider on welcome, finish consent/sign-in in the system browser, and
return through the app link. If the browser does not reopen the app, switch back
and choose **Finish provider sign-in**. A still-pending browser response keeps the
request available for retry. **Cancel provider sign-in** discards the encrypted
local verifier and enables starting over. Pending requests survive Activity
recreation/process restart and expire after ten minutes. No password is collected
by the app for these flows.

The backend's registered callbacks for local development are:

- Google: `http://localhost:8088/api/v1/auth/social/google/callback`
- Microsoft: `http://localhost:8088/api/v1/auth/social/microsoft/callback`

`adb reverse tcp:8088 tcp:8088` must remain active for the external browser too.
The app receives only `studenthaven://oauth/callback?attempt=<uuid>`, checks it
against the encrypted pending request, and proves possession of a random verifier
to obtain its Sanctum session. API/provider tokens are never put in callback URLs.
This proof binds the mobile handoff; provider code redemption is the backend's
confidential Web OAuth flow. Unexpected callbacks cannot sign in.

Matching emails do **not** link accounts: Google, Microsoft and password logins
remain separate backend identities, as requested. This does not change the
original main workspace's shared device-local storage behavior described above.
Sign-out returns to welcome and revokes the app's device token, not the provider's
browser login. Provider MFA is subject to that provider/account's policies.

Automated tests cover proof persistence, challenge correctness, wrong callbacks,
untrusted browser URLs, pending retry, cancellation, expiry, failure, and welcome
routing. Backend tests exercise actual provider adapters with mocked HTTP,
including Microsoft JWT signature/audience rejection. Real sign-in still requires
console registration and live acceptance tests. Production also requires an HTTPS
backend URL (release remains unconfigured) and HTTPS registered backend callbacks.

Tokens expire after 30 days. Expiration on session restoration and authenticated
401 responses return to welcome. Saved sign-ins are not proof that the server
session is still valid; revocation is detected on the next authenticated request.
Validation errors, throttling, malformed responses, and unavailable servers are
shown explicitly. No password is persisted or saved during Activity recreation.
Passwords must be reentered after rotation. Welcome-screen screenshots are blocked.

The token and cached account identity are AES-GCM encrypted with an
Android Keystore key in private preferences; backups remain disabled. Local
name/college preferences and Room data are independent of that encrypted session.
Do not use production credentials with this development HTTP endpoint.
Cleartext HTTP is permitted **only for loopback in debug builds**. Release builds
have no backend URL and do not include the HTTP exception. They show welcome
but cannot authenticate until a production HTTPS backend URL is configured;
an attempt reports the missing configuration rather than bypassing sign-in.
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
exists to avoid overwriting it. It verifies welcome registration, invalid login,
session restoration/recreation, logout routing, back navigation, token revocation,
and unchanged local profile/study counts. Study UI tests inject an isolated
test-only saved session, without a guest mode or production bypass.
If a test is interrupted, inspect the
local backend for leftover `android-...@example.com`, `sync-...@example.com`,
`notes-...@example.com`, or `note-ui-...@example.com`
test accounts and delete them.
The live suite waits one minute before its first temporary registration and
paces further registrations at five per minute, respecting the unchanged API
rate limits. Allow a few minutes for a complete live run; do not run another
registration suite against the same local server concurrently.

## Experimental account class and note synchronization (automated tests)

Use the backend's `backend-note-sync` branch with its latest migrations.
The former Profile > Backend Account developer entry is removed; the following
workspace and dialogs are exercised directly by instrumentation tests only.
They are not the app's main study workspace and have no navigation entry.

- This is a separate classes-and-notes workspace, **not the main Classes or Notes
  tabs**. Existing device-only classes/notes, cards, assignments, grades, and the
  main profile do not move or synchronize.
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
- Restoring class metadata after a server deletion does not restore its deleted
  notes, cards, or assignments.

### Synced Notes and recovery drafts

Open **Synced Notes** on an active class, then **Add note**. Enter a title,
plain-text content, and optional tags (one per line). **Save** persists locally,
even before the parent class has been uploaded. **Sync now** synchronizes class
metadata first, then notes. It is manual on every device; there is no background
sync. Notes remain account-scoped and separate from the main Notes tab.

- Titles allow 255 Unicode characters, content 200,000, and up to 50 unique tags
  of 100 characters each. Content preserves indentation and trailing newlines.
- Saved notes survive app restarts. An open editor retains its unsaved text across
  rotation in its ViewModel, not a large saved-state Bundle. Save before leaving:
  unsaved editor text does not survive process termination. Closing an open editor
  requires confirmation; synchronization is blocked until it is saved or cancelled.
- **View versions** shows full, scrollable content and tags. A conflict retains
  both local and server versions until **Keep local** or **Use server** is chosen.
  Cancel leaves both untouched. Choosing local against a note-deletion conflict
  can explicitly restore that note only while its parent class is active.
- Immutable dispatched note requests are persisted before sending and replayed
  with the same mutation ID after an unavailable server, lost response, or restart.
  Editing that row is blocked until its acknowledgement/conflict is recovered.
- If another device deletes a class while this device has pending note edits or
  new notes, those local versions become **Recovery drafts**. They never upload
  to the deleted class and do not automatically restore it. Existing saved server
  conflicts remain readable. Clean deleted notes are hidden rather than recovered.
- Open **Recovery drafts** from Synced Classes or Synced Notes, choose
  **Copy into active class**, select a class, and confirm. This creates a new note
  UUID with the local title/content/tags. The original recovery draft remains,
  including any saved server conflict. A recovery draft can be copied once;
  repeated clicks cannot create duplicates. Sync to upload the copied note.
- Recovery drafts are local, not cloud backups. Clearing app storage or
  uninstalling loses unsynchronized notes and recovery drafts.

Room schema exports cover both databases. The account sync database migrates
explicitly from version 1 to 2, preserving classes, pending requests/conflicts,
and class cursors while adding note tables. The original study database stays at
version 1 and is not destructively migrated. Do not reset/reseed the backend to
reuse account IDs while keeping device caches: reset only the separate sync cache
for disposable testing, or use a fresh emulator. An installation points to one
backend environment; multi-server switching is not supported.

The opt-in live tests use two independent Room stores/sessions as two devices,
exercise offline failure/retry, lost acknowledgements plus reopening, concurrent
edits, local/server conflict choices, edit/delete conflicts, account isolation,
and import consent through the actual UI. Note tests additionally cover rotation,
title/content/tag round trips, concurrent edits, deleted-parent races (including a
pending class conflict), recovery copying, maximum escaped Unicode content,
and the version-1 database migration. Backend tests verify note/class pagination,
idempotency, and version preconditions on ordinary update/delete routes.

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
