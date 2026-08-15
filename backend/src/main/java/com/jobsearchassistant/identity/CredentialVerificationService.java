package com.jobsearchassistant.identity;

import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
public class CredentialVerificationService {

    private static final char[] DUMMY_PASSWORD = "dummy-verification-passphrase".toCharArray();

    private final IdentityRepository repository;
    private final PasswordHasher passwordHasher;
    private final String dummyHash;

    CredentialVerificationService(IdentityRepository repository, PasswordHasher passwordHasher) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.dummyHash = passwordHasher.hash(DUMMY_PASSWORD);
    }

    public AuthenticatedIdentity verify(String loginName, char[] password) {
        if (password == null || Character.codePointCount(password, 0, password.length) > PasswordPolicy.MAXIMUM_CODE_POINTS) {
            passwordHasher.matches(DUMMY_PASSWORD, dummyHash);
            throw new AuthenticationFailedException();
        }
        LoginName normalizedLogin;
        try {
            normalizedLogin = LoginName.of(loginName);
        } catch (RuntimeException invalidLogin) {
            passwordHasher.matches(password, dummyHash);
            throw new AuthenticationFailedException();
        }

        StoredAccount account = repository.findAccount(normalizedLogin).orElse(null);
        boolean passwordMatches = passwordHasher.matches(
                password, account == null ? dummyHash : account.passwordHash());
        if (account == null || !passwordMatches || !account.status().canAuthenticate()) {
            throw new AuthenticationFailedException();
        }
        return new AuthenticatedIdentity(account.id(), account.role());
    }
}
