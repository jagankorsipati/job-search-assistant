package com.jobsearchassistant.applications;

class ApplicationConflictException extends RuntimeException {
    ApplicationConflictException(String code) {
        super(code);
    }
}
