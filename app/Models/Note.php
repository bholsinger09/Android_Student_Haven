<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\SoftDeletes;

#[Fillable(['title', 'content', 'tags'])]
class Note extends Model
{
    use HasUuids, SoftDeletes;

    protected function casts(): array
    {
        return ['tags' => 'array', 'version' => 'integer', 'sync_revision' => 'integer'];
    }
}
