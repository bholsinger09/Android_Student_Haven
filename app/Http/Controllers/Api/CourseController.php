<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Course;
use App\Models\User;
use App\Services\CourseWriter;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;

class CourseController extends Controller
{
    public function index(Request $request): JsonResponse
    {
        return response()->json($request->user()->courses()->orderBy('id')->paginate(50));
    }

    public function store(Request $request, CourseWriter $writer): JsonResponse
    {
        $data = $this->validated($request);
        $course = DB::transaction(function () use ($request, $data, $writer) {
            $owner = User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail();

            return $writer->write($owner, new Course, $data, false);
        });

        return response()->json(['data' => $course->fresh()], 201);
    }

    public function show(Request $request, string $course): JsonResponse
    {
        return response()->json(['data' => $request->user()->courses()->findOrFail($course)]);
    }

    public function update(Request $request, string $course, CourseWriter $writer): JsonResponse
    {
        return DB::transaction(function () use ($request, $course, $writer) {
            $owner = User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail();
            $model = $owner->courses()->findOrFail($course);
            $version = $request->validate(['version' => ['required', 'integer', 'min:1']])['version'];
            if ($version != $model->version) {
                return response()->json(['message' => 'This class changed on another device.', 'conflict' => $model], 409);
            }
            $saved = $writer->write($owner, $model, $this->validated($request, true), false);

            return response()->json(['data' => $saved]);
        });
    }

    public function destroy(Request $request, string $course, CourseWriter $writer): Response|JsonResponse
    {
        return DB::transaction(function () use ($request, $course, $writer) {
            $owner = User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail();
            $model = $owner->courses()->findOrFail($course);
            $version = $request->validate(['version' => ['required', 'integer', 'min:1']])['version'];
            if ($version != $model->version) {
                return response()->json(['message' => 'This class changed on another device.', 'conflict' => $model], 409);
            }
            $writer->write($owner, $model, [], true);

            return response()->noContent();
        });
    }

    private function validated(Request $request, bool $updating = false): array
    {
        $data = $request->validate([
            'name' => [$updating ? 'sometimes' : 'required', 'required', 'string', 'max:255'],
            'code' => ['sometimes', 'nullable', 'string', 'max:100'],
            'professor' => ['sometimes', 'nullable', 'string', 'max:255'],
            'location' => ['sometimes', 'nullable', 'string', 'max:255'],
        ]);
        foreach (['code', 'professor', 'location'] as $field) {
            if (array_key_exists($field, $data)) {
                $data[$field] ??= '';
            }
        }

        return $data;
    }
}
