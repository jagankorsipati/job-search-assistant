package com.jobsearchassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularArchitectureTests {

    private final ApplicationModules modules = ApplicationModules.of(JobSearchAssistantApplication.class);

    @Test
    void moduleBoundariesAreValid() {
        modules.verify();
    }

    @Test
    void architectureContainsTheDocumentedModules() {
        Set<String> moduleNames = modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(Collectors.toSet());

        assertThat(moduleNames).containsExactlyInAnyOrder(
                "identity",
                "profile",
                "jobs",
                "fit",
                "documents",
                "applications",
                "integrations",
                "operations");
    }
}
