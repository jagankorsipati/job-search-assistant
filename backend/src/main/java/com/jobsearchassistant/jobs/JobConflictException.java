package com.jobsearchassistant.jobs;

class JobConflictException extends RuntimeException {
    JobConflictException(String message) {
        super(message);
    }
}
