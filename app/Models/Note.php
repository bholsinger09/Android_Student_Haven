<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;

#[Fillable(['title', 'content', 'tags'])]
class Note extends Model
{
    use HasUuids;

    protected function casts(): array
    {
        return ['tags' => 'array'];
    }
}
