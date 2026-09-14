package com.jobsearchassistant.documents;

import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "identity.persistence.enabled", havingValue = "true", matchIfMissing = true)
class ResumeTailoringExportRepository {
    private final JdbcClient jdbc;
    ResumeTailoringExportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    void bind(UUID owner, UUID decision, String revision) {
        if (jdbc.sql("""
                UPDATE job_search_assistant.resume_tailoring_proposal_decision
                SET resolved_target_revision = :revision
                WHERE owner_account_id = :owner AND id = :id AND decision_type = 'APPROVED'
                    AND resolved_target_revision IS NULL
                """).param("owner", owner).param("id", decision).param("revision", revision).update() != 1) {
            throw new ResumeTailoringConflictException("stale_review");
        }
    }

    record DecisionBinding(UUID id, String type, String revision) { }
    Optional<DecisionBinding> latest(UUID owner, UUID proposal) {
        return jdbc.sql("""
                SELECT id, decision_type, resolved_target_revision
                FROM job_search_assistant.resume_tailoring_proposal_decision
                WHERE owner_account_id = :owner AND proposal_id = :proposal
                ORDER BY decided_at DESC, id DESC LIMIT 1
                """).param("owner", owner).param("proposal", proposal)
                .query((rs, row) -> new DecisionBinding(rs.getObject("id", UUID.class),
                        rs.getString("decision_type"), rs.getString("resolved_target_revision"))).optional();
    }
}
