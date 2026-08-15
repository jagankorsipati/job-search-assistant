package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class InvitationLifecycleTests {

    private static final Instant CREATED = Instant.parse("2026-08-15T10:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-08-16T10:00:00Z");

    @Test
    void pendingInvitationExpiresAfterItsBoundary() {
        InvitationLifecycle invitation = InvitationLifecycle.pending(CREATED, EXPIRES);

        assertThat(invitation.statusAt(EXPIRES.minusNanos(1))).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.statusAt(EXPIRES)).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.statusAt(EXPIRES.plusNanos(1))).isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    void consumptionIsSingleUseAndMustOccurBeforeExpiration() {
        Instant consumptionTime = CREATED.plusSeconds(60);
        InvitationLifecycle consumed = InvitationLifecycle.pending(CREATED, EXPIRES).consumeAt(consumptionTime);

        assertThat(consumed.statusAt(EXPIRES.plusSeconds(1))).isEqualTo(InvitationStatus.CONSUMED);
        assertThat(consumed.consumptionTime()).contains(consumptionTime);
        assertThatIllegalStateException().isThrownBy(() -> consumed.consumeAt(consumptionTime.plusSeconds(1)));
        assertThat(InvitationLifecycle.pending(CREATED, EXPIRES).consumeAt(EXPIRES).statusAt(EXPIRES))
                .isEqualTo(InvitationStatus.CONSUMED);
        assertThatIllegalStateException()
                .isThrownBy(() -> InvitationLifecycle.pending(CREATED, EXPIRES).consumeAt(EXPIRES.plusNanos(1)));
    }

    @Test
    void expirationMustFollowCreation() {
        assertThatIllegalArgumentException().isThrownBy(() -> InvitationLifecycle.pending(CREATED, CREATED));
    }
}
