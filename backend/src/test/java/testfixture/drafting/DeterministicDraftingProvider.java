package testfixture.drafting;

import java.util.List;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider;

/** Test-only fixture: copies the first selected fact, performs no inference or side effects. */
public final class DeterministicDraftingProvider implements GroundedDraftingProvider {
    @Override public Outcome suggest(Request request) {
        Evidence first = request.evidence().getFirst();
        return new Draft(first.content(), List.of(first.alias()));
    }
}
