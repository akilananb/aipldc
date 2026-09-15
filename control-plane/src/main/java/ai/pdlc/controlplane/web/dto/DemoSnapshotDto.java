package ai.pdlc.controlplane.web.dto;

/** Trailing {@code snapshot} field on {@link ItemSummaryDto}/{@link ItemDetailDto}: non-null only
 * for a curated restaurant-demo catalog item. Mirrors {@code ui/src/types.ts}'s {@code
 * DemoSnapshot} field-for-field. */
public record DemoSnapshotDto(String key, String label, int order, String sourceRef, boolean replay) {
}
