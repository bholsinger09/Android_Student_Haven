<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use PHPUnit\Framework\Attributes\DataProvider;
use Tests\TestCase;

class StudyApiTest extends TestCase
{
    use RefreshDatabase;

    private function authenticate(): User
    {
        $user = User::factory()->create();
        $this->withToken($user->createToken('test-device')->plainTextToken);

        return $user;
    }

    private function createCourse(): string
    {
        return $this->postJson('/api/v1/courses', ['name' => 'Biology'])
            ->assertCreated()->json('data.id');
    }

    public function test_registration_login_profile_and_logout(): void
    {
        $credentials = [
            'name' => 'Student',
            'email' => 'STUDENT@example.com',
            'password' => 'long-test-password',
            'password_confirmation' => 'long-test-password',
            'device_name' => 'Android',
        ];
        $registration = $this->postJson('/api/v1/auth/register', $credentials)
            ->assertCreated()->assertJsonPath('data.email', 'student@example.com')
            ->assertJsonMissingPath('data.password')->assertJsonStructure(['token', 'expires_at']);
        $this->assertDatabaseMissing('personal_access_tokens', ['token' => $registration->json('token')]);
        $this->assertNotSame('long-test-password', User::firstOrFail()->password);

        $token = $registration->json('token');
        $profile = $this->withToken($token)->getJson('/api/v1/me')->assertOk();
        $this->assertStringContainsString('no-store', $profile->headers->get('Cache-Control'));
        $this->patchJson('/api/v1/me', ['name' => 'New Name', 'college' => 'My College'])
            ->assertOk()->assertJsonPath('data.college', 'My College');
        $this->postJson('/api/v1/auth/logout')->assertNoContent();
        // Forget the resolved guard between simulated requests to exercise revocation.
        app('auth')->forgetGuards();
        $this->getJson('/api/v1/me')->assertUnauthorized();

        $login = $this->postJson('/api/v1/auth/login', $credentials)
            ->assertOk()->assertJsonPath('data.name', 'New Name');
        app('auth')->forgetGuards();
        $this->withToken($login->json('token'))->getJson('/api/v1/me')->assertOk();
    }

    public function test_invalid_credentials_and_registration_are_rejected(): void
    {
        $this->postJson('/api/v1/auth/register', [
            'name' => 'Student', 'email' => ['bad'], 'password' => 'short', 'device_name' => 'test',
        ])->assertUnprocessable()->assertJsonValidationErrors(['email', 'password']);
        $this->postJson('/api/v1/auth/login', [
            'email' => 'missing@example.com', 'password' => 'invalid-password', 'device_name' => 'test',
        ])->assertUnprocessable()->assertJsonValidationErrors('email');
    }

    public function test_duplicate_email_is_case_insensitive(): void
    {
        User::factory()->create(['email' => 'student@example.com']);
        $this->postJson('/api/v1/auth/register', [
            'name' => 'Student', 'email' => 'STUDENT@example.com',
            'password' => 'long-test-password', 'password_confirmation' => 'long-test-password',
            'device_name' => 'test',
        ])->assertUnprocessable()->assertJsonValidationErrors('email');
    }

    public function test_passwords_cannot_exceed_bcrypt_byte_limit_or_contain_nulls(): void
    {
        foreach ([str_repeat('x', 73), str_repeat("\u{00e9}", 37), "long-password\0suffix"] as $password) {
            $this->postJson('/api/v1/auth/register', [
                'name' => 'Student', 'email' => 'student@example.com', 'password' => $password,
                'password_confirmation' => $password, 'device_name' => 'test',
            ])->assertUnprocessable()->assertJsonValidationErrors('password');
        }
        $password = str_repeat('x', 72);
        $this->postJson('/api/v1/auth/register', [
            'name' => 'Student', 'email' => 'student@example.com', 'password' => $password,
            'password_confirmation' => $password, 'device_name' => 'test',
        ])->assertCreated();
        $this->postJson('/api/v1/auth/login', [
            'email' => 'student@example.com', 'password' => $password.'suffix', 'device_name' => 'test',
        ])->assertUnprocessable()->assertJsonValidationErrors('password');
    }

    public function test_anonymous_and_expired_tokens_cannot_access_data(): void
    {
        $this->getJson('/api/v1/courses')->assertUnauthorized();
        $user = User::factory()->create();
        $token = $user->createToken('expired', ['*'], now()->subMinute())->plainTextToken;
        $this->withToken($token)->getJson('/api/v1/me')->assertUnauthorized();
    }

    public function test_account_deletion_requires_password_and_removes_all_data_and_tokens(): void
    {
        $user = $this->authenticate();
        $course = $this->createCourse();
        $this->postJson("/api/v1/courses/$course/notes", ['title' => 'Private note'])->assertCreated();
        $this->deleteJson('/api/v1/me', ['password' => 'wrong'])->assertUnprocessable();
        $this->deleteJson('/api/v1/me', ['password' => 'password'])->assertNoContent();
        $this->assertDatabaseMissing('users', ['id' => $user->id]);
        $this->assertDatabaseCount('courses', 0);
        $this->assertDatabaseCount('notes', 0);
        $this->assertDatabaseCount('personal_access_tokens', 0);
    }

    public function test_courses_crud_and_ownership_fields_cannot_be_overwritten(): void
    {
        $user = $this->authenticate();
        $other = User::factory()->create();
        $id = $this->postJson('/api/v1/courses', [
            'name' => 'Biology', 'code' => '', 'location' => null, 'user_id' => $other->id,
        ])->assertCreated()->assertJsonPath('data.code', '')->assertJsonPath('data.location', '')
            ->json('data.id');
        $this->assertDatabaseHas('courses', ['id' => $id, 'user_id' => $user->id]);
        $this->getJson('/api/v1/courses')->assertOk()->assertJsonCount(1, 'data');
        $this->getJson("/api/v1/courses/$id")->assertOk();
        $this->patchJson("/api/v1/courses/$id", ['name' => 'Chemistry', 'user_id' => $other->id, 'version' => 1])
            ->assertOk()->assertJsonPath('data.name', 'Chemistry');
        $this->assertDatabaseHas('courses', ['id' => $id, 'user_id' => $user->id]);
        $this->deleteJson("/api/v1/courses/$id", ['version' => 2])->assertNoContent();
        $this->getJson("/api/v1/courses/$id")->assertNotFound();
        $this->getJson('/api/v1/courses/not-a-uuid')->assertNotFound();
    }

    public static function resources(): array
    {
        return [
            'notes' => ['notes', ['title' => 'Cell', 'content' => 'Cell: unit of life', 'tags' => ['biology']], ['title' => 'Cells']],
            'flashcards' => ['flashcards', ['front' => 'Cell?', 'back' => 'Unit of life'], ['front' => 'What is a cell?']],
            'assignments' => ['assignments', ['name' => 'Quiz', 'score' => 110, 'max_score' => 100], ['name' => 'Final quiz']],
        ];
    }

    #[DataProvider('resources')]
    public function test_nested_crud_and_parent_and_account_isolation(string $resource, array $create, array $update): void
    {
        $owner = $this->authenticate();
        $course = $this->createCourse();
        $otherCourse = $this->createCourse();
        $base = "/api/v1/courses/$course/$resource";
        $id = $this->postJson($base, $create)->assertCreated()->json('data.id');
        $this->getJson($base)->assertOk()->assertJsonCount(1, 'data');
        $this->getJson("$base/$id")->assertOk();
        $this->patchJson("$base/$id", $update)->assertOk();
        foreach (['get', 'patch', 'delete'] as $method) {
            $this->json(strtoupper($method), "/api/v1/courses/$otherCourse/$resource/$id", $update)
                ->assertNotFound();
        }
        $this->authenticate();
        app('auth')->forgetGuards();
        $this->getJson('/api/v1/courses')->assertOk()->assertJsonCount(0, 'data');
        $this->getJson("/api/v1/courses/$course")->assertNotFound();
        $this->patchJson("/api/v1/courses/$course", ['name' => 'Stolen'])->assertNotFound();
        $this->deleteJson("/api/v1/courses/$course")->assertNotFound();
        $this->getJson($base)->assertNotFound();
        $this->postJson($base, $create)->assertNotFound();
        $this->getJson("$base/$id")->assertNotFound();
        $this->patchJson("$base/$id", $update)->assertNotFound();
        $this->deleteJson("$base/$id")->assertNotFound();
        $this->withToken($owner->createToken('return')->plainTextToken);
        app('auth')->forgetGuards();
        $this->deleteJson("$base/$id")->assertNoContent();
        $this->assertDatabaseMissing($resource, ['id' => $id]);
    }

    public function test_note_deletion_preserves_cards_and_course_deletion_cascades(): void
    {
        $this->authenticate();
        $course = $this->createCourse();
        $note = $this->postJson("/api/v1/courses/$course/notes", ['title' => 'Cells'])
            ->assertCreated()->json('data.id');
        $card = $this->postJson("/api/v1/courses/$course/flashcards", [
            'front' => 'Cell?', 'back' => 'Unit of life', 'source_note_id' => $note,
        ])->assertCreated()->assertJsonPath('data.ease_factor', 2.5)->json('data.id');
        $this->deleteJson("/api/v1/courses/$course/notes/$note")->assertNoContent();
        $this->assertDatabaseHas('flashcards', ['id' => $card, 'source_note_id' => null]);
        $this->postJson("/api/v1/courses/$course/assignments", ['name' => 'Quiz'])->assertCreated();
        $this->postJson("/api/v1/courses/$course/notes", ['title' => 'Another'])->assertCreated();
        $this->deleteJson("/api/v1/courses/$course", ['version' => 1])->assertNoContent();
        foreach (['notes', 'flashcards', 'assignments'] as $table) {
            $this->assertDatabaseCount($table, 0);
        }
    }

    public function test_flashcard_source_must_belong_to_same_course(): void
    {
        $this->authenticate();
        $first = $this->createCourse();
        $second = $this->createCourse();
        $note = $this->postJson("/api/v1/courses/$first/notes", ['title' => 'Private'])
            ->assertCreated()->json('data.id');
        $this->postJson("/api/v1/courses/$second/flashcards", [
            'front' => 'Question', 'back' => 'Answer', 'source_note_id' => $note,
        ])->assertUnprocessable()->assertJsonValidationErrors('source_note_id');
    }

    public function test_validation_and_review_state_round_trip(): void
    {
        $this->authenticate();
        $course = $this->createCourse();
        $base = "/api/v1/courses/$course";
        $this->postJson("$base/notes", ['title' => 'Note', 'tags' => ['same', 'same']])
            ->assertUnprocessable();
        $this->postJson("$base/notes", ['title' => 'Note', 'content' => ''])->assertCreated()
            ->assertJsonPath('data.content', '');
        $this->postJson("$base/assignments", [
            'name' => 'Invalid', 'score' => -1, 'max_score' => 0, 'weight' => 0, 'due_date' => '2026-02-30',
        ])->assertUnprocessable()->assertJsonValidationErrors(['score', 'max_score', 'weight', 'due_date']);
        $this->postJson("$base/flashcards", [
            'front' => 'Q', 'back' => 'A', 'repetitions' => -1, 'ease_factor' => 1,
        ])->assertUnprocessable()->assertJsonValidationErrors(['repetitions', 'ease_factor']);
        $card = $this->postJson("$base/flashcards", ['front' => 'Q', 'back' => 'A'])
            ->assertCreated()->json('data.id');
        $this->patchJson("$base/flashcards/$card", [
            'repetitions' => 2, 'ease_factor' => 2.6, 'interval_days' => 6,
            'next_review_at' => '2026-11-15T10:00:00+00:00',
            'last_reviewed_at' => '2026-11-09T10:00:00+00:00',
        ])->assertOk()->assertJsonPath('data.repetitions', 2)
            ->assertJsonPath('data.next_review_at', '2026-11-15T10:00:00.000000Z');
        $this->patchJson("$base/flashcards/$card", ['next_review_at' => 'tomorrow'])
            ->assertUnprocessable();
        $this->patchJson("$base/flashcards/$card", ['next_review_at' => '2026-11-15T10:00:00-07:00'])
            ->assertOk()->assertJsonPath('data.next_review_at', '2026-11-15T17:00:00.000000Z');
        $assignment = $this->postJson("$base/assignments", ['name' => 'Ungraded'])
            ->assertCreated()->assertJsonPath('data.score', null)->json('data.id');
        $this->patchJson("$base/assignments/$assignment", ['score' => 120, 'due_date' => '2026-11-30'])
            ->assertOk()->assertJsonPath('data.score', 120)
            ->assertJsonPath('data.due_date', '2026-11-30');
        $this->patchJson("$base/assignments/$assignment", ['score' => null])
            ->assertOk()->assertJsonPath('data.score', null);
    }

    public function test_pagination_is_bounded(): void
    {
        $user = $this->authenticate();
        for ($i = 0; $i < 51; $i++) {
            $user->courses()->create(['name' => "Course $i"]);
        }
        $this->getJson('/api/v1/courses?per_page=1000000')->assertOk()
            ->assertJsonCount(50, 'data')->assertJsonPath('total', 51);
        $this->getJson('/api/v1/courses?page=2')->assertOk()->assertJsonCount(1, 'data');
    }

    public function test_login_and_registration_are_rate_limited(): void
    {
        $credentials = ['email' => 'unknown@example.com', 'password' => 'wrong', 'device_name' => 'test'];
        for ($i = 0; $i < 5; $i++) {
            $this->postJson('/api/v1/auth/login', $credentials)->assertUnprocessable();
        }
        $this->postJson('/api/v1/auth/login', $credentials)->assertTooManyRequests();
        for ($i = 0; $i < 5; $i++) {
            $this->postJson('/api/v1/auth/register', [])->assertUnprocessable();
        }
        $this->postJson('/api/v1/auth/register', [])->assertTooManyRequests();
    }

    public function test_suite_uses_isolated_postgresql_database(): void
    {
        $this->assertSame('pgsql', DB::connection()->getDriverName());
        $this->assertSame('student_haven_backend_test', DB::connection()->getDatabaseName());
        $this->assertSame('UTC', DB::selectOne('SHOW TIME ZONE')->TimeZone);
    }
}
