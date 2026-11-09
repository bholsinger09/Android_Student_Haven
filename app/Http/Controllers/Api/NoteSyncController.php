<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Course;
use App\Models\Note;
use App\Models\User;
use App\Services\NoteWriter;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class NoteSyncController extends Controller
{
    public function index(Request $request, NoteWriter $writer): JsonResponse
    {
        $input = $request->validate(['after' => ['sometimes', 'integer', 'min:0', 'max:9007199254740991']]);
        $after = (int) ($input['after'] ?? 0);
        // One large note per page bounds worst-case escaped Unicode JSON size.
        $rows = Note::withTrashed()->whereIn('course_id', Course::withTrashed()
            ->where('user_id', $request->user()->id)->select('id'))
            ->where('sync_revision', '>', $after)->orderBy('sync_revision')->limit(2)->get();
        $page = $rows->take(1);

        return response()->json([
            'data' => $page->map(fn (Note $note) => $writer->payload($note, $request->user()))->values(),
            'cursor' => $page->last()?->sync_revision ?? $after,
            'has_more' => $rows->count() > 1,
        ]);
    }

    public function update(Request $request, string $course, string $note, NoteWriter $writer): JsonResponse
    {
        $data = $request->validate([
            'mutation_id' => ['required', 'uuid'],
            'base_version' => ['required', 'integer', 'min:0', 'max:9007199254740991'],
            'deleted' => ['required', 'boolean'],
        ]);
        $fields = $writer->validated($request, false, (bool) $data['deleted']);
        $hash = hash('sha256', json_encode([$course, $note, (int) $data['base_version'],
            (bool) $data['deleted'], $fields], JSON_THROW_ON_ERROR));

        return DB::transaction(function () use ($request, $data, $hash, $course, $note, $fields, $writer) {
            $owner = User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail();
            $parent = Course::withTrashed()->where('user_id', $owner->id)->findOrFail($course);
            $receipt = DB::table('note_mutations')->where('user_id', $owner->id)
                ->where('mutation_id', $data['mutation_id'])->first();
            if ($receipt) {
                if (! hash_equals($receipt->request_hash, $hash)) {
                    return response()->json(['message' => 'Mutation ID was already used for different content.'], 409);
                }

                return response()->json(json_decode($receipt->response, true, flags: JSON_THROW_ON_ERROR));
            }
            if ($parent->trashed()) {
                return response()->json(['message' => 'The parent class was deleted. Keep a local recovery draft.',
                    'code' => 'parent_deleted', 'parent' => $parent], 409);
            }
            $model = Note::withTrashed()->find($note);
            abort_if($model && $model->course_id !== $parent->id, 404);
            if ((int) $data['base_version'] !== ($model?->version ?? 0)) {
                return response()->json(['message' => 'This note changed on another device.',
                    'conflict' => $model ? $writer->payload($model, $owner) : null], 409);
            }
            if (! $model && $data['deleted']) {
                return response()->json(['message' => 'Cannot delete a note that was never created.'], 422);
            }
            $model ??= new Note;
            $model->id = $note;
            $model->course_id = $parent->id;
            $saved = $writer->write($owner, $model, $fields, (bool) $data['deleted']);
            $response = ['data' => $writer->payload($saved, $owner)];
            DB::table('note_mutations')->insert([
                'user_id' => $owner->id, 'mutation_id' => $data['mutation_id'],
                'request_hash' => $hash, 'response' => json_encode($response, JSON_THROW_ON_ERROR),
            ]);

            return response()->json($response);
        });
    }
}
