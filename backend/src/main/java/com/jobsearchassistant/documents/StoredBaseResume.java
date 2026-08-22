package com.jobsearchassistant.documents;

import java.nio.file.Path;

record StoredBaseResume(String storageKey, Path stagedPath, BaseResumeInput input) {
}
