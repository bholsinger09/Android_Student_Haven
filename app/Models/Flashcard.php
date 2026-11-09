<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;

#[Fillable(['front', 'back', 'source_note_id', 'repetitions', 'ease_factor',
    'interval_days', 'next_review_at', 'last_reviewed_at'])]
class Flashcard extends Model
{
    use HasUuids;

    protected function casts(): array
    {
        return [
            'repetitions' => 'integer',
            'ease_factor' => 'float',
            'interval_days' => 'integer',
            'next_review_at' => 'immutable_datetime',
            'last_reviewed_at' => 'immutable_datetime',
        ];
    }
}
