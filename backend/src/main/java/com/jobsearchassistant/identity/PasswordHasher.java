package com.jobsearchassistant.identity;

interface PasswordHasher {

    String hash(char[] password);

    boolean matches(char[] password, String encodedHash);
}
