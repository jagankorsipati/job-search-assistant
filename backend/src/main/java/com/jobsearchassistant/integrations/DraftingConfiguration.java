package com.jobsearchassistant.integrations;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Administrator/operator process configuration only. There is no per-user or HTTP settings boundary. */
@Configuration(proxyBeanMethods = false)
class DraftingConfiguration {
    @Bean DraftingProtocol openAiDraftingProtocol() { return new OpenAiDraftingProtocol(); }
    @Bean DraftingProtocol claudeDraftingProtocol() { return new ClaudeDraftingProtocol(); }

    @Bean
    GroundedDraftingProvider groundedDraftingProvider(Environment environment, List<DraftingProtocol> protocols) {
        try {
            return configuredProvider(environment, protocols);
        } catch (RuntimeException invalidConfiguration) {
            // Property resolution can itself include raw settings in its exception. Do not chain it.
            throw invalid();
        }
    }

    private GroundedDraftingProvider configuredProvider(Environment environment, List<DraftingProtocol> protocols) {
        String enabled = environment.getProperty("ai.drafting.enabled", "false");
        if ("false".equals(enabled)) return new DisabledGroundedDraftingProvider();
        if (!"true".equals(enabled)) throw invalid();
        String selected = environment.getProperty("ai.drafting.provider", "");
        var matching = protocols.stream().filter(protocol -> protocol.id().equals(selected)).toList();
        String model = environment.getProperty("ai.drafting.model", "");
        String credential = environment.getProperty("ai.drafting.api-key", "");
        if (matching.size() != 1 || !model.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || !credential.matches("[!-~]{1,4096}")) throw invalid();
        // JDK transport policies are JVM startup flags: do not mutate global state or enable payload diagnostics.
        if (!"true".equals(System.getProperty("jdk.httpclient.disableRetryConnect"))
                || Boolean.parseBoolean(System.getProperty("jdk.httpclient.enableAllMethodRetry", "false"))
                || !System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()
                || !System.getProperty("jdk.internal.httpclient.debug", "").isBlank()
                || !System.getProperty("javax.net.debug", "").isBlank()
                || Boolean.parseBoolean(System.getProperty("jdk.internal.httpclient.disableHostnameVerification", "false"))) {
            throw invalid();
        }
        DraftingProtocol protocol = matching.getFirst();
        if (!"https".equals(protocol.endpoint().getScheme()) || protocol.endpoint().getRawUserInfo() != null
                || protocol.endpoint().getRawQuery() != null || protocol.endpoint().getRawFragment() != null) throw invalid();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(ProxySelector.of(null))
                .version(HttpClient.Version.HTTP_1_1).build();
        return new HttpGroundedDraftingProvider(protocol, model, credential, client, GroundedDraftingProvider.TIMEOUT);
    }

    private static IllegalStateException invalid() {
        // Never echo settings, credentials, model strings or nested property-conversion exceptions.
        return new IllegalStateException("invalid_ai_drafting_configuration");
    }
}
