package ai.pdlc.core.config;

import java.util.Map;
import java.util.Optional;

/**
 * DB-backed directory of {@link Profile}s (projects) — replaces the old startup-only {@code
 * PdlcConfig.profile(name)} singleton lookup. {@code pdlc.yaml}'s {@code profiles} block still
 * seeds the {@code projects} table once at startup (never overwriting an existing row) and
 * supplies deployment-only settings ({@code agents}/{@code notify}) not editable per-project.
 */
public interface ProjectDirectory {

    /** The named project's full runtime {@link Profile}, or throws {@link PdlcConfigException}
     * naming the missing project. */
    Profile project(String id);

    /** Every known project, keyed by id. */
    Map<String, Profile> projects();

    /** The named project's Admin-editable document, or empty if the project does not exist. */
    Optional<ProjectDocument> find(String id);

    /** Upserts the project's document (Admin edit or seed). */
    void save(String id, ProjectDocument doc, String updatedBy);

    /** Deletes the project; returns {@code false} if it did not exist. */
    boolean delete(String id);

    /** Drops any cached {@link Profile}/{@link ProjectDocument} for {@code id} so the next lookup
     * re-reads the database. */
    void invalidate(String id);
}
