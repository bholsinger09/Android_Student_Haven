<?php

namespace App\Http\Middleware;

use App\Models\User;
use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Symfony\Component\HttpFoundation\Response;

class SerializeStudyWrites
{
    public function handle(Request $request, Closure $next): Response
    {
        if ($request->isMethodSafe()) {
            return $next($request);
        }

        // Child inserts and class tombstones must not race: soft deletion
        // cannot rely on the database's physical-delete cascade.
        return DB::transaction(function () use ($request, $next) {
            User::whereKey($request->user()->id)->lockForUpdate()->firstOrFail();

            return $next($request);
        });
    }
}
