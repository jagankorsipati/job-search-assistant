package com.jobsearchassistant.identity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
class OfflineCompromisedPasswordChecker implements CompromisedPasswordChecker {
    private static final String VERSION_HEADER =
            "# job-search-assistant compromised-password blocklist v2026.1";
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final int MINIMUM_DIGESTS = 9_500;
    private final Set<String> digests;

    OfflineCompromisedPasswordChecker(
            @Value("classpath:security/compromised-passwords-sha256.txt") Resource resource) {
        this.digests = load(resource);
    }

    @Override
    public void validate(char[] password, String loginName, String displayName) {
        if (password == null) throw new PasswordRejectedException();
        String value = CharBuffer.wrap(password).toString();
        String folded = value.toLowerCase(Locale.ROOT);
        if (digests.contains(digest(value)) || digests.contains(digest(folded))
                || containsContext(folded, loginName) || containsDisplayName(folded, displayName)
                || folded.replaceAll("[^a-z0-9]", "").contains("jobsearchassistant")) {
            throw new PasswordRejectedException();
        }
    }

    private boolean containsContext(String password, String context) {
        if (context == null) return false;
        String normalized = context.strip().toLowerCase(Locale.ROOT);
        return normalized.length() >= 3 && password.contains(normalized);
    }

    private boolean containsDisplayName(String password, String displayName) {
        if (displayName == null) return false;
        return Pattern.compile("[^\\p{L}\\p{N}]+").splitAsStream(displayName.toLowerCase(Locale.ROOT))
                .anyMatch(part -> part.codePointCount(0, part.length()) >= 4 && password.contains(part));
    }

    private Set<String> load(Resource resource) {
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            if (!VERSION_HEADER.equals(first)) throw new IllegalStateException("compromised-password blocklist version mismatch");
            TreeSet<String> loaded = new TreeSet<>();
            String previous = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) continue;
                if (!DIGEST.matcher(line).matches() || (previous != null && line.compareTo(previous) <= 0)) {
                    throw new IllegalStateException("compromised-password blocklist is corrupt");
                }
                loaded.add(line);
                previous = line;
            }
            if (loaded.size() < MINIMUM_DIGESTS) throw new IllegalStateException("compromised-password blocklist coverage is insufficient");
            return Set.copyOf(loaded);
        } catch (IOException failure) {
            throw new IllegalStateException("required compromised-password blocklist cannot be loaded", failure);
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
