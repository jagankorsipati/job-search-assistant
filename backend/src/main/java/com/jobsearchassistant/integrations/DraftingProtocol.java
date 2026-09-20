package com.jobsearchassistant.integrations;

import java.net.URI;
import java.net.http.HttpRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.Outcome;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider.Request;

/** Internal extension point: each reviewed protocol owns one fixed HTTPS destination. */
interface DraftingProtocol {
    String id();
    URI endpoint();
    void authenticate(HttpRequest.Builder builder, String credential);
    JsonNode encode(Request request, String model) throws java.io.IOException;
    Outcome decode(JsonNode body, Request request) throws java.io.IOException;
}
