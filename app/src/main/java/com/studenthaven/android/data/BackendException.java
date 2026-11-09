package com.studenthaven.android.data;

public final class BackendException extends Exception {
    private final int status;
    private org.json.JSONObject response;

    public BackendException(String message, int status) {
        super(message);
        this.status = status;
    }

    public BackendException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() { return status; }
    public org.json.JSONObject response() { return response; }

    public BackendException(String message, int status, org.json.JSONObject response) {
        this(message, status);
        this.response = response;
    }
}
