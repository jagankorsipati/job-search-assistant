package com.jobsearchassistant.identity;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration(proxyBeanMethods = false)
class WebSecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            ObjectProvider<SessionValidationFilter> validationFilter,
            AnonymousCsrfRateLimitFilter csrfRateLimitFilter) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/api/auth/csrf", "/api/auth/login",
                                "/api/invitations/accept").permitAll()
                        .requestMatchers("/api/admin/invitations", "/api/admin/accounts/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/me", "/api/auth/logout", "/api/profile/**",
                                "/api/documents/**", "/api/jobs/**").authenticated()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .rememberMe(remember -> remember.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> problem(response, 401,
                                "Authentication required"))
                        .accessDeniedHandler((request, response, exception) -> problem(response, 403,
                                "Access denied")));
        SessionValidationFilter filter = validationFilter.getIfAvailable();
        if (filter != null) {
            http.addFilterAfter(filter, SecurityContextHolderFilter.class);
        }
        http.addFilterBefore(csrfRateLimitFilter, CsrfFilter.class);
        return http.build();
    }

    private static void problem(HttpServletResponse response, int status, String title) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"title\":\"" + title + "\",\"status\":" + status + "}");
    }
}
