package com.jobsearchassistant.identity;

final class LoginUnavailableException extends RuntimeException {
    LoginUnavailableException() { super("Login name is unavailable"); }
}
