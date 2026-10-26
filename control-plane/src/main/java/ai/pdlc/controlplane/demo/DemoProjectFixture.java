package ai.pdlc.controlplane.demo;

/**
 * Optional manifest section naming the project a demo bundle seeds into. {@code null} (the
 * restaurant bundle) means "the deployment's default project" with area path {@code orders},
 * exactly as before. When present, {@link DemoInitializer} creates the project once if it is
 * missing - a copy of the default project's board/repo/gate config under {@code id}/{@code name},
 * with the primary repo re-labelled {@code repoId} and serving {@code areaPath} - and never
 * overwrites an existing (possibly Admin-edited) row.
 */
public record DemoProjectFixture(
        String id,
        String name,
        String areaPath,
        String repoId,
        String boardProject) {
}
