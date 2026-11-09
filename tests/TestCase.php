<?php

namespace Tests;

use Illuminate\Foundation\Application;
use Illuminate\Foundation\Testing\TestCase as BaseTestCase;
use RuntimeException;

abstract class TestCase extends BaseTestCase
{
    public function createApplication(): Application
    {
        $app = parent::createApplication();
        $connection = $app['config']->get('database.default');
        $database = $app['config']->get("database.connections.$connection");
        if ($app->configurationIsCached() || ! $app->environment('testing')
            || $connection !== 'pgsql'
            || $database['database'] !== 'student_haven_backend_test'
            || ! empty($database['url'])) {
            throw new RuntimeException('Tests require uncached configuration and the dedicated PostgreSQL test database.');
        }

        return $app;
    }
}
