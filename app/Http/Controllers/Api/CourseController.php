<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;

class CourseController extends Controller
{
    public function index(Request $request): JsonResponse
    {
        return response()->json($request->user()->courses()->orderBy('id')->paginate(50));
    }

    public function store(Request $request): JsonResponse
    {
        $data = $this->validated($request);
        $course = $request->user()->courses()->create($data);

        return response()->json(['data' => $course->fresh()], 201);
    }

    public function show(Request $request, string $course): JsonResponse
    {
        return response()->json(['data' => $request->user()->courses()->findOrFail($course)]);
    }

    public function update(Request $request, string $course): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course);
        $model->update($this->validated($request, true));

        return response()->json(['data' => $model->fresh()]);
    }

    public function destroy(Request $request, string $course): Response
    {
        $request->user()->courses()->findOrFail($course)->delete();

        return response()->noContent();
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
