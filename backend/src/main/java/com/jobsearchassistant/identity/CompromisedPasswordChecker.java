package com.jobsearchassistant.identity;

interface CompromisedPasswordChecker {
    void validate(char[] password, String loginName, String displayName);
}
