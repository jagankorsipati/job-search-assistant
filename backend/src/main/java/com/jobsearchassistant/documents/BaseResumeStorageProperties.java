package com.jobsearchassistant.documents;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "documents.base-resume.storage")
record BaseResumeStorageProperties(Path root) {
}
