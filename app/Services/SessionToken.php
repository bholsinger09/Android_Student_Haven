<?php

namespace App\Services;

use App\Models\User;
use Illuminate\Http\JsonResponse;

class SessionToken
{
    public function response(User $user, string $deviceName, int $status = 200): JsonResponse
    {
        $expiresAt = now()->addDays(30);
        $token = $user->createToken($deviceName, ['*'], $expiresAt);

        return response()->json([
            'data' => $user,
            'token' => $token->plainTextToken,
            'token_type' => 'Bearer',
            'expires_at' => $expiresAt->toISOString(),
        ], $status)->header('Cache-Control', 'no-store');
    }
}
