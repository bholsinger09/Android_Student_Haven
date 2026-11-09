<?php

namespace Tests\Feature;

use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Str;
use Tests\TestCase;

class NoteSyncTest extends TestCase
{
    use RefreshDatabase;

    private function login(?User $user = null): User
    {
        $user ??= User::factory()->create();
        $this->withToken($user->createToken('test')->plainTextToken);
        app('auth')->forgetGuards();

        return $user;
    }

    private function course(): string
    {
        return $this->postJson('/api/v1/courses', ['name' => 'Biology'])->assertCreated()->json('data.id');
    }

    private function mutation(int $version = 0, bool $deleted = false): array
    {
        return ['mutation_id' => (string) Str::uuid(), 'base_version' => $version,
            'deleted' => $deleted, 'title' => 'Cells', 'content' => 'Local content', 'tags' => ['biology', 'exam']];
    }

    public function test_retry_conflict_choices_and_deletion_feed(): void
    {
        $this->login();
        $course = $this->course();
        $id = Str::uuid();
        $url = "/api/v1/sync/courses/$course/notes/$id";
        $create = $this->mutation();
        $first = $this->putJson($url, $create)->assertOk()->assertJsonPath('data.version', 1)->json();
        $this->putJson($url, $create)->assertExactJson($first);
        $this->putJson($url, array_merge($create, ['title' => 'ID reuse']))->assertConflict();
        $remote = $this->mutation(1);
        $remote['content'] = 'Remote version';
        $this->putJson($url, $remote)->assertOk()->assertJsonPath('data.version', 2);
        $local = $this->mutation(1);
        $this->putJson($url, $local)->assertConflict()->assertJsonPath('conflict.content', 'Remote version');
        $local['mutation_id'] = (string) Str::uuid();
        $local['base_version'] = 2;
        $this->putJson($url, $local)->assertOk()->assertJsonPath('data.version', 3)
            ->assertJsonPath('data.tags', ['biology', 'exam']);
        $delete = $this->mutation(3, true);
        $this->putJson($url, $delete)->assertOk()->assertJsonPath('data.version', 4);
        $this->putJson($url, $delete)->assertJsonPath('data.version', 4);
        $feed = $this->getJson('/api/v1/sync/notes')->assertOk()->assertJsonCount(1, 'data')
            ->assertJsonPath('cursor', 4)->assertJsonPath('has_more', false);
        $this->assertNotNull($feed->json('data.0.deleted_at'));
        $this->getJson("/api/v1/courses/$course/notes")->assertJsonCount(0, 'data');
        $this->putJson($url, $create)->assertExactJson($first);
        $this->assertDatabaseCount('note_mutations', 4);
    }

    public function test_parent_deletion_blocks_pending_edits_and_emits_note_tombstones(): void
    {
        $this->login();
        $course = $this->course();
        $id = Str::uuid();
        $url = "/api/v1/sync/courses/$course/notes/$id";
        $create = $this->mutation();
        $this->putJson($url, $create)->assertOk();
        $this->deleteJson("/api/v1/courses/$course", ['version' => 1])->assertNoContent();
        $this->putJson($url, $this->mutation(1))->assertConflict()
            ->assertJsonPath('code', 'parent_deleted')->assertJsonPath('parent.id', $course);
        $this->putJson("/api/v1/sync/courses/$course/notes/".Str::uuid(), $this->mutation())
            ->assertConflict()->assertJsonPath('code', 'parent_deleted');
        $feed = $this->getJson('/api/v1/sync/notes?after=1')->assertOk()
            ->assertJsonPath('data.0.version', 2)->assertJsonPath('cursor', 2);
        $this->assertNotNull($feed->json('data.0.deleted_at'));
        // A lost pre-deletion acknowledgement still replays without resurrecting anything.
        $this->putJson($url, $create)->assertOk()->assertJsonPath('data.version', 1);
        $this->assertSoftDeleted('notes', ['id' => $id]);
        $this->assertSoftDeleted('courses', ['id' => $course]);
        $this->assertDatabaseCount('note_mutations', 1);
    }

    public function test_regular_api_changes_require_versions_and_enter_paginated_feed(): void
    {
        $this->login();
        $course = $this->course();
        $base = "/api/v1/courses/$course/notes";
        $a = $this->postJson($base, ['title' => 'A'])->assertCreated()->json('data.id');
        $b = $this->postJson($base, ['title' => 'B'])->assertCreated()->json('data.id');
        $this->getJson('/api/v1/sync/notes')->assertJsonCount(1, 'data')
            ->assertJsonPath('data.0.id', $a)->assertJsonPath('cursor', 1)->assertJsonPath('has_more', true);
        $this->getJson('/api/v1/sync/notes?after=1')->assertJsonPath('data.0.id', $b)
            ->assertJsonPath('cursor', 2)->assertJsonPath('has_more', false);
        $this->patchJson("$base/$a", ['content' => 'edit'])->assertUnprocessable();
        $this->patchJson("$base/$a", ['version' => 1, 'content' => 'edit'])->assertOk();
        $this->deleteJson("$base/$a", ['version' => 1])->assertConflict();
        $this->deleteJson("$base/$a", ['version' => 2])->assertNoContent();
        $this->getJson('/api/v1/sync/notes?after=2')->assertJsonPath('data.0.version', 3)
            ->assertJsonPath('cursor', 4);
        $this->getJson('/api/v1/sync/notes?after=4')->assertJsonCount(0, 'data')->assertJsonPath('cursor', 4);
    }

    public function test_account_and_parent_boundaries_and_validation(): void
    {
        $owner = $this->login();
        $course = $this->course();
        $otherCourse = $this->course();
        $id = Str::uuid();
        $url = "/api/v1/sync/courses/$course/notes/$id";
        $this->putJson($url, $this->mutation())->assertOk();
        $this->putJson("/api/v1/sync/courses/$otherCourse/notes/$id", $this->mutation())->assertNotFound();
        $this->login();
        $this->getJson('/api/v1/sync/notes')->assertJsonCount(0, 'data');
        $this->putJson($url, $this->mutation(1))->assertNotFound();
        $this->login($owner);
        $invalid = $this->mutation(1);
        $invalid['tags'] = ['duplicate', 'duplicate'];
        $this->putJson($url, $invalid)->assertUnprocessable();
        $invalid['tags'] = ['topic' => 'not a list'];
        $this->putJson($url, $invalid)->assertUnprocessable();
        $invalid['tags'] = [];
        $invalid['content'] = str_repeat('x', 200001);
        $this->putJson($url, $invalid)->assertUnprocessable();
        $this->getJson('/api/v1/sync/notes?after=-1')->assertUnprocessable();
        $this->assertDatabaseCount('note_mutations', 1);
    }

    public function test_maximum_unicode_note_fits_mobile_response_limit(): void
    {
        $this->login();
        $course = $this->course();
        $data = $this->mutation();
        $data['title'] = str_repeat("\u{1F600}", 255);
        $data['tags'] = array_map(fn (int $i) => str_repeat("\u{1F600}", 98).sprintf('%02d', $i), range(0, 49));
        $data['content'] = str_repeat("\u{1F600}", 200000);
        $this->putJson("/api/v1/sync/courses/$course/notes/".Str::uuid(), $data)->assertOk();
        $response = $this->getJson('/api/v1/sync/notes')->assertOk()->assertJsonCount(1, 'data');
        $this->assertSame($data['content'], $response->json('data.0.content'));
        $this->assertSame($data['title'], $response->json('data.0.title'));
        $this->assertSame($data['tags'], $response->json('data.0.tags'));
        $this->assertLessThan(4194304, strlen($response->getContent()));
    }

    public function test_parent_restore_does_not_restore_notes_or_accept_deleted_source_references(): void
    {
        $this->login();
        $course = $this->course();
        $note = $this->postJson("/api/v1/courses/$course/notes", ['title' => 'Original'])->assertCreated()->json('data.id');
        $this->deleteJson("/api/v1/courses/$course", ['version' => 1])->assertNoContent();
        $restore = ['mutation_id' => (string) Str::uuid(), 'base_version' => 2,
            'deleted' => false, 'name' => 'Restored class'];
        $this->putJson("/api/v1/sync/courses/$course", $restore)->assertOk();
        $this->getJson("/api/v1/courses/$course/notes")->assertJsonCount(0, 'data');
        $this->postJson("/api/v1/courses/$course/flashcards", [
            'front' => 'Question', 'back' => 'Answer', 'source_note_id' => $note,
        ])->assertUnprocessable();
        $this->assertSoftDeleted('notes', ['id' => $note]);
    }

    public function test_note_migration_preserves_live_ids_and_never_resurrects_tombstones(): void
    {
        $owner = $this->login();
        $course = $this->course();
        $base = "/api/v1/courses/$course/notes";
        $live = $this->postJson($base, ['title' => 'Keep', 'content' => 'Existing content', 'tags' => ['topic']])
            ->assertCreated()->json('data.id');
        $deleted = $this->postJson($base, ['title' => 'Deleted'])->assertCreated()->json('data.id');
        $this->deleteJson("$base/$deleted", ['version' => 1])->assertNoContent();
        $migration = require database_path('migrations/2026_11_09_210000_add_note_synchronization.php');
        $migration->down();
        $this->assertDatabaseHas('notes', ['id' => $live]);
        $this->assertDatabaseMissing('notes', ['id' => $deleted]);
        $migration->up();
        $this->getJson('/api/v1/sync/notes')->assertJsonCount(1, 'data')->assertJsonPath('data.0.id', $live)
            ->assertJsonPath('data.0.content', 'Existing content')->assertJsonPath('data.0.tags', ['topic'])
            ->assertJsonPath('data.0.version', 1)->assertJsonPath('cursor', 1);
        $this->assertSame(1, $owner->fresh()->note_revision);
    }

    public function test_note_content_preserves_indentation_and_trailing_newlines(): void
    {
        $this->login();
        $course = $this->course();
        $content = "\n    indented code\n\n";
        $data = $this->mutation();
        $data['content'] = $content;
        $this->putJson("/api/v1/sync/courses/$course/notes/".Str::uuid(), $data)->assertOk()
            ->assertJsonPath('data.content', $content);
        $this->postJson("/api/v1/courses/$course/notes", ['title' => 'Code', 'content' => $content])
            ->assertCreated()->assertJsonPath('data.content', $content);
    }
}
