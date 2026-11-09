<?php

namespace App\Providers;

use App\Http\Controllers\Api\SocialAuthController;
use GuzzleHttp\Client;
use GuzzleHttp\ClientInterface;
use Illuminate\Cache\RateLimiting\Limit;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Event;
use Illuminate\Support\Facades\RateLimiter;
use Illuminate\Support\ServiceProvider;
use SocialiteProviders\Manager\SocialiteWasCalled;
use SocialiteProviders\Microsoft\MicrosoftExtendSocialite;

class AppServiceProvider extends ServiceProvider
{
    /**
     * Register any application services.
     */
    public function register(): void
    {
        $this->app->when(SocialAuthController::class)->needs(ClientInterface::class)
            ->give(fn () => new Client(['connect_timeout' => 5, 'timeout' => 15]));
    }

    /**
     * Bootstrap any application services.
     */
    public function boot(): void
    {
        Event::listen(SocialiteWasCalled::class, [MicrosoftExtendSocialite::class, 'handle']);
        RateLimiter::for('social-auth', fn (Request $request) => Limit::perMinute(30)->by($request->ip()));
        RateLimiter::for('registration', fn (Request $request) => Limit::perMinute(5)->by($request->ip()));
        RateLimiter::for('login', fn (Request $request) => [
            Limit::perMinute(30)->by('ip:'.$request->ip()),
            Limit::perMinute(5)->by('email:'.hash('sha256', mb_strtolower(
                is_string($request->input('email')) ? $request->input('email') : ''
            )).'|'.$request->ip()),
        ]);
        RateLimiter::for('api', fn (Request $request) => Limit::perMinute(120)->by((string) $request->user()->id));
    }
}
