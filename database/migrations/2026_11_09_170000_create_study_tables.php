<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('users', function (Blueprint $table) {
            $table->string('college', 255)->nullable();
        });

        Schema::create('courses', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->string('name');
            $table->string('code', 100)->default('');
            $table->string('professor')->default('');
            $table->string('location')->default('');
            $table->timestampsTz();
        });

        Schema::create('notes', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->foreignUuid('course_id')->constrained()->cascadeOnDelete();
            $table->string('title');
            $table->text('content')->default('');
            $table->jsonb('tags')->default('[]');
            $table->timestampsTz();
        });

        Schema::create('flashcards', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->foreignUuid('course_id')->constrained()->cascadeOnDelete();
            $table->foreignUuid('source_note_id')->nullable()->constrained('notes')->nullOnDelete();
            $table->text('front');
            $table->text('back');
            $table->integer('repetitions')->default(0);
            $table->double('ease_factor')->default(2.5);
            $table->integer('interval_days')->default(0);
            $table->timestampTz('next_review_at');
            $table->timestampTz('last_reviewed_at')->nullable();
            $table->timestampsTz();
            $table->index(['course_id', 'next_review_at']);
        });

        Schema::create('assignments', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->foreignUuid('course_id')->constrained()->cascadeOnDelete();
            $table->string('name');
            $table->date('due_date')->nullable();
            $table->double('score')->nullable();
            $table->double('max_score')->default(100);
            $table->double('weight')->default(1);
            $table->timestampsTz();
            $table->index(['course_id', 'due_date']);
        });

        DB::statement('ALTER TABLE flashcards ADD CONSTRAINT flashcards_review_valid CHECK
            (repetitions >= 0 AND interval_days >= 0 AND ease_factor BETWEEN 1.3 AND 100)');
        DB::statement('ALTER TABLE assignments ADD CONSTRAINT assignments_scores_valid CHECK
            ((score IS NULL OR (score >= 0 AND score < \'Infinity\'::float8))
            AND max_score > 0 AND max_score < \'Infinity\'::float8
            AND weight > 0 AND weight < \'Infinity\'::float8)');
    }

    public function down(): void
    {
        Schema::dropIfExists('assignments');
        Schema::dropIfExists('flashcards');
        Schema::dropIfExists('notes');
        Schema::dropIfExists('courses');
        Schema::table('users', fn (Blueprint $table) => $table->dropColumn('college'));
    }
};
