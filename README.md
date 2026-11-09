# Student Haven Backend

Standalone JSON API built with **Laravel 13, PHP 8.3+, Laravel Sanctum, and
PostgreSQL**. This directory is a separate local checkout of the public
[`backend` branch](https://github.com/bholsinger09/Android_Student_Haven/tree/backend)
in the Android repository. Its history is independent of the Android app branches;
do not merge it into the app branches. This work is on `backend-note-sync`,
paired with Android `feature/note-sync`. The Android app opens with welcome
sign-in/create-account and returns there on sign-out. Backend connectivity checks
are terminal-only; the separate account classes-and-notes workspace is retained
for automated tests without an app navigation entry;
card/assignment synchronization and iOS integration are not implemented.

## Scope

Implemented:

- Registration and login with hashed passwords and hashed, revocable bearer tokens.
- Google and Microsoft browser sign-in broker (requires provider registrations).
  Microsoft supports personal and work/school accounts. Provider identities never
  automatically link to password accounts or each other by email.
- Thirty-day device tokens; logout revokes the current token.
- Profile name/college updates; password-confirmed deletion for password accounts,
  or a provider sign-in within the last five minutes for provider accounts.
- Private classes (called `courses` in the API), notes/tags, flashcards/review
  state, and assignments/grades, using server-generated UUIDs.
- Ownership-scoped CRUD, fixed-size pagination, validation, and rate limits.
- Course deletion tombstones its live notes and permanently deletes its cards and
  assignments. Note deletion keeps flashcards and clears their source-note reference.
- PostgreSQL migrations, foreign keys, numeric constraints, and API tests.
- Class synchronization with client UUIDs, durable idempotency receipts, per-account
  ordered change feeds, deletion tombstones, and explicit version conflicts.
- Equivalent note synchronization for titles, plain-text content, and tags, with
  deleted-parent rejection so clients can retain local recovery drafts.

Not implemented: iOS integration, CloudKit migration, synchronization of
cards or assignments, automatic background sync,
shared groups/chat, attachments, email verification, password reset, push
notifications, or production hosting. Review scheduling and grade calculation
remain client responsibilities; this API stores their data.

This is a tested backend foundation, **not an internet-deployed production service**.
Do not treat it as a drop-in CloudKit synchronization engine.

## Local setup

Requirements: PHP 8.3+ with `pdo_pgsql`, Composer 2, and PostgreSQL. Local
verification used PHP 8.5 and PostgreSQL 14; select a currently supported
PostgreSQL release for production. No Node/npm build is required for the API.

```sh
composer install
cp .env.example .env
php artisan key:generate
```

Set `DB_HOST`, `DB_PORT`, `DB_USERNAME`, and `DB_PASSWORD` in `.env` for your local
PostgreSQL installation. Create two dedicated databases using that role:

```sh
createdb student_haven_backend
createdb student_haven_backend_test
php artisan migrate
composer dev
```

`createdb` uses your PostgreSQL CLI connection settings; supply `-h` and `-U` if
needed. Use a dedicated, minimally privileged database role outside development.
The development server binds only to `127.0.0.1:8000`. It is not a production server.
`GET /` identifies the service; `GET /up` is Laravel's liveness endpoint, not
a database readiness or backup check.

Do not commit `.env`, credentials, tokens, database dumps, or application logs.
Commit `composer.lock` and run `composer install` rather than `composer update`
when reproducing this version.

## API v1

Base path: `/api/v1`. Send `Accept: application/json` and
`Content-Type: application/json`. All routes except registration/login and the
social sign-in endpoints require
`Authorization: Bearer <token>`. Native mobile bearer tokens are supported;
browser cookie/session authentication is not enabled.

### Accounts

| Method | Path | Body / behavior |
|---|---|---|
| POST | `/auth/register` | `name`, `email`, `password`, `password_confirmation`, `device_name` |
| POST | `/auth/login` | `email`, `password`, `device_name` |
| POST | `/auth/logout` | Revokes only the supplied device token |
| GET | `/me` | Current profile |
| PATCH | `/me` | Optional `name`, nullable `college` |
| DELETE | `/me` | Password accounts require `password`; provider accounts require a device token issued within the last five minutes. Permanently removes account, owned study data, and all tokens |

Passwords must be at least 12 characters on registration and at most 72 bytes
(bcrypt's limit); null characters are rejected. Emails are normalized to
lowercase. Registration returns 201; login returns 200. Both return:

```json
{
  "data": {"id": 1, "name": "Student", "email": "student@example.com"},
  "token": "<shown only when issued>",
  "token_type": "Bearer",
  "expires_at": "2026-12-09T12:00:00.000000Z"
}
```

The profile object also contains timestamps and nullable college/verification
fields. Passwords and remember tokens are never returned. Store mobile tokens
in platform-protected storage. Tokens expire after 30 days; there is no refresh
endpoint yet, so clients must sign in again. Account email/password changes are
not part of this initial API.

### Google and Microsoft sign-in setup

These are **confidential Web OAuth clients on this backend**, not Android client
registrations. Android opens the provider in a system browser; provider passwords
and client secrets never enter the Android app. No provider registration or
credentials ship with this repository. An unconfigured provider returns HTTP 503,
not a simulated account.

**Google**

1. In [Google Cloud Console](https://console.cloud.google.com/auth/overview), create
   or select a project and configure Google Auth Platform branding/audience.
   For development, keep the app in Testing and add your Google account as a test user.
2. Create an OAuth client with application type **Web application**.
3. Add this exact authorized redirect URI for the local debug setup:
   `http://localhost:8088/api/v1/auth/social/google/callback`.
   No JavaScript origin is needed for this server-side flow.
4. Put the client ID and secret directly in your local, ignored `.env` as
   `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`. Set `GOOGLE_REDIRECT_URI` to that
   same URI. Request only the default OpenID/profile/email scopes.

**Microsoft**

1. In [Microsoft Entra admin center](https://entra.microsoft.com/), select your
   directory and open **App registrations > New registration**. Registration may
   require an Entra directory and permission from its administrator.
2. Select **Accounts in any organizational directory and personal Microsoft
   accounts**. This backend uses the `common` tenant, not a single organization.
3. Add a **Web** redirect URI:
   `http://localhost:8088/api/v1/auth/social/microsoft/callback`.
   Do not register the app's custom scheme as the provider redirect.
4. Use delegated Microsoft Graph **User.Read** plus OpenID/profile scopes.
   Organizational policies may require administrator consent.
5. Create a client secret under **Certificates & secrets**. Save its **value**
   (not the secret ID) directly in `.env` as `MICROSOFT_CLIENT_SECRET`.
   Set `MICROSOFT_CLIENT_ID` to Application (client) ID, and
   `MICROSOFT_REDIRECT_URI` to the exact URI above. Track and rotate the secret
   before its expiration.

Never paste secrets into chat, commit them, or place them in Android resources,
Gradle settings, screenshots, logs, or shell history. After editing `.env`, run
`php artisan config:clear` and restart the local server. Run `php artisan migrate`
before testing, start Laravel on loopback port 8088, and run
`adb reverse tcp:8088 tcp:8088`. The browser's `localhost:8088` callback then reaches
this backend through the connected emulator/device. Both providers must have the
exact registered callback, including scheme, port and path.

For production, register **HTTPS backend-domain callbacks**, update the respective
environment values, configure the Android HTTPS backend URL, disable Laravel debug
output, and deploy with the normal production web server/security configuration.
Exclude OAuth callback query strings from web-server/proxy access logs and
error-reporting tools; they contain short-lived authorization codes and state.
The local HTTP callback exception is allowed only in Laravel `local`/`testing`.
Do not expose the development PHP server to the Internet. Live provider sign-in
must be tested after these registrations; automated adapter tests use mocked
provider HTTP responses and are not proof that your console settings work.

### Social sign-in protocol

| Method | Path | Body / behavior |
|---|---|---|
| POST | `/auth/social/{provider}/start` | `provider` is `google` or `microsoft`; body: `challenge`, a base64url SHA-256 hash of a random 32-byte verifier encoded as base64url without padding. Returns `attempt_id` and `authorization_url` |
| GET | `/auth/social/{provider}/callback` | Registered provider callback; consumes an unpredictable, hashed, single-use `state` and exchanges the authorization code server-side |
| POST | `/auth/social/exchange` | `attempt_id`, original 43-character `verifier`, `device_name`; returns the ordinary thirty-day Sanctum session after authorization |

Attempts expire in ten minutes. The app persists the verifier encrypted before
opening the HTTPS provider URL. After callback validation the backend returns
only `studenthaven://oauth/callback?attempt=<uuid>` to Android, never an API token,
provider token, code, email, or secret. An intercepted custom-scheme callback
alone cannot redeem the session: the verifier is required. This is a PKCE-style
**app-to-backend handoff binding**; provider code redemption itself uses the
confidential Web client secret, not a claimed provider PKCE implementation.

Google accounts use the provider's stable userinfo subject. Microsoft ID tokens
are signature/issuer/expiry validated, require the exact client audience, and use
tenant ID plus object ID, cross-checked against Graph. Provider access/refresh
tokens are not persisted. Email is profile metadata, not an identity/linking key;
Google, Microsoft, and password accounts can all share an email while having
separate server-owned study records. The migration preserves existing password
account IDs/passwords and retains password-email uniqueness within that namespace.
Rollback is blocked while provider accounts exist.

Pending/processing exchanges return 409 with `code: authorization_pending`; keep
the proof and retry after browser sign-in. Cancelled, failed, expired or consumed
attempts return 400 and require a new sign-in. Exchanges are single use: if the
server commits a token but the acknowledgement is lost, restart provider sign-in
rather than attempting to replay that exchange. Network errors retain Android's
pending proof for explicit retry; **Cancel provider sign-in** discards it locally.
After cancellation the app no longer has the proof needed to redeem that attempt;
the browser and server attempt are not cancelled, and provider cookies remain.

Social endpoints allow 30 requests/minute/IP. Expired attempt rows are pruned
hourly by Laravel's scheduler; configure the deployment scheduler, for example
`* * * * * cd /path/to/backend && php artisan schedule:run >> /dev/null 2>&1`.
Use `php artisan schedule:work` during local development if automatic cleanup is
desired. Expiry is enforced even when the scheduler is not running.
App sign-out revokes only the current Sanctum token, not Google/Microsoft browser
cookies or other devices. MFA remains governed by the selected provider/account
policy; choosing provider sign-in does not independently force MFA on every login.

### Study resources

Each resource supports `GET` collection, `POST` create, `GET /{id}`, `PATCH /{id}`,
`PUT /{id}` (also partial update), and `DELETE /{id}`.

| Collection path | Create fields and optional fields |
|---|---|
| `/courses` | Required `name`; optional `code`, `professor`, `location` |
| `/courses/{course}/notes` | Required `title`; optional `content`, `tags` (list of unique strings) |
| `/courses/{course}/flashcards` | Required `front`, `back`; optional fields below |
| `/courses/{course}/assignments` | Required `name`; optional `due_date`, `score`, `max_score`, `weight` |

Flashcard optional fields: nullable `source_note_id` (must reference a note in
the same course), `repetitions` (default 0), `ease_factor` (default 2.5, range
1.3-100), `interval_days` (default 0), `next_review_at` (default now), and nullable
`last_reviewed_at`. Review counts/intervals must be nonnegative integers at most
1,000,000. Review dates must use ISO 8601 with seconds and a timezone, e.g.
`2026-11-09T12:00:00+00:00` or `2026-11-09T12:00:00Z`, without fractional seconds.
Responses serialize timestamps in UTC. The database connection is explicitly UTC
regardless of the host's local timezone.

Assignments allow a nullable score (ungraded) and nullable `due_date` in
`YYYY-MM-DD` form. `max_score` defaults to 100 and `weight` to 1; both must be
positive. Scores must be nonnegative and may exceed the maximum for extra credit.
Scores, maximums, and weights are bounded at 1,000,000,000.

Text limits: names/titles 255 characters, course code 100, note content 200,000,
card front/back 20,000 each, and 50 tags of at most 100 characters each.
Empty/null course metadata and note content normalize to empty strings.
Nonempty note content is not trimmed; indentation and trailing newlines are retained.

The ordinary CRUD API assigns IDs and ownership; supplied IDs, `user_id`, and `course_id`
are not writable. The separate sync API accepts client-generated class/note UUIDs.
Child resources cannot move between courses. Unknown fields are
ignored rather than persisted. Collections contain only the authenticated user's
data. Another user's course/resource, a mismatched child/parent, or malformed UUID
returns 404.

Successful single-object responses use `{"data": {...}}`. Create returns 201,
read/update 200, and delete/logout 204 with no body. Lists use Laravel's paginator:
`data`, `current_page`, `per_page`, `total`, `last_page`, and page URLs. Page size is
fixed at 50; use `?page=2`. Validation returns 422 with `message` and `errors`,
missing/expired authentication returns 401, and throttling returns 429.
Private successful responses use `Cache-Control: no-store, private`.

### Class synchronization protocol

The class sync API is used by Android's separate Synced Classes workspace.
All endpoints require the same bearer authentication and user rate limit.
Existing course UUIDs are preserved by migration; previous records enter the
initial feed. No client-owned timestamps are used to order changes.

`GET /sync/courses?after=0` returns:

```json
{
  "data": [{"id": "<uuid>", "user_id": 1, "name": "Biology", "code": "BIO101",
            "professor": "", "location": "", "version": 1, "sync_revision": 1,
            "deleted_at": null, "created_at": "...", "updated_at": "..."}],
  "cursor": 1,
  "has_more": false
}
```

Feed pages have at most 100 records, ordered by an increasing per-account
revision. Persist applied records and the returned cursor in one local
transaction. Fetch the next page while `has_more` is true. The feed contains the
latest state per class, not an audit log of every intermediate edit. Start at
zero for a new cache, keep that account's cursor, and never reuse it for another
account. Tombstones are included; ordinary CRUD queries hide deleted classes.

`PUT /sync/courses/{client-generated-uuid}` accepts:

```json
{
  "mutation_id": "<new uuid for this exact write>",
  "base_version": 0,
  "deleted": false,
  "name": "Biology",
  "code": "BIO101",
  "professor": "",
  "location": ""
}
```

Use base version 0 for creation and the last acknowledged version for edits or
deletes. Non-deleted writes are full metadata replacements; omitted optional
fields become empty strings. Deletions require `deleted: true` and a matching
version. A successful response is `200 {"data": <saved class>}`. Reuse the same
mutation UUID and **identical payload** when retrying an unacknowledged request;
the original successful acknowledgement is replayed without applying another
change. UUID reuse with different content returns 409 without a `conflict` object.

Stale writes return `409 {"message": "...", "conflict": <current server class>}`.
The server does not modify either record on rejection; the client must preserve
its local draft. Choosing local submits a fresh mutation UUID against the chosen
server version. Choosing server discards the local pending change. Cancel keeps
both for later. If the server changes again, another conflict is returned.
An unknown class with a nonzero base version returns `conflict: null`; clients
must treat that as a protocol/state error, not silently recreate data.

Deleted class metadata remains as a versioned tombstone so offline devices cannot
resurrect it accidentally. Explicit restoration is a non-deleted write against
the tombstone's current version. Deletion tombstones all live notes and permanently
removes associated cards and assignments; restoring metadata does not restore those records.
Tombstones and successful mutation receipts are retained until account deletion.
Introduce a coordinated retention/reset protocol before pruning either.

**Compatibility change:** ordinary `/courses/{id}` PATCH/PUT/DELETE requests now
require `version` in the JSON body. Missing versions return 422 and stale versions
return 409 with `conflict`. This prevents non-sync clients from bypassing conflict
checks. Ordinary course creates/updates/deletes also appear in the sync feed.
All account study writes take a transactional user-row lock so assigned feed
revisions commit in order, and child creation cannot race soft deletion.
Rolling this migration back permanently removes tombstones rather than restoring
deleted classes. A subsequent reapplication renumbers versions/revisions, so
clients must rebuild their sync caches; do not roll it back with active clients.

### Note synchronization protocol

`GET /sync/notes?after=0` uses the same `data`, `cursor`, and `has_more` envelope,
with a **separate per-account note revision/cursor**. It returns the latest state
per note, including tombstones, ordered by `sync_revision`, **one note per page**.
One maximum-sized escaped Unicode note fits within the Android client's 4 MiB
response limit. Continue while `has_more` is true; apply each page and its cursor
atomically. Notes contain `id`, `course_id`, `user_id`, `title`, `content`, `tags`,
`version`, `sync_revision`, `deleted_at`, timestamps, and `parent_deleted`.
`parent_deleted` reflects the parent's deletion state when the payload is built.
Migration preserves existing note IDs and puts existing notes in the initial feed.

`PUT /sync/courses/{course-uuid}/notes/{client-note-uuid}` accepts:

```json
{
  "mutation_id": "<new uuid for this exact write>",
  "base_version": 0,
  "deleted": false,
  "title": "Cells",
  "content": "Cell: unit of life\n",
  "tags": ["biology", "exam"]
}
```

Non-deleted writes fully replace title/content/tags; omitted content/tags become
`""`/`[]`. Use base version 0 for creation and the last acknowledged version for
edits/deletes. Note IDs cannot move between classes. Success returns
`200 {"data": <saved note>}`. Receipts, replay rules, ownership, stale-version
conflicts, and explicit local/server choices match the class protocol, using a
separate note-mutation receipt table. No write is accepted on a stale base version.

An owned but deleted parent rejects a new mutation with
`409 {"code": "parent_deleted", "parent": <class tombstone>, "message": "..."}`.
The server stores neither the rejected mutation nor a replacement class/note.
A previously successful mutation still replays its exact original acknowledgement
even after parent deletion; clients must continue pulling current feeds rather than
treating old acknowledgements as current state.

The Android client synchronizes classes before notes, preserves pending local
notes as recovery drafts on parent deletion (including unsent notes and saved
conflicts), and blocks uploads from those drafts. It also persists an
acknowledgement-awaiting-feed flag to protect an acknowledged draft if deletion
arrives between a class pull and the note feed, including across restart.
An explicit recovery-copy action creates a new UUID in a chosen active class;
it never automatically restores the deleted parent or overwrites the original draft.
Recovery drafts live only in that device's cache; the API does not back them up.

**Compatibility change:** ordinary note PATCH/PUT/DELETE now require `version`.
Missing versions return 422; stale versions return 409 with `conflict`. All note
CRUD writes enter the note feed. Ordinary lists hide note tombstones, and cards
cannot reference a tombstoned source note. Deleting a note clears existing card
source references even though the note remains as a tombstone. Explicitly
restoring class metadata never restores notes automatically.

Note tombstones and receipts are retained until account deletion. Note migration
rollback permanently deletes tombstones; reapplying renumbers revisions, so
clients must rebuild the account sync cache. Do not roll back with active clients.
Configure PHP and the reverse proxy to accept at least a 4 MiB JSON request body;
test their actual limits with maximum Unicode notes before deployment.

Limits: registration 5 requests/minute/IP; login 5/minute/email+IP and
30/minute/IP overall; authenticated API 120/minute/user. These use Laravel's
configured cache (database locally). For multiple app servers, use a shared cache
and configure trusted proxies correctly before relying on IP-based limits.

Example registration:

```sh
curl -X POST http://127.0.0.1:8000/api/v1/auth/register \
  -H 'Accept: application/json' -H 'Content-Type: application/json' \
  -d '{"name":"Student","email":"student@example.com","password":"replace-with-a-long-password","password_confirmation":"replace-with-a-long-password","device_name":"Android"}'
```

Do not paste real production credentials/tokens into shared shell histories.

## Verification

```sh
php artisan config:clear
php artisan test
vendor/bin/pint --test
composer validate --strict
composer audit
```

Tests use **real PostgreSQL**, not SQLite. `phpunit.xml` forces the connection to
`pgsql` and database to `student_haven_backend_test`; it inherits host/username/
password from the environment. Create that dedicated database before running.
Tests migrate/reset its schema; **never place valuable data in that database**.
Clear cached Laravel configuration before running tests. Do not run these tests
on a production database server.
The test bootstrap refuses cached configuration, database URL overrides, and
any database name other than the dedicated test database before migrations run.

Tests cover account/token lifecycle, expired tokens, ownership boundaries,
nested-resource CRUD, validation, bounded pagination, rate limits, extra credit,
ungraded scores, timestamp offsets, and cascading deletes. Sync coverage includes
note/class idempotency, version conflicts, account/parent isolation, tombstones,
deleted-parent rejection, acknowledgement replay after deletion, pagination,
maximum escaped Unicode content, whitespace preservation, and migration rollback/backfill.

## Before production deployment

- Choose hosting/region and a supported PostgreSQL version. Serve `public/`
  through HTTPS with a production web server/PHP-FPM; never expose the project root.
- Use `APP_ENV=production`, `APP_DEBUG=false`, a persistent secret `APP_KEY`, the
  HTTPS `APP_URL`, and least-privilege database credentials. Configure verified
  database TLS for remote connections, trusted proxies, request-size limits,
  shared cache, logs/alerts, and secrets management.
- Install with `composer install --no-dev --optimize-autoloader`; run controlled
  migrations and configuration caching as part of deployment, with rollback plans.
- Configure and test encrypted backups/restores, retention, privacy/account
  deletion policy, monitoring, and rate-limit behavior behind the real proxy.
- Add verified email and password recovery with a real mail provider before
  public account rollout. Schedule `sanctum:prune-expired --hours=24` regularly.
- Extend the tested class/note sync protocol to cards and assignments before
  integrating the main Room study space. Add equivalent iOS integration separately;
  existing CloudKit data is not imported.

No hosting account or paid service is provisioned. Source publication does not
deploy the API, upload a database, or publish local credentials.
