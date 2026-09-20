package com.jobsearchassistant.integrations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.*;

final class ClaudeDraftingProtocol implements DraftingProtocol {
    public String id() { return "anthropic"; }
    public URI endpoint() { return URI.create("https://api.anthropic.com/v1/messages"); }
    public void authenticate(HttpRequest.Builder builder, String credential) {
        builder.header("x-api-key", credential).header("anthropic-version", "2023-06-01");
    }

    public JsonNode encode(Request request, String model) throws IOException {
        var body = DraftingJson.MAPPER.createObjectNode().put("model", model)
                .put("system", DraftingJson.instructions(request)).put("stream", false).put("max_tokens", 4096);
        body.putArray("messages").addObject().put("role", "user").put("content", DraftingJson.input(request));
        body.putObject("output_config").putObject("format").put("type", "json_schema").set("schema", DraftingJson.schema());
        return body;
    }

    public Outcome decode(JsonNode body, Request request) throws IOException {
        if (!"message".equals(body.path("type").asText()) || !"assistant".equals(body.path("role").asText())) {
            return Failure.INVALID_RESPONSE;
        }
        if ("refusal".equals(body.path("stop_reason").asText())) return Failure.REFUSED;
        if (!"end_turn".equals(body.path("stop_reason").asText()) || !DraftingJson.absentOrNull(body, "stop_sequence")
                || !DraftingJson.absentOrNull(body, "container") || !body.path("content").isArray()
                || body.get("content").size() != 1) return Failure.INVALID_RESPONSE;
        JsonNode content = body.get("content").get(0);
        if (!DraftingJson.fields(content, Set.of("type", "text", "citations"))
                || !"text".equals(content.path("type").asText()) || !content.path("text").isTextual()
                || (content.hasNonNull("citations") && (!content.get("citations").isArray() || !content.get("citations").isEmpty()))) {
            return Failure.INVALID_RESPONSE;
        }
        return DraftingJson.draft(content.get("text").textValue(), request);
    }
}
