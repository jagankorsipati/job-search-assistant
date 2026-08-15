package com.jobsearchassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import com.jobsearchassistant.identity.AccountId;
import com.jobsearchassistant.identity.InvitationLifecycle;
import com.jobsearchassistant.identity.LoginName;
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

    @Test
    void identityDomainRemainsInsideAnIndependentClosedModule() {
        var identity = modules.getModuleByName("identity").orElseThrow();

        assertThat(identity.getBasePackage().getName()).isEqualTo("com.jobsearchassistant.identity");
        assertThat(identity).matches(module -> module.contains(AccountId.class));
        assertThat(identity).matches(module -> module.contains(LoginName.class));
        assertThat(identity).matches(module -> module.contains(InvitationLifecycle.class));
        assertThat(identity.getDirectDependencies(modules).isEmpty()).isTrue();
    }
}
