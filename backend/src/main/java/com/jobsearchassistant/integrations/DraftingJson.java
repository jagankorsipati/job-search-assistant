package com.jobsearchassistant.integrations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.*;

final class DraftingJson {
    private DraftingJson() {}
    static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(128 * 1024).maxNumberLength(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    static ObjectNode schema() {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("text").add("evidenceAliases");
        ObjectNode fields = schema.putObject("properties");
        fields.putObject("text").put("type", "string");
        fields.putObject("evidenceAliases").put("type", "array").putObject("items").put("type", "string");
        // Use the common supported schema subset; all lengths/aliases are enforced locally.
        return schema;
    }

    static String instructions(Request request) {
        return request.task().instructions() + " Return only JSON with text and evidenceAliases. "
                + "Draft text must be at most 4000 UTF-16 code units; cite only the supplied aliases.";
    }

    static String input(Request request) throws IOException {
        // Deliberately serialize ONLY the immutable Phase 7A DTO, never a domain record/context.
        return MAPPER.writeValueAsString(request);
    }

    static Outcome draft(String text, Request request) throws IOException {
        JsonNode json = MAPPER.readTree(text);
        if (!fields(json, Set.of("text", "evidenceAliases")) || json.size() != 2
                || !json.path("text").isTextual() || !json.path("evidenceAliases").isArray()
                || json.path("evidenceAliases").size() > GroundedDraftingProvider.FACT_COUNT_MAX) {
            return Failure.INVALID_RESPONSE;
        }
        var aliases = new ArrayList<String>();
        for (JsonNode alias : json.get("evidenceAliases")) {
            if (!alias.isTextual()) return Failure.INVALID_RESPONSE;
            aliases.add(alias.textValue());
        }
        return GroundedDraftingProvider.validate(request, new Draft(json.get("text").textValue(), aliases));
    }

    static boolean fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    static boolean absentOrNull(JsonNode node, String name) { return !node.hasNonNull(name); }
}
