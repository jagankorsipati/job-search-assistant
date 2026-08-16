package com.jobsearchassistant.identity;

import java.security.SecureRandom;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class IdentityConfiguration {

    @Bean
    Clock identityClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom identitySecureRandom() {
        return new SecureRandom();
    }

    @Bean
    PasswordPolicy passwordPolicy() {
        return new PasswordPolicy();
    }
}
