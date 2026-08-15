package com.jobsearchassistant.identity;

import java.util.Arrays;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "identity.bootstrap.enabled", havingValue = "true")
class AdministratorBootstrapRunner implements ApplicationRunner {

    private final AdministratorBootstrapService bootstrapService;
    private final Environment environment;

    AdministratorBootstrapRunner(AdministratorBootstrapService bootstrapService, Environment environment) {
        this.bootstrapService = bootstrapService;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String loginName = required("IDENTITY_BOOTSTRAP_LOGIN");
        String displayName = required("IDENTITY_BOOTSTRAP_DISPLAY_NAME");
        char[] password = required("IDENTITY_BOOTSTRAP_PASSWORD").toCharArray();
        try {
            bootstrapService.bootstrap(true, loginName, displayName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private String required(String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new BootstrapRejectedException(name + " is required when bootstrap is enabled");
        }
        return value;
    }
}
