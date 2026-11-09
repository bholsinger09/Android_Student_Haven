<?php

use App\Http\Controllers\Api\AssignmentController;
use App\Http\Controllers\Api\AuthController;
use App\Http\Controllers\Api\CourseController;
use App\Http\Controllers\Api\FlashcardController;
use App\Http\Controllers\Api\NoteController;
use Illuminate\Support\Facades\Route;

foreach (['course', 'note', 'flashcard', 'assignment'] as $parameter) {
    Route::pattern($parameter, '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}');
}

Route::prefix('v1')->group(function () {
    Route::post('auth/register', [AuthController::class, 'register'])->middleware('throttle:registration');
    Route::post('auth/login', [AuthController::class, 'login'])->middleware('throttle:login');

    Route::middleware(['auth:sanctum', 'throttle:api', 'cache.headers:no_store;private'])->group(function () {
        Route::post('auth/logout', [AuthController::class, 'logout']);
        Route::get('me', [AuthController::class, 'show']);
        Route::patch('me', [AuthController::class, 'update']);
        Route::delete('me', [AuthController::class, 'destroy']);
        Route::apiResource('courses', CourseController::class);
        Route::apiResource('courses.notes', NoteController::class);
        Route::apiResource('courses.flashcards', FlashcardController::class);
        Route::apiResource('courses.assignments', AssignmentController::class);
    });
});
