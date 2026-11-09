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
            $table->string('auth_provider', 20)->nullable();
            $table->string('provider_subject', 255)->nullable();
            $table->string('password')->nullable()->change();
            $table->dropUnique(['email']);
            $table->unique(['auth_provider', 'provider_subject']);
        });
        DB::statement('CREATE UNIQUE INDEX users_password_email_unique ON users (email) WHERE auth_provider IS NULL');
        Schema::create('social_auth_attempts', function (Blueprint $table) {
            $table->uuid('id')->primary();
            $table->string('provider', 20);
            $table->string('state_hash', 64)->unique();
            $table->string('challenge', 43);
            $table->string('status', 20)->default('pending');
            $table->foreignId('user_id')->nullable()->constrained()->cascadeOnDelete();
            $table->timestampTz('expires_at')->index();
        });
    }

    public function down(): void
    {
        if (DB::table('users')->whereNotNull('auth_provider')->exists()) {
            throw new RuntimeException('Remove social accounts through the account-deletion API before rolling back social authentication.');
        }
        Schema::dropIfExists('social_auth_attempts');
        DB::statement('DROP INDEX users_password_email_unique');
        Schema::table('users', function (Blueprint $table) {
            $table->dropUnique(['auth_provider', 'provider_subject']);
            $table->dropColumn(['auth_provider', 'provider_subject']);
            $table->string('password')->nullable(false)->change();
            $table->unique('email');
        });
    }
};
