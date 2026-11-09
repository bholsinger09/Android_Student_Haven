<?php

use Illuminate\Support\Facades\Route;

Route::get('/', function () {
    return response()->json(['service' => 'Student Haven API', 'api_version' => 'v1']);
});
