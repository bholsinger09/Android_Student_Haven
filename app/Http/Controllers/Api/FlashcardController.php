<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Carbon\CarbonImmutable;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Validation\Rule;

class FlashcardController extends Controller
{
    public function index(Request $request, string $course): JsonResponse
    {
        return response()->json($request->user()->courses()->findOrFail($course)
            ->flashcards()->orderBy('id')->paginate(50));
    }

    public function store(Request $request, string $course): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course);
        $data = $this->validated($request, $course);
        $data['next_review_at'] ??= now();
        $flashcard = $model->flashcards()->create($data);

        return response()->json(['data' => $flashcard->fresh()], 201);
    }

    public function show(Request $request, string $course, string $flashcard): JsonResponse
    {
        return response()->json(['data' => $request->user()->courses()->findOrFail($course)
            ->flashcards()->findOrFail($flashcard)]);
    }

    public function update(Request $request, string $course, string $flashcard): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course)->flashcards()->findOrFail($flashcard);
        $model->update($this->validated($request, $course, true));

        return response()->json(['data' => $model->fresh()]);
    }

    public function destroy(Request $request, string $course, string $flashcard): Response
    {
        $request->user()->courses()->findOrFail($course)->flashcards()->findOrFail($flashcard)->delete();

        return response()->noContent();
    }

    private function validated(Request $request, string $course, bool $updating = false): array
    {
        $data = $request->validate([
            'front' => [$updating ? 'sometimes' : 'required', 'required', 'string', 'max:20000'],
            'back' => [$updating ? 'sometimes' : 'required', 'required', 'string', 'max:20000'],
            'source_note_id' => ['sometimes', 'nullable', 'uuid',
                Rule::exists('notes', 'id')->where('course_id', $course)->whereNull('deleted_at')],
            'repetitions' => ['sometimes', 'required', 'integer', 'min:0', 'max:1000000'],
            'ease_factor' => ['sometimes', 'required', 'numeric', 'between:1.3,100'],
            'interval_days' => ['sometimes', 'required', 'integer', 'min:0', 'max:1000000'],
            'next_review_at' => ['sometimes', 'required', 'date_format:Y-m-d\TH:i:sP'],
            'last_reviewed_at' => ['sometimes', 'nullable', 'date_format:Y-m-d\TH:i:sP'],
        ]);
        foreach (['next_review_at', 'last_reviewed_at'] as $field) {
            if (isset($data[$field])) {
                $data[$field] = CarbonImmutable::parse($data[$field])->utc();
            }
        }

        return $data;
    }
}
