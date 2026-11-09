<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\User;
use Closure;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\Response;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Timebox;
use Illuminate\Validation\Rules\Password;
use Illuminate\Validation\ValidationException;

class AuthController extends Controller
{
    public function register(Request $request): JsonResponse
    {
        if (is_string($request->input('email'))) {
            $request->merge(['email' => mb_strtolower(trim($request->input('email')))]);
        }
        $data = $request->validate([
            'name' => ['required', 'string', 'max:255'],
            'email' => ['required', 'email', 'max:255', 'unique:users,email'],
            'password' => [...$this->passwordRules(), 'confirmed', Password::min(12)],
            'device_name' => ['required', 'string', 'max:100'],
        ]);

        return DB::transaction(function () use ($data) {
            $user = User::create(collect($data)->only(['name', 'email', 'password'])->all());

            return $this->tokenResponse($user, $data['device_name'], 201);
        });
    }

    public function login(Request $request): JsonResponse
    {
        $data = $request->validate([
            'email' => ['required', 'email', 'max:255'],
            'password' => $this->passwordRules(),
            'device_name' => ['required', 'string', 'max:100'],
        ]);

        $user = (new Timebox)->call(function (Timebox $timebox) use ($data) {
            $user = User::where('email', mb_strtolower(trim($data['email'])))->first();
            if (! $user || ! Hash::check($data['password'], $user->password)) {
                throw ValidationException::withMessages(['email' => ['Invalid email or password.']]);
            }
            $timebox->returnEarly();

            return $user;
        }, 200000);

        return $this->tokenResponse($user, $data['device_name']);
    }

    public function logout(Request $request): Response
    {
        $request->user()->currentAccessToken()->delete();

        return response()->noContent();
    }

    public function show(Request $request): JsonResponse
    {
        return response()->json(['data' => $request->user()]);
    }

    public function update(Request $request): JsonResponse
    {
        $data = $request->validate([
            'name' => ['sometimes', 'required', 'string', 'max:255'],
            'college' => ['sometimes', 'nullable', 'string', 'max:255'],
        ]);
        $request->user()->update($data);

        return response()->json(['data' => $request->user()->fresh()]);
    }

    public function destroy(Request $request): Response
    {
        $data = $request->validate(['password' => $this->passwordRules()]);
        if (! Hash::check($data['password'], $request->user()->password)) {
            throw ValidationException::withMessages(['password' => ['Incorrect password.']]);
        }
        DB::transaction(function () use ($request) {
            $request->user()->tokens()->delete();
            $request->user()->delete();
        });

        return response()->noContent();
    }

    private function tokenResponse(User $user, string $deviceName, int $status = 200): JsonResponse
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

    private function passwordRules(): array
    {
        return [
            'bail', 'required', 'string', 'max:72',
            function (string $attribute, string $value, Closure $fail): void {
                if (strlen($value) > 72 || str_contains($value, "\0")) {
                    $fail('The :attribute must be at most 72 bytes and must not contain null characters.');
                }
            },
        ];
    }
}
