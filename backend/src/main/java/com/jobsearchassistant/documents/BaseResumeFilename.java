package com.jobsearchassistant.documents;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.text.Normalizer;

final class BaseResumeFilename {
    static final int MAX_LENGTH = 180;

    private BaseResumeFilename() {
    }

    static String normalize(String submitted) {
        if (submitted == null) {
            throw new BaseResumeValidationException("invalid_file");
        }
        if (submitted.indexOf('\0') >= 0) {
            throw new BaseResumeValidationException("invalid_file");
        }
        String withoutNulls = submitted;
        String leaf;
        try {
            leaf = Path.of(withoutNulls).getFileName().toString();
        } catch (InvalidPathException invalid) {
            throw new BaseResumeValidationException("invalid_file");
        }
        leaf = leaf.replace('\\', '/');
        int separator = leaf.lastIndexOf('/');
        if (separator >= 0) {
            leaf = leaf.substring(separator + 1);
        }
        String normalized = Normalizer.normalize(leaf, Normalizer.Form.NFC).trim();
        if (normalized.isBlank() || ".".equals(normalized) || "..".equals(normalized)
                || normalized.length() > MAX_LENGTH || normalized.contains("/") || normalized.contains("\\")
                || normalized.chars().anyMatch(ch -> Character.isISOControl(ch) || ch == 0)) {
            throw new BaseResumeValidationException("invalid_file");
        }
        return normalized;
    }
}
