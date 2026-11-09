<?php

use App\Http\Controllers\Api\AssignmentController;
use App\Http\Controllers\Api\AuthController;
use App\Http\Controllers\Api\CourseController;
use App\Http\Controllers\Api\CourseSyncController;
use App\Http\Controllers\Api\FlashcardController;
use App\Http\Controllers\Api\NoteController;
use App\Http\Controllers\Api\NoteSyncController;
use App\Http\Controllers\Api\SocialAuthController;
use App\Http\Middleware\SerializeStudyWrites;
use Illuminate\Support\Facades\Route;

foreach (['course', 'note', 'flashcard', 'assignment'] as $parameter) {
    Route::pattern($parameter, '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}');
}

Route::prefix('v1')->group(function () {
    Route::post('auth/register', [AuthController::class, 'register'])->middleware('throttle:registration');
    Route::post('auth/login', [AuthController::class, 'login'])->middleware('throttle:login');
    Route::middleware(['throttle:social-auth', 'cache.headers:no_store;private'])->group(function () {
        Route::post('auth/social/{provider}/start', [SocialAuthController::class, 'start']);
        Route::get('auth/social/{provider}/callback', [SocialAuthController::class, 'callback']);
        Route::post('auth/social/exchange', [SocialAuthController::class, 'exchange']);
    });

    Route::middleware(['auth:sanctum', 'throttle:api', 'cache.headers:no_store;private', SerializeStudyWrites::class])->group(function () {
        Route::post('auth/logout', [AuthController::class, 'logout']);
        Route::get('me', [AuthController::class, 'show']);
        Route::patch('me', [AuthController::class, 'update']);
        Route::delete('me', [AuthController::class, 'destroy']);
        Route::get('sync/courses', [CourseSyncController::class, 'index']);
        Route::put('sync/courses/{course}', [CourseSyncController::class, 'update']);
        Route::get('sync/notes', [NoteSyncController::class, 'index']);
        Route::put('sync/courses/{course}/notes/{note}', [NoteSyncController::class, 'update']);
        Route::apiResource('courses', CourseController::class);
        Route::apiResource('courses.notes', NoteController::class);
        Route::apiResource('courses.flashcards', FlashcardController::class);
        Route::apiResource('courses.assignments', AssignmentController::class);
    });
});
