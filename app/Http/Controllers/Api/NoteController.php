<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;

class NoteController extends Controller
{
    public function index(Request $request, string $course): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course);

        return response()->json($model->notes()->orderBy('id')->paginate(50));
    }

    public function store(Request $request, string $course): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course);
        $note = $model->notes()->create($this->validated($request));

        return response()->json(['data' => $note->fresh()], 201);
    }

    public function show(Request $request, string $course, string $note): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course);

        return response()->json(['data' => $model->notes()->findOrFail($note)]);
    }

    public function update(Request $request, string $course, string $note): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course)->notes()->findOrFail($note);
        $model->update($this->validated($request, true));

        return response()->json(['data' => $model->fresh()]);
    }

    public function destroy(Request $request, string $course, string $note): Response
    {
        $request->user()->courses()->findOrFail($course)->notes()->findOrFail($note)->delete();

        return response()->noContent();
    }

    private function validated(Request $request, bool $updating = false): array
    {
        $data = $request->validate([
            'title' => [$updating ? 'sometimes' : 'required', 'required', 'string', 'max:255'],
            'content' => ['sometimes', 'nullable', 'string', 'max:200000'],
            'tags' => ['sometimes', 'array', 'list', 'max:50'],
            'tags.*' => ['required', 'string', 'max:100', 'distinct'],
        ]);
        if (array_key_exists('content', $data)) {
            $data['content'] ??= '';
        }

        return $data;
    }
}
