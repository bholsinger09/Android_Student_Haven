<?php

namespace App\Services;

use App\Models\Course;
use App\Models\User;

class CourseWriter
{
    // Call inside a transaction after locking the owning user, so feed order
    // follows commit order even when different courses change concurrently.
    public function write(User $owner, Course $course, array $fields, bool $deleted): Course
    {
        $course->fill($fields);
        $course->user_id = $owner->id;
        $course->version = $course->exists ? $course->version + 1 : 1;
        $owner->sync_revision++;
        $owner->save();
        $course->sync_revision = $owner->sync_revision;
        if ($deleted) {
            $course->notes()->delete();
            $course->flashcards()->delete();
            $course->assignments()->delete();
        }
        $course->deleted_at = $deleted ? now() : null;
        $course->save();

        return $course->fresh();
    }
}
