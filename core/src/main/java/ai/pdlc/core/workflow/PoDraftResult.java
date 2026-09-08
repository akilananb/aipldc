package ai.pdlc.core.workflow;

import ai.pdlc.core.domain.GrillQuestion;

import java.util.List;

/**
 * Result of {@code poDraft} — either drafted {@link StoryDraft}s or follow-up questions the PO
 * agent needs answered before it can draft (playbook §2). At most one of the two is populated.
 *
 * @param drafts    the story/spec-delta drafts; empty when {@link #needsClarification()}
 * @param followUps questions to append to the grill list with ids {@code po1, po2, …}
 */
public record PoDraftResult(List<StoryDraft> drafts, List<GrillQuestion> followUps) {
    public PoDraftResult {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
    }

    /** True when the PO agent asked instead of drafting — {@code drafts} is then empty and ignored. */
    public boolean needsClarification() {
        return !followUps.isEmpty();
    }
}
