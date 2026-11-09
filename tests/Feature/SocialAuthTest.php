<?php

namespace Tests\Feature;

use App\Http\Controllers\Api\SocialAuthController;
use App\Models\User;
use Firebase\JWT\JWT;
use GuzzleHttp\Client;
use GuzzleHttp\ClientInterface;
use GuzzleHttp\Handler\MockHandler;
use GuzzleHttp\HandlerStack;
use GuzzleHttp\Middleware;
use GuzzleHttp\Psr7\Response;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;
use Illuminate\Testing\TestResponse;
use Laravel\Socialite\Contracts\Factory;
use Laravel\Socialite\Facades\Socialite;
use Laravel\Socialite\Two\User as SocialUser;
use Mockery;
use PHPUnit\Framework\Attributes\DataProvider;
use Tests\TestCase;

class SocialAuthTest extends TestCase
{
    use RefreshDatabase;

    private string $verifier;

    private string $challenge;

    protected function setUp(): void
    {
        parent::setUp();
        $this->verifier = str_repeat('v', 43);
        $this->challenge = rtrim(strtr(base64_encode(hash('sha256', $this->verifier, true)), '+/', '-_'), '=');
        foreach (['google', 'microsoft'] as $provider) {
            config(["services.$provider.client_id" => 'test-client',
                "services.$provider.client_secret" => 'test-secret',
                "services.$provider.redirect" => "http://localhost:8088/api/v1/auth/social/$provider/callback"]);
        }
    }

    public static function providers(): array
    {
        return [['google'], ['microsoft']];
    }

    public static function providerResponses(): array
    {
        return [['google', 'success'], ['microsoft', 'success'],
            ['microsoft', 'wrong-audience'], ['microsoft', 'bad-signature']];
    }

    #[DataProvider('providerResponses')]
    public function test_real_provider_adapters_exchange_codes_and_validate_microsoft_signatures_and_audience(string $provider, string $scenario): void
    {
        $start = $this->postJson("/api/v1/auth/social/$provider/start", ['challenge' => $this->challenge])->assertOk();
        $id = $start->json('attempt_id');
        parse_str(parse_url($start->json('authorization_url'), PHP_URL_QUERY), $parameters);
        $callback = "/api/v1/auth/social/$provider/callback?state=".$parameters['state'].'&code=test-code';
        $reply = fn (array $body) => new Response(200, ['Content-Type' => 'application/json'], json_encode($body, JSON_THROW_ON_ERROR));
        if ($provider === 'google') {
            $responses = [$reply(['access_token' => 'provider-access-token', 'token_type' => 'Bearer', 'expires_in' => 3600]),
                $reply(['sub' => 'stable-subject', 'name' => 'Provider Student', 'email' => 'student@example.com'])];
        } else {
            $key = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
            openssl_pkey_export($key, $privateKey);
            $rsa = openssl_pkey_get_details($key)['rsa'];
            $encode = fn (string $value) => rtrim(strtr(base64_encode($value), '+/', '-_'), '=');
            $tenant = '9188040d-6c67-4c5b-b112-36a304b66dad';
            $jwt = JWT::encode(['iss' => "https://login.microsoftonline.com/$tenant/v2.0",
                'aud' => $scenario === 'wrong-audience' ? 'prefix-test-client-suffix' : 'test-client',
                'tid' => $tenant, 'oid' => 'stable-subject', 'sub' => 'client-subject',
                'iat' => time(), 'exp' => time() + 300], $privateKey, 'RS256', 'test-key');
            if ($scenario === 'bad-signature') {
                $pieces = explode('.', $jwt);
                $pieces[2] = $encode(str_repeat('x', 256));
                $jwt = implode('.', $pieces);
            }
            $responses = [
                $reply(['access_token' => 'provider-access-token', 'id_token' => $jwt, 'expires_in' => 3600]),
                $reply(['id' => 'stable-subject', 'displayName' => 'Provider Student', 'userPrincipalName' => 'student@example.com']),
                $reply(['issuer' => 'https://login.microsoftonline.com/{tenantid}/v2.0',
                    'jwks_uri' => 'https://login.microsoftonline.com/common/discovery/v2.0/keys',
                    'id_token_signing_alg_values_supported' => ['RS256']]),
                $reply(['keys' => [['kty' => 'RSA', 'kid' => 'test-key', 'use' => 'sig', 'alg' => 'RS256',
                    'n' => $encode($rsa['n']), 'e' => $encode($rsa['e'])]]]),
            ];
        }
        $history = [];
        $stack = HandlerStack::create(new MockHandler($responses));
        $stack->push(Middleware::history($history));
        Socialite::driver($provider)->setRequest(Request::create($callback));
        $this->app->when(SocialAuthController::class)->needs(ClientInterface::class)
            ->give(fn () => new Client(['handler' => $stack]));
        $this->get($callback)->assertRedirect("studenthaven://oauth/callback?attempt=$id");
        $this->assertSame('POST', $history[0]['request']->getMethod());
        parse_str((string) $history[0]['request']->getBody(), $tokenRequest);
        $this->assertSame('test-code', $tokenRequest['code']);
        $this->assertSame('test-secret', $tokenRequest['client_secret']);
        if ($scenario === 'success') {
            $this->exchange($id)->assertOk()->assertJsonPath('data.name', 'Provider Student');
            $stored = json_encode([User::first(), DB::table('social_auth_attempts')->first()]);
            $this->assertStringNotContainsString('provider-access-token', $stored);
        } else {
            $this->exchange($id)->assertBadRequest();
            $this->assertDatabaseCount('users', 0);
            $this->assertDatabaseCount('personal_access_tokens', 0);
        }
    }

    #[DataProvider('providers')]
    public function test_real_provider_authorization_urls_use_expected_hosts_scopes_and_hashed_state(string $provider): void
    {
        $response = $this->postJson("/api/v1/auth/social/$provider/start", ['challenge' => $this->challenge])->assertOk();
        $url = parse_url($response->json('authorization_url'));
        $this->assertSame('https', $url['scheme']);
        $this->assertSame($provider === 'google' ? 'accounts.google.com' : 'login.microsoftonline.com', $url['host']);
        if ($provider === 'microsoft') {
            $this->assertSame('/common/oauth2/v2.0/authorize', $url['path']);
        }
        parse_str($url['query'], $parameters);
        $this->assertSame('test-client', $parameters['client_id']);
        $this->assertSame('select_account', $parameters['prompt']);
        $this->assertSame(config("services.$provider.redirect"), $parameters['redirect_uri']);
        $this->assertStringContainsString('openid', $parameters['scope']);
        $this->assertStringContainsString('profile', $parameters['scope']);
        $this->assertStringContainsString($provider === 'google' ? 'email' : 'User.Read', $parameters['scope']);
        $this->assertSame(64, strlen($parameters['state']));
        $this->assertDatabaseHas('social_auth_attempts', ['id' => $response->json('attempt_id'),
            'state_hash' => hash('sha256', $parameters['state']), 'challenge' => $this->challenge]);
        $this->assertStringNotContainsString('test-secret', $response->getContent());
    }

    public function test_migration_preserves_password_accounts_and_refuses_unsafe_rollback(): void
    {
        $user = User::factory()->create(['email' => 'migration@example.com', 'password' => 'Long-test-password']);
        $migration = require database_path('migrations/2026_11_09_220000_add_social_authentication.php');
        $migration->down();
        $migration->up();
        $this->postJson('/api/v1/auth/login', ['email' => $user->email, 'password' => 'Long-test-password',
            'device_name' => 'Migration'])->assertOk()->assertJsonPath('data.id', $user->id);
        User::create(['name' => 'Social', 'email' => $user->email, 'password' => null,
            'auth_provider' => 'google', 'provider_subject' => 'stable-subject']);
        try {
            $migration->down();
            $this->fail('Rollback must not remove provider identities or require null passwords.');
        } catch (\RuntimeException $exception) {
            $this->assertStringContainsString('Remove social accounts', $exception->getMessage());
        }
        $this->assertDatabaseCount('users', 2);
        $this->assertTrue(Schema::hasTable('social_auth_attempts'));
    }

    private function begin(string $provider, string $subject = 'stable-subject', string $email = 'student@example.com',
        bool $failure = false, bool $mismatch = false): array
    {
        $state = null;
        Socialite::swap(Mockery::mock(Factory::class));
        $driver = Mockery::mock();
        Socialite::shouldReceive('driver')->with($provider)->andReturn($driver);
        $driver->shouldReceive('stateless')->andReturnSelf();
        $driver->shouldReceive('setHttpClient')->andReturnSelf();
        $driver->shouldReceive('with')->once()->withArgs(function (array $parameters) use (&$state) {
            $state = $parameters['state'];

            return $parameters['prompt'] === 'select_account';
        })->andReturnSelf();
        $driver->shouldReceive('redirect')->once()->andReturn(redirect()->away('https://provider.example/authorize'));
        if ($failure) {
            $driver->shouldReceive('user')->andThrow(new \RuntimeException('SECRET_ACCESS_TOKEN'));
        } else {
            $driver->shouldReceive('user')->andReturn((new SocialUser)->map([
                'id' => $subject, 'name' => 'Provider Student', 'email' => $email,
            ]));
        }
        $driver->shouldReceive('getClaims')->andReturn((object) ['tid' => 'tenant', 'oid' => $mismatch ? 'different-object' : $subject,
            'aud' => config('services.microsoft.client_id')]);
        $id = $this->postJson("/api/v1/auth/social/$provider/start", ['challenge' => $this->challenge])
            ->assertOk()->assertHeader('Cache-Control', 'no-store, private')->json('attempt_id');

        return [$id, $state, $driver];
    }

    private function exchange(string $id, ?string $verifier = null): TestResponse
    {
        return $this->postJson('/api/v1/auth/social/exchange', [
            'attempt_id' => $id, 'verifier' => $verifier ?? $this->verifier, 'device_name' => 'Android test',
        ]);
    }

    #[DataProvider('providers')]
    public function test_both_providers_issue_a_session_only_after_callback_and_proof(string $provider): void
    {
        [$id, $state] = $this->begin($provider);
        $this->exchange($id)->assertConflict()->assertJsonPath('code', 'authorization_pending');
        $response = $this->get("/api/v1/auth/social/$provider/callback?state=$state&code=authorization-code")
            ->assertRedirect("studenthaven://oauth/callback?attempt=$id")->assertHeader('Referrer-Policy', 'no-referrer');
        $this->assertStringNotContainsString('token', $response->headers->get('Location'));
        $this->exchange($id, str_repeat('x', 43))->assertBadRequest();
        $login = $this->exchange($id)->assertOk()->assertJsonPath('token_type', 'Bearer');
        $this->assertSame(now()->addDays(30)->toDateString(), substr($login->json('expires_at'), 0, 10));
        $this->assertArrayNotHasKey('provider_subject', $login->json('data'));
        $this->assertArrayNotHasKey('password', $login->json('data'));
        $token = $login->json('token');
        $this->assertDatabaseCount('users', 1);
        $this->assertNull(User::first()->password);
        $this->assertSame($provider, User::first()->auth_provider);
        $this->exchange($id)->assertBadRequest();
        $this->get("/api/v1/auth/social/$provider/callback?state=$state&code=replay")->assertBadRequest();
        $this->assertDatabaseCount('personal_access_tokens', 1);
        $this->withToken($token)->getJson('/api/v1/me')->assertOk()->assertJsonPath('data.name', 'Provider Student');
        $this->postJson('/api/v1/auth/logout')->assertNoContent();
        app('auth')->forgetGuards();
        $this->getJson('/api/v1/me')->assertUnauthorized();
    }

    public function test_same_email_never_links_password_google_or_microsoft_accounts(): void
    {
        $passwordUser = User::factory()->create(['email' => 'student@example.com', 'password' => 'Long-test-password']);
        [$google, $state] = $this->begin('google');
        $this->get("/api/v1/auth/social/google/callback?state=$state&code=google")->assertRedirect();
        $googleUser = $this->exchange($google)->assertOk()->json('data.id');
        [$microsoft, $state] = $this->begin('microsoft');
        $this->get("/api/v1/auth/social/microsoft/callback?state=$state&code=microsoft")->assertRedirect();
        $microsoftUser = $this->exchange($microsoft)->assertOk()->json('data.id');
        $this->assertCount(3, array_unique([$passwordUser->id, $googleUser, $microsoftUser]));
        $this->assertDatabaseCount('users', 3);
        $this->postJson('/api/v1/auth/login', ['email' => 'student@example.com', 'password' => 'Long-test-password',
            'device_name' => 'Password'])->assertOk()->assertJsonPath('data.id', $passwordUser->id);
    }

    public function test_social_only_email_can_register_password_account_and_cannot_use_password_login(): void
    {
        [$id, $state] = $this->begin('google');
        $this->get("/api/v1/auth/social/google/callback?state=$state&code=code")->assertRedirect();
        $social = $this->exchange($id)->assertOk()->json('data.id');
        $this->postJson('/api/v1/auth/login', ['email' => 'student@example.com', 'password' => 'Long-test-password',
            'device_name' => 'Password'])->assertUnprocessable();
        $password = $this->postJson('/api/v1/auth/register', ['name' => 'Password user', 'email' => 'student@example.com',
            'password' => 'Long-test-password', 'password_confirmation' => 'Long-test-password',
            'device_name' => 'Password'])->assertCreated()->json('data.id');
        $this->assertNotSame($social, $password);
    }

    public function test_stable_subject_returns_same_user_even_if_email_changes(): void
    {
        [$id, $state] = $this->begin('google');
        $this->get("/api/v1/auth/social/google/callback?state=$state&code=first");
        $first = $this->exchange($id)->assertOk()->json('data.id');
        [$id, $state] = $this->begin('google', email: 'changed@example.com');
        $this->get("/api/v1/auth/social/google/callback?state=$state&code=second");
        $this->exchange($id)->assertOk()->assertJsonPath('data.id', $first);
        $this->assertDatabaseCount('users', 1);
    }

    public function test_unknown_state_wrong_provider_expired_and_cancelled_attempts_fail_closed(): void
    {
        [$id, $state] = $this->begin('google');
        $this->get('/api/v1/auth/social/google/callback?state='.str_repeat('z', 64).'&code=bad')->assertBadRequest();
        $this->get("/api/v1/auth/social/microsoft/callback?state=$state&code=bad")->assertBadRequest();
        $this->get("/api/v1/auth/social/google/callback?state=$state&error=access_denied")->assertRedirect();
        $this->exchange($id)->assertBadRequest()->assertJsonPath('message', 'Sign-in was cancelled. Please try again.');
        $this->assertDatabaseCount('users', 0);
        [$id, $state] = $this->begin('google');
        DB::table('social_auth_attempts')->where('id', $id)->update(['expires_at' => now()->subSecond()]);
        $this->get("/api/v1/auth/social/google/callback?state=$state&code=expired")->assertBadRequest();
        $this->exchange($id)->assertBadRequest();
        $this->assertDatabaseCount('personal_access_tokens', 0);
    }

    public function test_provider_failure_never_creates_a_session_or_leaks_provider_errors(): void
    {
        [$id, $state] = $this->begin('google', failure: true);
        $response = $this->get("/api/v1/auth/social/google/callback?state=$state&code=bad")->assertRedirect();
        $this->assertStringNotContainsString('SECRET', $response->getContent());
        $this->exchange($id)->assertBadRequest();
        $this->assertDatabaseCount('users', 0);
        $this->assertDatabaseCount('personal_access_tokens', 0);
    }

    public function test_configuration_validation_and_unknown_providers(): void
    {
        config(['services.google.client_id' => null]);
        $this->postJson('/api/v1/auth/social/google/start', ['challenge' => $this->challenge])
            ->assertStatus(503)->assertJsonPath('message', 'Google sign-in is not configured yet.');
        $this->postJson('/api/v1/auth/social/apple/start', ['challenge' => $this->challenge])->assertNotFound();
        $this->postJson('/api/v1/auth/social/microsoft/start', ['challenge' => 'bad'])->assertUnprocessable();
        $this->postJson('/api/v1/auth/social/exchange', ['attempt_id' => 'bad', 'verifier' => 'bad'])->assertUnprocessable();
        $this->assertDatabaseCount('social_auth_attempts', 0);
    }

    public function test_microsoft_identity_requires_validated_tenant_and_matching_object_id(): void
    {
        [$id, $state] = $this->begin('microsoft', mismatch: true);
        $this->get("/api/v1/auth/social/microsoft/callback?state=$state&code=bad")->assertRedirect();
        $this->exchange($id)->assertBadRequest();
        $this->assertDatabaseCount('users', 0);
    }

    public function test_social_account_deletion_requires_recent_provider_authentication(): void
    {
        [$id, $state] = $this->begin('google');
        $this->get("/api/v1/auth/social/google/callback?state=$state&code=code");
        $token = $this->exchange($id)->assertOk()->json('token');
        $this->withToken($token);
        $this->travel(6)->minutes();
        $this->deleteJson('/api/v1/me')->assertUnprocessable();
        $this->travelBack();
        $this->deleteJson('/api/v1/me')->assertNoContent();
        $this->assertDatabaseCount('users', 0);
        $this->assertDatabaseCount('social_auth_attempts', 0);
        $this->assertDatabaseCount('personal_access_tokens', 0);
    }
}
