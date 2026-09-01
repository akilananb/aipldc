package ai.pdlc.core.domain;

/**
 * A board/repo event normalized from a provider-specific webhook payload — tech-stack §4
 * {@code onWebhook(event): CanonicalEvent}. {@code rev} is the provider's revision counter for the
 * item, used for idempotent dedupe (orchestration-decision §5: dedupe by {@code item+rev}).
 */
public record CanonicalEvent(WorkItemRef itemRef, Kind kind, long rev) {

    public enum Kind {
        ITEM_CREATED("item.created"),
        ITEM_UPDATED("item.updated"),
        COMMENT_ADDED("comment.added");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }
}
