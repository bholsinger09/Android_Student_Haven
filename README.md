# Student Haven Backend

Standalone JSON API built with **Laravel 13, PHP 8.3+, Laravel Sanctum, and
PostgreSQL**. This directory is a separate local checkout of the public
[`backend` branch](https://github.com/bholsinger09/Android_Student_Haven/tree/backend)
in the Android repository. Its history is independent of the Android app branches;
do not merge it into the app branches. The Android `feature/local-backend-auth`
branch supports local authentication against this API; study-data synchronization
and iOS integration are not implemented.

## Scope

Implemented:

- Registration and login with hashed passwords and hashed, revocable bearer tokens.
- Thirty-day device tokens; logout revokes the current token.
- Profile name/college updates and password-confirmed account deletion.
- Private classes (called `courses` in the API), notes/tags, flashcards/review
  state, and assignments/grades, using server-generated UUIDs.
- Ownership-scoped CRUD, fixed-size pagination, validation, and rate limits.
- Course deletion cascades to its notes, cards, and assignments. Note deletion
  keeps flashcards and clears their source-note reference.
- PostgreSQL migrations, foreign keys, numeric constraints, and API tests.

Not implemented: Android/iOS integration, CloudKit migration, offline sync,
client-ID import/idempotent writes, conflict resolution, deletion tombstones,
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
`Content-Type: application/json`. All routes except registration/login require
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
| DELETE | `/me` | Requires `password`; permanently removes account, owned study data, and all tokens |

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

The API assigns IDs and ownership; supplied IDs, `user_id`, and `course_id` are
not writable. Child resources cannot move between courses. Unknown fields are
ignored rather than persisted. Collections contain only the authenticated user's
data. Another user's course/resource, a mismatched child/parent, or malformed UUID
returns 404.

Successful single-object responses use `{"data": {...}}`. Create returns 201,
read/update 200, and delete/logout 204 with no body. Lists use Laravel's paginator:
`data`, `current_page`, `per_page`, `total`, `last_page`, and page URLs. Page size is
fixed at 50; use `?page=2`. Validation returns 422 with `message` and `errors`,
missing/expired authentication returns 401, and throttling returns 429.
Private successful responses use `Cache-Control: no-store, private`.

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
ungraded scores, timestamp offsets, and cascading deletes.

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
- Design mobile account isolation, initial local-data import, offline outbox,
  idempotency, conflicts, and deletion synchronization before connecting Room.
  Add equivalent iOS integration separately; existing CloudKit data is not imported.

No hosting account or paid service is provisioned. Source publication does not
deploy the API, upload a database, or publish local credentials.
