package com.jobsearchassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.boot.autoconfigure.web.ServerProperties;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
        "identity.persistence.enabled=false",
        "spring.session.store-type=none"
})
@AutoConfigureMockMvc
class JobSearchAssistantApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private ServerProperties serverProperties;

    @Test
    void applicationContextLoads() {
    }

    @Test
    void healthEndpointReportsStatusWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/vnd.spring-boot.actuator.v3+json"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void testOnlyOwnershipEndpointIsAbsentFromNormalApplicationContext() {
        assertThat(mappings.getHandlerMethods().keySet())
                .noneMatch(mapping -> mapping.getPatternValues().stream()
                        .anyMatch(pattern -> pattern.startsWith("/test/owned-resources")));
    }

    @Test
    void sessionCookieRemainsSecureByDefault() {
        assertThat(serverProperties.getServlet().getSession().getCookie().getName()).isEqualTo("JSA_SESSION");
        assertThat(serverProperties.getServlet().getSession().getCookie().getHttpOnly()).isTrue();
        assertThat(serverProperties.getServlet().getSession().getCookie().getSecure()).isTrue();
        assertThat(serverProperties.getServlet().getSession().getCookie().getSameSite()).hasToString("STRICT");
    }
}
