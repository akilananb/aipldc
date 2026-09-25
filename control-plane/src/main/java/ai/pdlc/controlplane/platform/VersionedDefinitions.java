package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.platform.DefinitionStore.DefinitionRow;
import ai.pdlc.controlplane.platform.DefinitionStore.Status;
import ai.pdlc.controlplane.platform.DefinitionStore.VersionRow;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.core.platform.ContentHash;

import java.util.List;
import java.util.function.BiFunction;

/**
 * The draft → publish → rollback → retire mechanics shared by every versioned registry (agents,
 * tools): optimistic draft revisions (stale = 409), immutable hashed versions, and hash
 * re-verification on read for execution. Authorization and kind-specific validation stay in the
 * owning service; this class only enforces the lifecycle.
 */
final class VersionedDefinitions<S> {

    private final DefinitionStore store;
    private final String noun;
    private final Class<S> specType;
    private final BiFunction<String, S, String> hasher;

    VersionedDefinitions(DefinitionStore store, String noun, Class<S> specType, BiFunction<String, S, String> hasher) {
        this.store = store;
        this.noun = noun;
        this.specType = specType;
        this.hasher = hasher;
    }

    DefinitionStore store() {
        return store;
    }

    S spec(String json) {
        return ContentHash.read(json, specType);
    }

    String hash(String name, S spec) {
        return hasher.apply(name, spec);
    }

    DefinitionRow find(String workspaceId, String id) {
        return store.find(workspaceId, id)
                .orElseThrow(() -> new NotFoundException("No " + lower() + " " + id + " in " + workspaceId));
    }

    VersionRow findVersion(String workspaceId, String id, int version) {
        return store.version(workspaceId, id, version)
                .orElseThrow(() -> new NotFoundException("No version " + version + " of " + lower() + " " + id + " in " + workspaceId));
    }

    DefinitionRow requireActive(DefinitionRow row) {
        if (row.status() == Status.RETIRED) {
            throw new ConflictException(noun + " " + row.id() + " is retired");
        }
        return row;
    }

    DefinitionRow create(String workspaceId, String id, String name, S spec, String user) {
        if (id == null || !WorkspaceService.ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must match " + WorkspaceService.ID.pattern());
        }
        requireName(name);
        if (!store.insert(workspaceId, id, name, ContentHash.canonicalJson(spec), user)) {
            throw new ConflictException(noun + " " + id + " already exists in " + workspaceId);
        }
        return find(workspaceId, id);
    }

    DefinitionRow saveDraft(String workspaceId, String id, Integer revision, String name, S spec, String user) {
        DefinitionRow row = requireActive(find(workspaceId, id));
        requireName(name);
        if (!store.updateDraft(workspaceId, id, requireRevision(revision), name, ContentHash.canonicalJson(spec), user)) {
            throw staleDraft(row);
        }
        return find(workspaceId, id);
    }

    /**
     * Publishes the draft at {@code revision} as version N+1 when {@code validator} finds nothing;
     * rejects a stale revision, invalid content, and a draft identical to the latest version.
     */
    VersionRow publish(String workspaceId, String id, Integer revision, String user,
                       BiFunction<String, S, List<String>> validator) {
        DefinitionRow row = requireActive(find(workspaceId, id));
        if (row.draftRevision() != requireRevision(revision)) {
            throw staleDraft(row);
        }
        S spec = spec(row.draftSpecJson());
        List<String> errors = validator.apply(row.draftName(), spec);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        String hash = hash(row.draftName(), spec);
        List<VersionRow> versions = store.versions(workspaceId, id);
        VersionRow latest = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        if (latest != null && latest.contentHash().equals(hash)) {
            throw new ConflictException("Draft is unchanged since published version " + latest.version());
        }
        int next = latest == null ? 1 : latest.version() + 1;
        if (!store.insertVersion(new VersionRow(workspaceId, id, next, row.draftName(), ContentHash.canonicalJson(spec),
                hash, null, user))) {
            throw new ConflictException(noun + " " + id + " was published concurrently; reload and retry");
        }
        store.setCurrentVersion(workspaceId, id, next, user);
        return findVersion(workspaceId, id, next);
    }

    DefinitionRow rollback(String workspaceId, String id, Integer version, String user) {
        requireActive(find(workspaceId, id));
        if (version == null) {
            throw new IllegalArgumentException("version is required");
        }
        findVersion(workspaceId, id, version);
        store.setCurrentVersion(workspaceId, id, version, user);
        return find(workspaceId, id);
    }

    DefinitionRow retire(String workspaceId, String id, String user) {
        requireActive(find(workspaceId, id));
        store.setStatus(workspaceId, id, Status.RETIRED, user);
        return find(workspaceId, id);
    }

    /** A stored version whose content still matches its hash; a tampered row fails loudly. */
    VersionRow verified(VersionRow version) {
        S spec = spec(version.specJson());
        if (!hash(version.name(), spec).equals(version.contentHash())) {
            throw new IllegalStateException(noun + " " + version.definitionId() + " v" + version.version()
                    + " content does not match its hash");
        }
        return version;
    }

    Integer latestVersion(String workspaceId, String id) {
        List<VersionRow> versions = store.versions(workspaceId, id);
        return versions.isEmpty() ? null : versions.get(versions.size() - 1).version();
    }

    private ConflictException staleDraft(DefinitionRow row) {
        int current = store.find(row.workspaceId(), row.id()).map(DefinitionRow::draftRevision).orElse(row.draftRevision());
        return new ConflictException("Draft of " + lower() + " " + row.id() + " is at revision " + current
                + "; reload before saving or publishing");
    }

    private String lower() {
        return noun.toLowerCase();
    }

    static int requireRevision(Integer revision) {
        if (revision == null) {
            throw new IllegalArgumentException("revision is required");
        }
        return revision;
    }

    static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }
}
