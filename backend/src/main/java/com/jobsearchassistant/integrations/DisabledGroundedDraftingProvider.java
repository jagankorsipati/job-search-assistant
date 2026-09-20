package com.jobsearchassistant.integrations;

import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider;
import org.springframework.stereotype.Component;

/** The only runtime implementation. There is deliberately no enable switch. */
@Component
final class DisabledGroundedDraftingProvider implements GroundedDraftingProvider {
    @Override public Outcome suggest(Request request) { return Failure.DISABLED; }
}
