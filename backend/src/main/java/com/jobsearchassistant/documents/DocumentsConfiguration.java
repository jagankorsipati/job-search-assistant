package com.jobsearchassistant.documents;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BaseResumeStorageProperties.class)
class DocumentsConfiguration {
}
