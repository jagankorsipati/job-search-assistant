package com.jobsearchassistant.documents;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.jobsearchassistant.identity.api.CurrentActorProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class BaseResumeService {
    private final CurrentActorProvider actors;
    private final BaseResumeRepository repository;
    private final BaseResumeStorage storage;
    private final Clock clock;

    @Autowired
    BaseResumeService(CurrentActorProvider actors, BaseResumeRepository repository, BaseResumeStorage storage) {
        this(actors, repository, storage, Clock.systemUTC());
    }

    BaseResumeService(CurrentActorProvider actors, BaseResumeRepository repository, BaseResumeStorage storage, Clock clock) {
        this.actors = actors;
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BaseResumeDocument current() {
        return repository.findByOwner(owner()).orElseThrow(BaseResumeNotFoundException::new);
    }

    @Transactional
    public BaseResumeDocument upload(InputStream input, String originalFilename) {
        UUID owner = owner();
        if (repository.findByOwner(owner).isPresent()) {
            throw new BaseResumeConflictException("base_resume_exists");
        }
        StoredBaseResume staged = stage(input, originalFilename);
        try {
            storage.publish(staged);
            Instant now = clock.instant();
            BaseResumeInput stagedInput = staged.input();
            BaseResumeDocument document = new BaseResumeDocument(UUID.randomUUID(), owner,
                    stagedInput.originalFilename(), stagedInput.mediaType(), stagedInput.byteSize(),
                    stagedInput.sha256Checksum(), staged.storageKey(), now, now, 0);
            repository.insert(document);
            registerRollbackCleanup(staged.storageKey());
            return document;
        } catch (DuplicateKeyException duplicate) {
            storage.deleteIfExists(staged.storageKey());
            throw new BaseResumeConflictException("base_resume_exists");
        } catch (RuntimeException | IOException failure) {
            storage.deleteIfExists(staged.storageKey());
            throw storageFailure(failure);
        }
    }

    @Transactional
    public BaseResumeDocument replace(InputStream input, String originalFilename, long expectedVersion) {
        requireVersion(expectedVersion);
        UUID owner = owner();
        BaseResumeDocument existing = repository.findByOwner(owner).orElseThrow(BaseResumeNotFoundException::new);
        StoredBaseResume staged = stage(input, originalFilename);
        try {
            storage.publish(staged);
            BaseResumeInput stagedInput = staged.input();
            BaseResumeDocument replacement = new BaseResumeDocument(existing.id(), existing.ownerAccountId(),
                    stagedInput.originalFilename(), stagedInput.mediaType(), stagedInput.byteSize(),
                    stagedInput.sha256Checksum(), staged.storageKey(), existing.createdAt(), clock.instant(),
                    existing.version() + 1);
            if (!repository.replace(replacement, expectedVersion)) {
                storage.deleteIfExists(staged.storageKey());
                throw new BaseResumeConflictException("stale_version");
            }
            registerRollbackCleanup(staged.storageKey());
            registerCommitCleanup(existing.storageKey());
            return repository.findByOwner(owner).orElseThrow(BaseResumeNotFoundException::new);
        } catch (BaseResumeConflictException conflict) {
            throw conflict;
        } catch (RuntimeException | IOException failure) {
            storage.deleteIfExists(staged.storageKey());
            throw storageFailure(failure);
        }
    }

    @Transactional(readOnly = true)
    public DownloadedBaseResume download() {
        BaseResumeDocument document = current();
        try {
            return new DownloadedBaseResume(document, storage.open(document.storageKey()));
        } catch (IOException e) {
            throw new BaseResumeNotFoundException();
        }
    }

    private StoredBaseResume stage(InputStream input, String originalFilename) {
        try {
            return storage.stage(input, originalFilename);
        } catch (BaseResumeValidationException validation) {
            throw validation;
        } catch (IOException e) {
            throw new BaseResumeValidationException("invalid_file");
        }
    }

    private UUID owner() {
        return actors.currentActor().accountId();
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new BaseResumeValidationException("invalid_request");
        }
    }

    private RuntimeException storageFailure(Exception failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new BaseResumeValidationException("invalid_file");
    }

    private void registerRollbackCleanup(String storageKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        storage.deleteIfExists(storageKey);
                    }
                }
            });
        }
    }

    private void registerCommitCleanup(String oldStorageKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storage.deleteIfExists(oldStorageKey);
                }
            });
        }
    }

    record DownloadedBaseResume(BaseResumeDocument document, BaseResumeDownload download) {
    }
}
