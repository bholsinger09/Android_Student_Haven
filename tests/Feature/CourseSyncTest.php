<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;
use Tests\TestCase;

class CourseSyncTest extends TestCase
{
    use RefreshDatabase;

    private function login(?User $user = null): User
    {
        $user ??= User::factory()->create();
        $this->withToken($user->createToken('test')->plainTextToken);
        app('auth')->forgetGuards();

        return $user;
    }

    private function mutation(int $version = 0, bool $deleted = false): array
    {
        return ['mutation_id' => (string) Str::uuid(), 'base_version' => $version,
            'deleted' => $deleted, 'name' => 'Biology', 'code' => 'BIO101'];
    }

    public function test_create_retry_conflict_resolution_and_tombstones(): void
    {
        $this->login();
        $id = Str::uuid();
        $url = "/api/v1/sync/courses/$id";
        $create = $this->mutation();
        $first = $this->putJson($url, $create)->assertOk()->assertJsonPath('data.version', 1)->json();
        $this->putJson($url, $create)->assertOk()->assertExactJson($first);
        $this->assertDatabaseCount('courses', 1);
        $this->assertDatabaseCount('course_mutations', 1);
        $this->putJson($url, array_merge($create, ['name' => 'Different request']))
            ->assertConflict()->assertJsonMissingPath('conflict');
        $edit = $this->mutation(1);
        $edit['name'] = 'Remote edit';
        $this->putJson($url, $edit)->assertOk()->assertJsonPath('data.version', 2);
        $local = $this->mutation(1);
        $local['name'] = 'Local edit';
        $this->putJson($url, $local)->assertConflict()->assertJsonPath('conflict.name', 'Remote edit');
        // The rejected local content can be resubmitted only after an explicit choice.
        $local['base_version'] = 2;
        $local['mutation_id'] = (string) Str::uuid();
        $this->putJson($url, $local)->assertOk()->assertJsonPath('data.version', 3);
        $delete = $this->mutation(3, true);
        $this->putJson($url, $delete)->assertOk()->assertJsonPath('data.version', 4);
        $this->putJson($url, $delete)->assertOk()->assertJsonPath('data.version', 4);
        $this->getJson('/api/v1/courses')->assertJsonCount(0, 'data');
        $feed = $this->getJson('/api/v1/sync/courses?after=0')->assertOk()
            ->assertJsonCount(1, 'data')->assertJsonPath('cursor', 4)->assertJsonPath('has_more', false);
        $this->assertNotNull($feed->json('data.0.deleted_at'));
        $this->putJson($url, $this->mutation(3))->assertConflict()->assertJsonPath('conflict.version', 4);
        $this->putJson($url, $this->mutation(4))->assertOk()
            ->assertJsonPath('data.deleted_at', null)->assertJsonPath('data.version', 5);
        $this->getJson('/api/v1/sync/courses?after=4')->assertOk()->assertJsonPath('cursor', 5);
        // Old receipts replay the old acknowledgement, not a second mutation.
        $this->putJson($url, $create)->assertExactJson($first);
        $this->getJson("/api/v1/courses/$id")->assertJsonPath('data.version', 5);
    }

    public function test_feed_is_ordered_paginated_and_includes_normal_api_changes(): void
    {
        $this->login();
        for ($i = 0; $i < 102; $i++) {
            $this->postJson('/api/v1/courses', ['name' => "Course $i"])->assertCreated();
        }
        $first = $this->getJson('/api/v1/sync/courses')->assertOk()
            ->assertJsonCount(100, 'data')->assertJsonPath('cursor', 100)->assertJsonPath('has_more', true);
        $second = $this->getJson('/api/v1/sync/courses?after=100')->assertOk()
            ->assertJsonCount(2, 'data')->assertJsonPath('cursor', 102);
        $id = $first->json('data.0.id');
        $this->patchJson("/api/v1/courses/$id", ['name' => 'Changed'])->assertUnprocessable();
        $this->patchJson("/api/v1/courses/$id", ['name' => 'Changed', 'version' => 1])->assertOk();
        $this->deleteJson("/api/v1/courses/$id", ['version' => 1])->assertConflict();
        $this->deleteJson("/api/v1/courses/$id", ['version' => 2])->assertNoContent();
        $this->getJson('/api/v1/sync/courses?after=102')->assertOk()
            ->assertJsonCount(1, 'data')->assertJsonPath('cursor', 104)->assertJsonPath('data.0.version', 3);
        $this->getJson('/api/v1/sync/courses?after=104')->assertJsonCount(0, 'data')
            ->assertJsonPath('cursor', 104)->assertJsonPath('has_more', false);
        $this->assertNotEquals($id, $second->json('data.0.id'));
    }

    public function test_accounts_and_mutation_receipts_are_isolated(): void
    {
        $owner = $this->login();
        $id = Str::uuid();
        $body = $this->mutation();
        $this->putJson("/api/v1/sync/courses/$id", $body)->assertOk();
        $this->login();
        $this->getJson('/api/v1/sync/courses')->assertJsonCount(0, 'data')->assertJsonPath('cursor', 0);
        $this->putJson("/api/v1/sync/courses/$id", $body)->assertNotFound();
        $otherId = Str::uuid();
        $this->putJson("/api/v1/sync/courses/$otherId", $body)->assertOk();
        $this->login($owner);
        $this->putJson("/api/v1/sync/courses/$otherId", $this->mutation(1))->assertNotFound();
        $this->getJson('/api/v1/sync/courses')->assertJsonCount(1, 'data');
    }

    public function test_invalid_mutations_are_rejected_without_writes(): void
    {
        $this->getJson('/api/v1/sync/courses')->assertUnauthorized();
        $this->login();
        $this->getJson('/api/v1/sync/courses?after=-1')->assertUnprocessable();
        $id = Str::uuid();
        $body = $this->mutation();
        $body['name'] = '';
        $this->putJson("/api/v1/sync/courses/$id", $body)->assertUnprocessable();
        $this->putJson("/api/v1/sync/courses/$id", $this->mutation(1))->assertConflict()
            ->assertJsonPath('conflict', null);
        $this->putJson("/api/v1/sync/courses/$id", $this->mutation(0, true))->assertUnprocessable();
        $this->assertDatabaseCount('courses', 0);
        $this->assertDatabaseCount('course_mutations', 0);
    }

    public function test_full_unicode_page_fits_the_mobile_response_limit(): void
    {
        $this->login();
        $name = str_repeat("\u{1F600}", 255);
        for ($i = 0; $i < 100; $i++) {
            $this->postJson('/api/v1/courses', [
                'name' => $name, 'code' => str_repeat("\u{1F600}", 100),
                'professor' => $name, 'location' => $name,
            ])->assertCreated();
        }
        $page = $this->getJson('/api/v1/sync/courses')->assertOk()->assertJsonCount(100, 'data');
        $this->assertLessThan(2097152, strlen($page->getContent()));
        $this->assertSame($name, $page->json('data.0.name'));
    }

    public function test_migration_preserves_existing_ids_and_rollback_does_not_restore_deletions(): void
    {
        $this->login();
        $live = $this->postJson('/api/v1/courses', ['name' => 'Keep me'])->assertCreated()->json('data.id');
        $deleted = $this->postJson('/api/v1/courses', ['name' => 'Deleted'])->assertCreated()->json('data.id');
        $this->deleteJson("/api/v1/courses/$deleted", ['version' => 1])->assertNoContent();
        $migration = require database_path('migrations/2026_11_09_200000_add_course_synchronization.php');
        $migration->down();
        $this->assertDatabaseHas('courses', ['id' => $live]);
        $this->assertDatabaseMissing('courses', ['id' => $deleted]);
        $migration->up();
        $this->getJson('/api/v1/sync/courses')->assertOk()->assertJsonCount(1, 'data')
            ->assertJsonPath('data.0.id', $live)->assertJsonPath('data.0.version', 1)
            ->assertJsonPath('cursor', 1);
        $this->assertSame(1, (int) DB::table('users')->value('sync_revision'));
    }
}
