<?php

namespace App\Services;

use App\Models\Course;
use App\Models\Note;
use App\Models\User;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class NoteWriter
{
    public function validated(Request $request, bool $partial = false, bool $deleted = false): array
    {
        if ($deleted) {
            return [];
        }
        $data = $request->validate([
            'title' => [$partial ? 'sometimes' : 'required', 'required', 'string', 'max:255'],
            'content' => ['sometimes', 'nullable', 'string', 'max:200000'],
            'tags' => ['sometimes', 'array', 'list', 'max:50'],
            'tags.*' => ['required', 'string', 'max:100', 'distinct'],
        ]);
        if (array_key_exists('content', $data)) {
            $data['content'] ??= '';
        }
        if (! $partial) {
            $data += ['content' => '', 'tags' => []];
        }

        return $data;
    }

    // The owning user is locked by SerializeStudyWrites before calling.
    public function write(User $owner, Note $note, array $fields, bool $deleted): Note
    {
        $note->fill($fields);
        $note->version = $note->exists ? $note->version + 1 : 1;
        $owner->note_revision++;
        $owner->save();
        $note->sync_revision = $owner->note_revision;
        if ($deleted) {
            DB::table('flashcards')->where('source_note_id', $note->id)
                ->update(['source_note_id' => null, 'updated_at' => now()]);
        }
        $note->deleted_at = $deleted ? now() : null;
        $note->save();

        return $note->fresh();
    }

    public function payload(Note $note, User $owner): array
    {
        return $note->toArray() + [
            'user_id' => $owner->id,
            'parent_deleted' => Course::withTrashed()->whereKey($note->course_id)->whereNotNull('deleted_at')->exists(),
        ];
    }
}
