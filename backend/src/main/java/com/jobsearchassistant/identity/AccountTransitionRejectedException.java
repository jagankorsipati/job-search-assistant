package com.jobsearchassistant.identity;

final class AccountTransitionRejectedException extends RuntimeException {
    AccountTransitionRejectedException() { super("Account transition is not available"); }
}
