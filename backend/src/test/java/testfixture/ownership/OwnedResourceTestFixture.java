package testfixture.ownership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.jobsearchassistant.identity.api.AuthenticatedActor;
import com.jobsearchassistant.identity.api.CurrentActorProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

public final class OwnedResourceTestFixture {
    private OwnedResourceTestFixture() { }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Configuration {
        @Bean OwnedRepository ownedRepository(JdbcClient jdbc) { return new OwnedRepository(jdbc); }
        @Bean OwnedService ownedService(OwnedRepository repository, CurrentActorProvider actors) {
            return new OwnedService(repository, actors);
        }
        @Bean OwnedController ownedController(OwnedService service) { return new OwnedController(service); }

        @Bean
        @Order(0)
        SecurityFilterChain ownedFixtureSecurity(HttpSecurity http) throws Exception {
            return http.securityMatcher("/test/owned-resources/**")
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                    .requestCache(cache -> cache.disable())
                    .exceptionHandling(errors -> errors
                            .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                            .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
                    .formLogin(login -> login.disable())
                    .httpBasic(basic -> basic.disable())
                    .build();
        }
    }

    public record OwnedResource(UUID id, UUID ownerAccountId, String value) { }
    record WriteRequest(String value, UUID ownerAccountId) { }

    public static final class OwnedRepository {
        private final JdbcClient jdbc;
        OwnedRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

        public OwnedResource insert(UUID id, UUID ownerId, String value) {
            jdbc.sql("INSERT INTO job_search_assistant.test_owned_resource (id, owner_account_id, value) "
                            + "VALUES (:id, :ownerId, :value)")
                    .param("id", id).param("ownerId", ownerId).param("value", value).update();
            return new OwnedResource(id, ownerId, value);
        }

        public Optional<OwnedResource> find(UUID id, UUID ownerId) {
            return jdbc.sql("SELECT id, owner_account_id, value FROM job_search_assistant.test_owned_resource "
                            + "WHERE id = :id AND owner_account_id = :ownerId")
                    .param("id", id).param("ownerId", ownerId)
                    .query((rs, row) -> new OwnedResource(rs.getObject("id", UUID.class),
                            rs.getObject("owner_account_id", UUID.class), rs.getString("value"))).optional();
        }

        public List<OwnedResource> findAll(UUID ownerId) {
            return jdbc.sql("SELECT id, owner_account_id, value FROM job_search_assistant.test_owned_resource "
                            + "WHERE owner_account_id = :ownerId ORDER BY id")
                    .param("ownerId", ownerId)
                    .query((rs, row) -> new OwnedResource(rs.getObject("id", UUID.class),
                            rs.getObject("owner_account_id", UUID.class), rs.getString("value"))).list();
        }

        public boolean update(UUID id, UUID ownerId, String value) {
            return jdbc.sql("UPDATE job_search_assistant.test_owned_resource SET value = :value "
                            + "WHERE id = :id AND owner_account_id = :ownerId")
                    .param("value", value).param("id", id).param("ownerId", ownerId).update() == 1;
        }

        public boolean delete(UUID id, UUID ownerId) {
            return jdbc.sql("DELETE FROM job_search_assistant.test_owned_resource "
                            + "WHERE id = :id AND owner_account_id = :ownerId")
                    .param("id", id).param("ownerId", ownerId).update() == 1;
        }
    }

    static final class OwnedService {
        private final OwnedRepository repository;
        private final CurrentActorProvider actors;
        OwnedService(OwnedRepository repository, CurrentActorProvider actors) {
            this.repository = repository;
            this.actors = actors;
        }
        OwnedResource create(WriteRequest request) {
            AuthenticatedActor actor = actors.currentActor();
            return repository.insert(UUID.randomUUID(), actor.accountId(), request.value());
        }
        Optional<OwnedResource> find(UUID id) {
            return repository.find(id, actors.currentActor().accountId());
        }
        List<OwnedResource> findAll() { return repository.findAll(actors.currentActor().accountId()); }
        boolean update(UUID id, WriteRequest request) {
            return repository.update(id, actors.currentActor().accountId(), request.value());
        }
        boolean delete(UUID id) { return repository.delete(id, actors.currentActor().accountId()); }
    }

    @RestController
    @RequestMapping("/test/owned-resources")
    static final class OwnedController {
        private final OwnedService service;
        OwnedController(OwnedService service) { this.service = service; }

        @PostMapping ResponseEntity<OwnedResource> create(@RequestBody WriteRequest request) {
            return ResponseEntity.status(201).body(service.create(request));
        }
        @GetMapping("/{id}") ResponseEntity<OwnedResource> find(@PathVariable UUID id) {
            return ResponseEntity.of(service.find(id));
        }
        @GetMapping List<OwnedResource> findAll() { return service.findAll(); }
        @PutMapping("/{id}") ResponseEntity<Void> update(@PathVariable UUID id, @RequestBody WriteRequest request) {
            return service.update(id, request) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        }
        @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable UUID id) {
            return service.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
        }
    }
}
