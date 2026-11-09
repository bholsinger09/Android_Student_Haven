<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\User;
use App\Services\SessionToken;
use GuzzleHttp\ClientInterface;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Str;
use Laravel\Socialite\Facades\Socialite;
use RuntimeException;

class SocialAuthController extends Controller
{
    private const APP_CALLBACK = 'studenthaven://oauth/callback';

    public function __construct(private ClientInterface $http) {}

    public function start(Request $request, string $provider): JsonResponse
    {
        $this->provider($provider);
        $data = $request->validate(['challenge' => ['required', 'string', 'regex:/^[A-Za-z0-9_-]{43}$/']]);
        foreach (['client_id', 'client_secret', 'redirect'] as $key) {
            if (! is_string(config("services.$provider.$key")) || config("services.$provider.$key") === '') {
                return response()->json(['message' => ucfirst($provider).' sign-in is not configured yet.'], 503);
            }
        }
        $redirect = config("services.$provider.redirect");
        abort_unless(str_starts_with($redirect, 'https://') || (app()->environment('local', 'testing')
            && str_starts_with($redirect, 'http://localhost:')), 503, 'OAuth requires an HTTPS callback.');
        $state = Str::random(64);
        $id = (string) Str::uuid();
        $url = Socialite::driver($provider)->stateless()->with(['state' => $state, 'prompt' => 'select_account'])
            ->redirect()->getTargetUrl();
        DB::table('social_auth_attempts')->insert([
            'id' => $id, 'provider' => $provider, 'state_hash' => hash('sha256', $state),
            'challenge' => $data['challenge'], 'status' => 'pending', 'expires_at' => now()->addMinutes(10),
        ]);

        return response()->json(['attempt_id' => $id, 'authorization_url' => $url])->header('Cache-Control', 'no-store');
    }

    public function callback(Request $request, string $provider): RedirectResponse
    {
        $this->provider($provider);
        $data = $request->validate(['state' => ['required', 'string', 'size:64']]);
        $attempt = DB::transaction(function () use ($provider, $data) {
            $attempt = DB::table('social_auth_attempts')->where('provider', $provider)
                ->where('state_hash', hash('sha256', $data['state']))->lockForUpdate()->first();
            abort_unless($attempt && $attempt->status === 'pending' && now()->lt($attempt->expires_at), 400, 'Expired or invalid sign-in state.');
            DB::table('social_auth_attempts')->where('id', $attempt->id)->update(['status' => 'processing']);

            return $attempt;
        });
        if ($request->has('error')) {
            DB::table('social_auth_attempts')->where('id', $attempt->id)->update(['status' => 'cancelled']);
        } else {
            try {
                // Provider exceptions are isolated here; never log codes, tokens, or provider response bodies.
                $driver = Socialite::driver($provider)->stateless();
                $driver->setHttpClient($this->http);
                if (! is_string($request->input('code')) || $request->input('code') === '') {
                    throw new RuntimeException('Missing authorization code');
                }
                $remote = $driver->user();
                $subject = (string) $remote->getId();
                if ($provider === 'microsoft') {
                    $claims = $driver->getClaims();
                    if (! isset($claims->tid, $claims->oid, $claims->aud)
                        || ! is_string($claims->tid) || $claims->tid === ''
                        || ! is_string($claims->oid) || $subject !== $claims->oid
                        || $claims->aud !== config('services.microsoft.client_id')) {
                        throw new RuntimeException('Missing or inconsistent Microsoft identity');
                    }
                    $subject = $claims->tid.':'.$claims->oid;
                }
                if ($subject === '' || strlen($subject) > 255) {
                    throw new RuntimeException('Invalid provider identity');
                }
                $name = mb_substr(trim((string) $remote->getName()), 0, 255);
                $email = mb_strtolower(trim((string) $remote->getEmail()));
                if (mb_strlen($email) > 255) {
                    throw new RuntimeException('Invalid provider email');
                }
            } catch (\Exception $exception) {
                Log::warning('Social sign-in provider rejected authentication', ['provider' => $provider,
                    'exception_type' => $exception::class]);
                DB::table('social_auth_attempts')->where('id', $attempt->id)->update(['status' => 'failed']);

                return $this->appRedirect($attempt->id);
            }
            DB::transaction(function () use ($attempt, $provider, $subject, $name, $email) {
                // No email matching/linking: only the provider's stable, validated identity can select an account.
                $user = User::firstOrCreate(['auth_provider' => $provider, 'provider_subject' => $subject],
                    ['name' => $name !== '' ? $name : ucfirst($provider).' student', 'email' => $email, 'password' => null]);
                DB::table('social_auth_attempts')->where('id', $attempt->id)
                    ->update(['status' => 'authorized', 'user_id' => $user->id]);
            });
        }

        return $this->appRedirect($attempt->id);
    }

    public function exchange(Request $request, SessionToken $tokens): JsonResponse
    {
        $data = $request->validate([
            'attempt_id' => ['required', 'uuid'],
            'verifier' => ['required', 'string', 'regex:/^[A-Za-z0-9_-]{43}$/'],
            'device_name' => ['required', 'string', 'max:100'],
        ]);

        return DB::transaction(function () use ($data, $tokens) {
            $attempt = DB::table('social_auth_attempts')->where('id', $data['attempt_id'])->lockForUpdate()->first();
            $challenge = rtrim(strtr(base64_encode(hash('sha256', $data['verifier'], true)), '+/', '-_'), '=');
            abort_unless($attempt && now()->lt($attempt->expires_at)
                && hash_equals($attempt->challenge, $challenge), 400, 'Invalid or expired social sign-in. Please start again.');
            if ($attempt->status === 'pending' || $attempt->status === 'processing') {
                return response()->json(['code' => 'authorization_pending',
                    'message' => 'Finish signing in in your browser, then try again.'], 409);
            }
            if ($attempt->status !== 'authorized') {
                return response()->json(['message' => $attempt->status === 'cancelled'
                    ? 'Sign-in was cancelled. Please try again.' : 'Social sign-in failed or was already used. Please start again.'], 400);
            }
            $user = User::findOrFail($attempt->user_id);
            DB::table('social_auth_attempts')->where('id', $attempt->id)->update(['status' => 'consumed']);

            return $tokens->response($user, $data['device_name']);
        });
    }

    private function provider(string $provider): void
    {
        abort_unless(in_array($provider, ['google', 'microsoft'], true), 404);
    }

    private function appRedirect(string $attempt): RedirectResponse
    {
        return redirect()->away(self::APP_CALLBACK.'?attempt='.$attempt)
            ->header('Cache-Control', 'no-store')->header('Referrer-Policy', 'no-referrer');
    }
}
