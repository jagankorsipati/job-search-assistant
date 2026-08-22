package com.jobsearchassistant.documents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

final class BaseResumeValidator {
    static final long MAX_BYTES = 5L * 1024L * 1024L;
    static final long MULTIPART_MAX_BYTES = MAX_BYTES + (512L * 1024L);
    static final long MAX_DOCX_UNCOMPRESSED_BYTES = 25L * 1024L * 1024L;
    static final int MAX_DOCX_ENTRIES = 512;
    static final String PDF = "application/pdf";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private BaseResumeValidator() {
    }

    static String validate(Path file, String originalFilename, long byteSize) {
        if (byteSize <= 0 || byteSize > MAX_BYTES) {
            throw new BaseResumeValidationException("invalid_file");
        }
        String lowerName = originalFilename.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".pdf")) {
            validatePdf(file);
            return PDF;
        }
        if (lowerName.endsWith(".docx")) {
            validateDocx(file);
            return DOCX;
        }
        throw new BaseResumeValidationException("invalid_file");
    }

    private static void validatePdf(Path file) {
        byte[] prefix = new byte[1024];
        int read;
        try (InputStream input = Files.newInputStream(file)) {
            read = input.read(prefix);
        } catch (IOException e) {
            throw new BaseResumeValidationException("invalid_file");
        }
        if (read < 8) {
            throw new BaseResumeValidationException("invalid_file");
        }
        String header = new String(prefix, 0, Math.min(read, prefix.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!header.contains("%PDF-") || !hasPdfTrailer(file)) {
            throw new BaseResumeValidationException("invalid_file");
        }
    }

    private static boolean hasPdfTrailer(Path file) {
        try {
            long size = Files.size(file);
            int tailSize = (int) Math.min(size, 2048L);
            byte[] tail;
            try (InputStream input = Files.newInputStream(file)) {
                input.skipNBytes(size - tailSize);
                tail = input.readNBytes(tailSize);
            }
            return new String(tail, java.nio.charset.StandardCharsets.ISO_8859_1).contains("%%EOF");
        } catch (IOException e) {
            return false;
        }
    }

    private static void validateDocx(Path file) {
        int entries = 0;
        long totalUncompressed = 0;
        boolean contentTypes = false;
        boolean document = false;
        rejectEncryptedZipEntries(file);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                entries++;
                if (entries > MAX_DOCX_ENTRIES) {
                    throw new BaseResumeValidationException("invalid_file");
                }
                String name = entry.getName();
                validateZipEntryName(name);
                if (entry.getSize() >= 0) {
                    totalUncompressed += entry.getSize();
                    if (entry.getCompressedSize() > 0 && entry.getSize() / entry.getCompressedSize() > 100) {
                        throw new BaseResumeValidationException("invalid_file");
                    }
                }
                if (totalUncompressed > MAX_DOCX_UNCOMPRESSED_BYTES) {
                    throw new BaseResumeValidationException("invalid_file");
                }
                contentTypes |= "[Content_Types].xml".equals(name);
                document |= "word/document.xml".equals(name);
            }
        } catch (ZipException e) {
            throw new BaseResumeValidationException("invalid_file");
        } catch (IOException e) {
            throw new BaseResumeValidationException("invalid_file");
        }
        if (!contentTypes || !document) {
            throw new BaseResumeValidationException("invalid_file");
        }
    }

    private static void rejectEncryptedZipEntries(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i <= bytes.length - 10; i++) {
                if (matchesSignature(bytes, i, 0x50, 0x4b, 0x03, 0x04) && encryptedFlagSet(bytes, i + 6)) {
                    throw new BaseResumeValidationException("invalid_file");
                }
                if (matchesSignature(bytes, i, 0x50, 0x4b, 0x01, 0x02) && encryptedFlagSet(bytes, i + 8)) {
                    throw new BaseResumeValidationException("invalid_file");
                }
            }
        } catch (IOException e) {
            throw new BaseResumeValidationException("invalid_file");
        }
    }

    private static boolean matchesSignature(byte[] bytes, int offset, int first, int second, int third, int fourth) {
        return Byte.toUnsignedInt(bytes[offset]) == first
                && Byte.toUnsignedInt(bytes[offset + 1]) == second
                && Byte.toUnsignedInt(bytes[offset + 2]) == third
                && Byte.toUnsignedInt(bytes[offset + 3]) == fourth;
    }

    private static boolean encryptedFlagSet(byte[] bytes, int offset) {
        int flags = Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
        return (flags & 1) == 1;
    }

    private static void validateZipEntryName(String name) {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || name.contains("..") || name.contains(":")
                || name.chars().anyMatch(ch -> Character.isISOControl(ch) || ch == 0)) {
            throw new BaseResumeValidationException("invalid_file");
        }
    }
}
