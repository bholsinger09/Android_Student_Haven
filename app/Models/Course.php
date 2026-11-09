<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Attributes\Fillable;
use Illuminate\Database\Eloquent\Concerns\HasUuids;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\SoftDeletes;

#[Fillable(['name', 'code', 'professor', 'location'])]
class Course extends Model
{
    use HasUuids, SoftDeletes;

    protected function casts(): array
    {
        return ['version' => 'integer', 'sync_revision' => 'integer'];
    }

    public function notes(): HasMany
    {
        return $this->hasMany(Note::class);
    }

    public function flashcards(): HasMany
    {
        return $this->hasMany(Flashcard::class);
    }

    public function assignments(): HasMany
    {
        return $this->hasMany(Assignment::class);
    }
}
