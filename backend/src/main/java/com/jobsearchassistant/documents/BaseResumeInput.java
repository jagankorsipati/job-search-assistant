package com.jobsearchassistant.documents;

import java.nio.file.Path;

record BaseResumeInput(String originalFilename, String mediaType, long byteSize, String sha256Checksum, Path stagedFile) {
}
