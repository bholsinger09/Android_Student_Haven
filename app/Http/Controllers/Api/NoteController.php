<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\User;
use App\Services\NoteWriter;
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

    public function store(Request $request, string $course, NoteWriter $writer): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course);
        $note = $model->notes()->make();
        $owner = User::findOrFail($request->user()->id);
        $note = $writer->write($owner, $note, $writer->validated($request), false);

        return response()->json(['data' => $note->fresh()], 201);
    }

    public function show(Request $request, string $course, string $note): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course);

        return response()->json(['data' => $model->notes()->findOrFail($note)]);
    }

    public function update(Request $request, string $course, string $note, NoteWriter $writer): JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course)->notes()->findOrFail($note);
        $version = $request->validate(['version' => ['required', 'integer', 'min:1']])['version'];
        if ($model->version != $version) {
            return response()->json(['message' => 'This note changed on another device.',
                'conflict' => $writer->payload($model, $request->user())], 409);
        }
        $model = $writer->write(User::findOrFail($request->user()->id), $model,
            $writer->validated($request, true), false);

        return response()->json(['data' => $model->fresh()]);
    }

    public function destroy(Request $request, string $course, string $note, NoteWriter $writer): Response|JsonResponse
    {
        $model = $request->user()->courses()->findOrFail($course)->notes()->findOrFail($note);
        $version = $request->validate(['version' => ['required', 'integer', 'min:1']])['version'];
        if ($model->version != $version) {
            return response()->json(['message' => 'This note changed on another device.',
                'conflict' => $writer->payload($model, $request->user())], 409);
        }
        $writer->write(User::findOrFail($request->user()->id), $model, [], true);

        return response()->noContent();
    }
}
