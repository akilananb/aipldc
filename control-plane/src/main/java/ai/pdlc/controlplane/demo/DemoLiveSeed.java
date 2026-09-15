package ai.pdlc.controlplane.demo;

/**
 * The separate, non-snapshot live feature seeded as {@code demo-live} ({@code kind=feature},
 * {@code state=new}). It is the real walkthrough target; snapshot seeding never touches it.
 */
public record DemoLiveSeed(
        String boardId,
        String title,
        String description,
        String areaPath,
        String resolvedAnswer) {
}
