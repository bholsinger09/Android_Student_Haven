<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;

#[Fillable(['name', 'due_date', 'score', 'max_score', 'weight'])]
class Assignment extends Model
{
    use HasUuids;

    protected function casts(): array
    {
        return ['score' => 'float', 'max_score' => 'float', 'weight' => 'float'];
    }
}
