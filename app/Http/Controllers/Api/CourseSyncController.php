<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Course;
use App\Models\User;
use App\Services\CourseWriter;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class CourseSyncController extends Controller
{
    public function index(Request $request): JsonResponse
    {
        $data = $request->validate(['after' => ['sometimes', 'integer', 'min:0', 'max:9007199254740991']]);
        $after = (int) ($data['after'] ?? 0);
        $rows = Course::withTrashed()->where('user_id', $request->user()->id)
            ->where('sync_revision', '>', $after)->orderBy('sync_revision')->limit(101)->get();
        $page = $rows->take(100)->values();

        return response()->json([
            'data' => $page,
            'cursor' => $page->last()?->sync_revision ?? $after,
            'has_more' => $rows->count() > 100,
        ]);
    }

    public function update(Request $request, string $course, CourseWriter $writer): JsonResponse
    {
        $data = $request->validate([
            'mutation_id' => ['required', 'uuid'],
            'base_version' => ['required', 'integer', 'min:0', 'max:9007199254740991'],
            'deleted' => ['required', 'boolean'],
            'name' => ['required_unless:deleted,true', 'nullable', 'string', 'max:255'],
            'code' => ['sometimes', 'nullable', 'string', 'max:100'],
            'professor' => ['sometimes', 'nullable', 'string', 'max:255'],
            'location' => ['sometimes', 'nullable', 'string', 'max:255'],
        ]);
        $fields = [];
        if (! $data['deleted']) {
            foreach (['name', 'code', 'professor', 'location'] as $field) {
                $fields[$field] = $data[$field] ?? '';
            }
        }
        $hash = hash('sha256', json_encode([$course, (int) $data['base_version'],
            (bool) $data['deleted'], $fields], JSON_THROW_ON_ERROR));

        return DB::transaction(function () use ($request, $data, $hash, $course, $fields, $writer) {
            $owner = User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail();
            $receipt = DB::table('course_mutations')->where('user_id', $owner->id)
                ->where('mutation_id', $data['mutation_id'])->first();
            if ($receipt) {
                if (! hash_equals($receipt->request_hash, $hash)) {
                    return response()->json(['message' => 'Mutation ID was already used for different content.'], 409);
                }

                return response()->json(json_decode($receipt->response, true, flags: JSON_THROW_ON_ERROR));
            }
            $model = Course::withTrashed()->find($course);
            abort_if($model && $model->user_id !== $owner->id, 404);
            if ((int) $data['base_version'] !== ($model?->version ?? 0)) {
                return response()->json(['message' => 'This class changed on another device.',
                    'conflict' => $model], 409);
            }
            if (! $model && $data['deleted']) {
                return response()->json(['message' => 'Cannot delete a class that was never created.'], 422);
            }
            $model ??= new Course;
            $model->id = $course;
            $saved = $writer->write($owner, $model, $fields, (bool) $data['deleted']);
            $response = ['data' => $saved->toArray()];
            DB::table('course_mutations')->insert([
                'user_id' => $owner->id, 'mutation_id' => $data['mutation_id'],
                'request_hash' => $hash, 'response' => json_encode($response, JSON_THROW_ON_ERROR),
            ]);

            return response()->json($response);
        });
    }
}
