package com.jobsearchassistant.identity;

import java.nio.CharBuffer;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
class Argon2idPasswordHasher implements PasswordHasher {

    static final int SALT_LENGTH_BYTES = 16;
    static final int HASH_LENGTH_BYTES = 32;
    static final int PARALLELISM = 1;
    static final int MEMORY_KIB = 19 * 1024;
    static final int ITERATIONS = 2;

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(
            SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);

    @Override
    public String hash(char[] password) {
        return encoder.encode(CharBuffer.wrap(password));
    }

    @Override
    public boolean matches(char[] password, String encodedHash) {
        try {
            return encoder.matches(CharBuffer.wrap(password), encodedHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
