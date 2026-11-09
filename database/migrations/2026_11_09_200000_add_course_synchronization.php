<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('users', fn (Blueprint $table) => $table->bigInteger('sync_revision')->default(0));
        Schema::table('courses', function (Blueprint $table) {
            $table->bigInteger('version')->default(1);
            $table->bigInteger('sync_revision')->default(0);
            $table->softDeletesTz();
            $table->index(['user_id', 'sync_revision']);
        });
        DB::statement('WITH numbered AS (
            SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY id) AS revision FROM courses
        ) UPDATE courses SET sync_revision = numbered.revision FROM numbered WHERE courses.id = numbered.id');
        DB::statement('UPDATE users SET sync_revision =
            COALESCE((SELECT max(sync_revision) FROM courses WHERE courses.user_id = users.id), 0)');
        Schema::create('course_mutations', function (Blueprint $table) {
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->uuid('mutation_id');
            $table->string('request_hash', 64);
            $table->jsonb('response');
            $table->primary(['user_id', 'mutation_id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('course_mutations');
        // Deletions must stay deleted if this migration is rolled back.
        DB::table('courses')->whereNotNull('deleted_at')->delete();
        Schema::table('courses', function (Blueprint $table) {
            $table->dropIndex(['user_id', 'sync_revision']);
            $table->dropColumn(['version', 'sync_revision', 'deleted_at']);
        });
        Schema::table('users', fn (Blueprint $table) => $table->dropColumn('sync_revision'));
    }
};
