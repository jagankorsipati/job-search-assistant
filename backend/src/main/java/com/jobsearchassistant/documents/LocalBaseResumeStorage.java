package com.jobsearchassistant.documents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class LocalBaseResumeStorage implements BaseResumeStorage {
    private final Path root;
    private final Path stagingRoot;
    private final Path publishedRoot;

    LocalBaseResumeStorage(BaseResumeStorageProperties properties) {
        this.root = initializeRoot(properties.root());
        this.stagingRoot = ensureDirectory(root.resolve("staging"));
        this.publishedRoot = ensureDirectory(root.resolve("objects"));
    }

    @Override
    public StoredBaseResume stage(InputStream input, String submittedFilename) throws IOException {
        String originalFilename = BaseResumeFilename.normalize(submittedFilename);
        String storageKey = UUID.randomUUID() + "-" + UUID.randomUUID();
        ensureDirectory(stagingRoot);
        Path staged = contained(stagingRoot, storageKey + ".upload");
        MessageDigest digest = sha256();
        long total = 0;
        try (DigestInputStream digesting = new DigestInputStream(input, digest);
                var output = Files.newOutputStream(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = digesting.read(buffer)) != -1) {
                total += read;
                if (total > BaseResumeValidator.MAX_BYTES) {
                    throw new BaseResumeValidationException("invalid_file");
                }
                output.write(buffer, 0, read);
            }
        } catch (RuntimeException | IOException failure) {
            Files.deleteIfExists(staged);
            throw failure;
        }
        String checksum = HexFormat.of().formatHex(digest.digest());
        String mediaType;
        try {
            mediaType = BaseResumeValidator.validate(staged, originalFilename, total);
        } catch (RuntimeException failure) {
            Files.deleteIfExists(staged);
            throw failure;
        }
        restrictPermissions(staged);
        return new StoredBaseResume(storageKey, staged,
                new BaseResumeInput(originalFilename, mediaType, total, checksum, staged));
    }

    @Override
    public void publish(StoredBaseResume staged) throws IOException {
        ensureDirectory(publishedRoot);
        Path destination = pathFor(staged.storageKey());
        Files.move(staged.stagedPath(), destination, StandardCopyOption.ATOMIC_MOVE);
        restrictPermissions(destination);
    }

    @Override
    public BaseResumeDownload open(String storageKey) throws IOException {
        Path path = pathFor(storageKey);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("missing stored document");
        }
        return new BaseResumeDownload(Files.newInputStream(path, StandardOpenOption.READ), Files.size(path));
    }

    @Override
    public void deleteIfExists(String storageKey) {
        try {
            Files.deleteIfExists(pathFor(storageKey));
        } catch (IOException ignored) {
            // Best-effort post-commit cleanup. Metadata now points to the replacement document.
        }
    }

    private Path pathFor(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || storageKey.contains("/") || storageKey.contains("\\")
                || storageKey.contains("..")) {
            throw new BaseResumeValidationException("invalid_file");
        }
        return contained(publishedRoot, storageKey + ".bin");
    }

    private static Path initializeRoot(Path configured) {
        if (configured == null) {
            throw new IllegalStateException("documents.base-resume.storage.root is required");
        }
        Path normalized = configured.toAbsolutePath().normalize();
        return ensureDirectory(normalized);
    }

    private static Path ensureDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(directory)) {
                throw new IllegalStateException("Base resume storage directory is not writable");
            }
            return directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            throw new IllegalStateException("Base resume storage directory is not usable", e);
        }
    }

    private static Path contained(Path parent, String filename) {
        Path resolved = parent.resolve(filename).normalize().toAbsolutePath();
        if (!resolved.startsWith(parent)) {
            throw new BaseResumeValidationException("invalid_file");
        }
        return resolved;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some filesystems do not expose POSIX permissions.
        }
    }
}
