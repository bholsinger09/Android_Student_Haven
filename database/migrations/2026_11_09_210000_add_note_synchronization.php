<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::table('users', fn (Blueprint $table) => $table->bigInteger('note_revision')->default(0));
        Schema::table('notes', function (Blueprint $table) {
            $table->bigInteger('version')->default(1);
            $table->bigInteger('sync_revision')->default(0);
            $table->softDeletesTz();
            $table->index(['course_id', 'sync_revision']);
        });
        DB::statement('WITH numbered AS (
            SELECT notes.id, row_number() OVER (PARTITION BY courses.user_id ORDER BY notes.id) AS revision
            FROM notes JOIN courses ON notes.course_id = courses.id
        ) UPDATE notes SET sync_revision = numbered.revision FROM numbered WHERE notes.id = numbered.id');
        DB::statement('UPDATE users SET note_revision = COALESCE((
            SELECT max(notes.sync_revision) FROM notes JOIN courses ON notes.course_id = courses.id
            WHERE courses.user_id = users.id), 0)');
        Schema::create('note_mutations', function (Blueprint $table) {
            $table->foreignId('user_id')->constrained()->cascadeOnDelete();
            $table->uuid('mutation_id');
            $table->string('request_hash', 64);
            $table->jsonb('response');
            $table->primary(['user_id', 'mutation_id']);
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('note_mutations');
        DB::table('notes')->whereNotNull('deleted_at')->delete();
        Schema::table('notes', function (Blueprint $table) {
            $table->dropIndex(['course_id', 'sync_revision']);
            $table->dropColumn(['version', 'sync_revision', 'deleted_at']);
        });
        Schema::table('users', fn (Blueprint $table) => $table->dropColumn('note_revision'));
    }
};
