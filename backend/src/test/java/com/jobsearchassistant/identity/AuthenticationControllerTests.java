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
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticationControllerTests {
    @Test
    void sourceRateLimitRunsBeforeCredentialVerificationAndIgnoresForwardedHeader() {
        CredentialVerificationService credentials = mock(CredentialVerificationService.class);
        when(credentials.verifyForSession(any(), any())).thenThrow(new AuthenticationFailedException());
        LoginRateLimiter limiter = new LoginRateLimiter(Clock.systemUTC(), 1, 10, Duration.ofMinutes(5), 20);
        AuthenticationAuditService audit = mock(AuthenticationAuditService.class);
        AuthenticationController controller = new AuthenticationController(credentials, limiter, audit, true);

        MockHttpServletRequest first = request("203.0.113.8", "198.51.100.1");
        assertThat(controller.login(new AuthenticationController.LoginRequest("known", "wrong passphrase".toCharArray()),
                first, new MockHttpServletResponse()).getStatusCode().value()).isEqualTo(401);

        MockHttpServletRequest second = request("203.0.113.8", "192.0.2.44");
        var rejected = controller.login(
                new AuthenticationController.LoginRequest("unknown", "another passphrase".toCharArray()),
                second, new MockHttpServletResponse());
        assertThat(rejected.getStatusCode().value()).isEqualTo(429);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotBlank();
        verify(credentials, times(1)).verifyForSession(any(), any());
        verify(audit).record("LOGIN", "RATE_LIMITED", null);
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedAddress);
        return request;
    }
}
