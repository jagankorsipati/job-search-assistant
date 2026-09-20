package com.jobsearchassistant.integrations;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import testfixture.drafting.LocalProviderServer;

class DraftingConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(DraftingConfiguration.class);
    private final Request request = new Request(Task.REWORD_PARAGRAPH, "Paragraph", List.of(new Evidence("E1", "Fact")));

    @Test void disabledDefaultDoesNotResolveConfigurationCreateClientOrSendRequests() throws Exception {
        try (var server = new LocalProviderServer()) {
            runner.withPropertyValues("ai.drafting.provider=unknown", "ai.drafting.model=${do-not-resolve}",
                    "ai.drafting.api-key=${do-not-resolve}").withSystemProperties("jdk.httpclient.HttpClient.log=headers,content")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(GroundedDraftingProvider.class);
                        var provider = context.getBean(GroundedDraftingProvider.class);
                        assertThat(provider).isInstanceOf(DisabledGroundedDraftingProvider.class);
                        assertThat(provider.suggest(request)).isEqualTo(Failure.DISABLED);
                        assertThat(context.getBeansOfType(java.net.http.HttpClient.class)).isEmpty();
                    });
            assertThat(server.requests()).isZero();
        }
    }

    @Test void onlyOperatorConfigurationSelectsExactlyOneProviderWithoutDefaultModelOrNetworkCall() {
        for (String id : List.of("openai", "anthropic")) {
            enabled(id).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(GroundedDraftingProvider.class);
                assertThat(context.getBean(GroundedDraftingProvider.class)).isInstanceOf(HttpGroundedDraftingProvider.class);
                assertThat(context.getBean(GroundedDraftingProvider.class).toString()).isEqualTo("HttpGroundedDraftingProvider[redacted]");
                assertThat(context.getBeansOfType(DraftingProtocol.class)).hasSize(2);
            });
        }
        assertThat(new OpenAiDraftingProtocol().endpoint().toString()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(new ClaudeDraftingProtocol().endpoint().toString()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(Request.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("task", "paragraph", "evidence");
    }

    @Test void enabledRequiresValidExplicitSettingsAndErrorsNeverEchoCredentials() {
        for (String property : List.of("ai.drafting.provider=", "ai.drafting.provider=arbitrary-url",
                "ai.drafting.model=", "ai.drafting.model=private-setting/unsafe", "ai.drafting.api-key=",
                "ai.drafting.api-key=private-setting\r\nInjected: header", "ai.drafting.enabled=private-setting",
                "ai.drafting.api-key=${private-setting}")) {
            enabled("openai").withPropertyValues(property).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage("invalid_ai_drafting_configuration");
                assertThat(context.getStartupFailure()).hasStackTraceContaining("invalid_ai_drafting_configuration")
                        .hasStackTraceContaining("DraftingConfiguration");
                assertThat(stack(context.getStartupFailure())).doesNotContain("private-setting", "synthetic-test-credential", "Injected");
            });
        }
        enabled("openai").withBean("duplicateProtocol", DraftingProtocol.class, OpenAiDraftingProtocol::new)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void unsafeRetryDiagnosticAndTlsSettingsRefuseEnabledStartup() {
        for (String property : List.of("jdk.httpclient.disableRetryConnect=false", "jdk.httpclient.enableAllMethodRetry=true",
                "jdk.httpclient.HttpClient.log=headers,content", "javax.net.debug=all", "jdk.internal.httpclient.debug=true",
                "jdk.internal.httpclient.disableHostnameVerification=true")) {
            enabled("anthropic").withSystemProperties(property).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage("invalid_ai_drafting_configuration");
            });
        }
    }

    @Test void reviewedProtocolCanBeRegisteredWithoutChangingTheDomainContractOrSelector() throws Exception {
        DraftingProtocol extension = org.mockito.Mockito.mock(DraftingProtocol.class);
        org.mockito.Mockito.when(extension.id()).thenReturn("extension-test");
        org.mockito.Mockito.when(extension.endpoint()).thenReturn(java.net.URI.create("https://synthetic.invalid/drafting"));
        enabled("extension-test").withBean("extensionProtocol", DraftingProtocol.class, () -> extension).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GroundedDraftingProvider.class);
            assertThat(context.getBean(GroundedDraftingProvider.class)).isInstanceOf(HttpGroundedDraftingProvider.class);
        });
        org.mockito.Mockito.verify(extension, org.mockito.Mockito.never()).encode(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private ApplicationContextRunner enabled(String provider) {
        return runner.withSystemProperties("jdk.httpclient.disableRetryConnect=true", "jdk.httpclient.enableAllMethodRetry=false")
                .withPropertyValues("ai.drafting.enabled=true", "ai.drafting.provider=" + provider,
                        "ai.drafting.model=synthetic-model", "ai.drafting.api-key=synthetic-test-credential");
    }

    private static String stack(Throwable exception) {
        var text = new java.io.StringWriter();
        exception.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }
}
