package com.jobsearchassistant.documents;

import java.io.IOException;
import java.io.InputStream;

interface BaseResumeStorage {
    StoredBaseResume stage(InputStream input, String originalFilename) throws IOException;

    void publish(StoredBaseResume staged) throws IOException;

    BaseResumeDownload open(String storageKey) throws IOException;

    void deleteIfExists(String storageKey);
}
