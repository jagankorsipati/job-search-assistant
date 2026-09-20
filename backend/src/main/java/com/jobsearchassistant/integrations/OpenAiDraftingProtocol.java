package com.jobsearchassistant.integrations;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.*;

final class OpenAiDraftingProtocol implements DraftingProtocol {
    public String id() { return "openai"; }
    public URI endpoint() { return URI.create("https://api.openai.com/v1/responses"); }
    public void authenticate(HttpRequest.Builder builder, String credential) {
        builder.header("Authorization", "Bearer " + credential);
    }

    public JsonNode encode(Request request, String model) throws IOException {
        var body = DraftingJson.MAPPER.createObjectNode().put("model", model)
                .put("instructions", DraftingJson.instructions(request))
                .put("store", false).put("stream", false).put("background", false)
                .put("max_output_tokens", 4096).put("truncation", "disabled");
        body.putArray("input").addObject().put("role", "user").put("content", DraftingJson.input(request));
        body.putObject("text").putObject("format").put("type", "json_schema")
                .put("name", "grounded_paragraph").put("strict", true).set("schema", DraftingJson.schema());
        return body;
    }

    public Outcome decode(JsonNode body, Request request) throws IOException {
        if (!"response".equals(body.path("object").asText())) return Failure.INVALID_RESPONSE;
        if ("failed".equals(body.path("status").asText())) return Failure.UNAVAILABLE;
        if ("cancelled".equals(body.path("status").asText())) return Failure.CANCELLED;
        if (!"completed".equals(body.path("status").asText())
                || !DraftingJson.absentOrNull(body, "error") || !DraftingJson.absentOrNull(body, "incomplete_details")) {
            return Failure.INVALID_RESPONSE;
        }
        JsonNode output = body.path("output");
        if (!output.isArray() || output.isEmpty() || output.size() > 2) return Failure.INVALID_RESPONSE;
        int index = 0;
        if (output.size() == 2) {
            // Some reasoning models emit an empty reasoning marker before their answer. Never use it as text.
            JsonNode reasoning = output.get(0);
            if (!DraftingJson.fields(reasoning, Set.of("id", "type", "summary", "status"))
                    || !"reasoning".equals(reasoning.path("type").asText())
                    || !reasoning.path("summary").isArray() || !reasoning.path("summary").isEmpty()
                    || (reasoning.hasNonNull("status") && !"completed".equals(reasoning.path("status").asText()))) {
                return Failure.INVALID_RESPONSE;
            }
            index = 1;
        }
        JsonNode message = output.get(index);
        if (!DraftingJson.fields(message, Set.of("id", "type", "status", "role", "content"))
                || !"message".equals(message.path("type").asText())
                || !"assistant".equals(message.path("role").asText())
                || !"completed".equals(message.path("status").asText())
                || !message.path("content").isArray() || message.get("content").size() != 1) return Failure.INVALID_RESPONSE;
        JsonNode content = message.get("content").get(0);
        if ("refusal".equals(content.path("type").asText())) return Failure.REFUSED;
        if (!DraftingJson.fields(content, Set.of("type", "text", "annotations", "logprobs"))
                || !"output_text".equals(content.path("type").asText()) || !content.path("text").isTextual()
                || (content.has("annotations") && (!content.get("annotations").isArray() || !content.get("annotations").isEmpty()))
                || (content.hasNonNull("logprobs") && (!content.get("logprobs").isArray() || !content.get("logprobs").isEmpty()))) {
            return Failure.INVALID_RESPONSE;
        }
        return DraftingJson.draft(content.get("text").textValue(), request);
    }
}
