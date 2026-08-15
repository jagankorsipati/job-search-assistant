package com.jobsearchassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class InvitationControllerTests {
    @Test void acceptanceLimitRejectsBeforePasswordCheckLookupAndArgonWork() {
        InvitationAcceptanceService acceptance = mock(InvitationAcceptanceService.class);
        when(acceptance.accept(any(), any(), any(), any())).thenThrow(new InvitationRejectedException());
        CompromisedPasswordChecker checker = mock(CompromisedPasswordChecker.class);
        var limiter = new InvitationRequestRateLimiter(Clock.systemUTC(), 10, 1, Duration.ofMinutes(1), 20);
        var controller = new InvitationController(mock(InvitationCreationService.class), acceptance, checker,
                limiter, new TokenDigester(), mock(AuthenticationAuditService.class));
        var body = new InvitationController.AcceptanceRequest("token", "member", "Member",
                "long unique passphrase".toCharArray());
        MockHttpServletRequest request = new MockHttpServletRequest(); request.setRemoteAddr("203.0.113.2");
        assertThat(controller.accept(body, request).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.accept(body, request).getStatusCode().value()).isEqualTo(429);
        verify(checker, times(1)).validate(any(), any(), any());
        verify(acceptance, times(1)).accept(any(), any(), any(), any());
    }
}
