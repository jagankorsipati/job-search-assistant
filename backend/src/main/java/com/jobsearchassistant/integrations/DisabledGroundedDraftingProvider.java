package com.jobsearchassistant.integrations;

import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider;
/** Selected by default, without reading credentials or creating an HTTP client. */
final class DisabledGroundedDraftingProvider implements GroundedDraftingProvider {
    @Override public Outcome suggest(Request request) { return Failure.DISABLED; }
}
