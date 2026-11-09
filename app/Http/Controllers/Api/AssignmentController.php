<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;

class AssignmentController extends Controller
{
    public function index(Request $request, string $course): JsonResponse
    {
        return response()->json($request->user()->courses()->findOrFail($course)
            ->assignments()->orderBy('id')->paginate(50));
    }

    public function store(Request $request, string $course): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course);
        $assignment = $model->assignments()->create($this->validated($request));

        return response()->json(['data' => $assignment->fresh()], 201);
    }

    public function show(Request $request, string $course, string $assignment): JsonResponse
    {
        return response()->json(['data' => $request->user()->courses()->findOrFail($course)
            ->assignments()->findOrFail($assignment)]);
    }

    public function update(Request $request, string $course, string $assignment): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course)->assignments()->findOrFail($assignment);
        $model->update($this->validated($request, true));

        return response()->json(['data' => $model->fresh()]);
    }

    public function destroy(Request $request, string $course, string $assignment): Response
    {
        $request->user()->courses()->findOrFail($course)->assignments()->findOrFail($assignment)->delete();

        return response()->noContent();
    }

    private function validated(Request $request, bool $updating = false): array
    {
        return $request->validate([
            'name' => [$updating ? 'sometimes' : 'required', 'required', 'string', 'max:255'],
            'due_date' => ['sometimes', 'nullable', 'date_format:Y-m-d'],
            'score' => ['sometimes', 'nullable', 'numeric', 'min:0', 'max:1000000000'],
            'max_score' => ['sometimes', 'required', 'numeric', 'gt:0', 'max:1000000000'],
            'weight' => ['sometimes', 'required', 'numeric', 'gt:0', 'max:1000000000'],
        ]);
    }
}
