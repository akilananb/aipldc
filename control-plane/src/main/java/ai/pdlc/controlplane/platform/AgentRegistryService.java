package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.ModelCatalog;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.AgentRegistryStore.AgentRow;
import ai.pdlc.controlplane.platform.AgentRegistryStore.Status;
import ai.pdlc.controlplane.platform.AgentRegistryStore.VersionRow;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.AgentDefinitionDto;
import ai.pdlc.controlplane.web.dto.AgentDraftRequest;
import ai.pdlc.controlplane.web.dto.AgentValidationDto;
import ai.pdlc.controlplane.web.dto.AgentVersionDto;
import ai.pdlc.controlplane.web.dto.ResolvedAgentDto;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.AgentSpecValidator;
import ai.pdlc.core.platform.ContentHash;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The AgentDefinition registry (configurable-agent-platform.md §2, phase 1 slice 1). Lifecycle:
 * draft → validated → published immutable version → retired.
 *
 * <ul>
 *   <li>Drafts may be incomplete; every save names the revision it was based on, and a stale
 *       revision is a 409 instead of a silent overwrite.</li>
 *   <li>Publishing validates the draft against {@link AgentSpecValidator} and the
 *       {@link ModelCatalog}, then appends version N+1 with its {@link ContentHash} and makes it
 *       the version new runs resolve. Published versions are never updated or deleted.</li>
 *   <li>Rollback points new runs at an earlier published version; it does not create a version.</li>
 *   <li>Retiring stops new runs and edits; existing versions stay readable for provenance.</li>
 * </ul>
 *
 * Every entry point first goes through {@link WorkspaceService#require}, so a non-member cannot
 * distinguish another workspace's agent from a missing one.
 */
@Service
public class AgentRegistryService {

    private final AgentRegistryStore store;
    private final WorkspaceService workspaces;
    private final ModelCatalog models;

    public AgentRegistryService(AgentRegistryStore store, WorkspaceService workspaces, ModelCatalog models) {
        this.store = store;
        this.workspaces = workspaces;
        this.models = models;
    }

    public List<AgentDefinitionDto> list(String workspaceId, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return store.list(workspaceId).stream().map(this::toDto).toList();
    }

    public AgentDefinitionDto get(String workspaceId, String id, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return toDto(find(workspaceId, id));
    }

    @Transactional
    public AgentDefinitionDto create(String workspaceId, AgentDraftRequest request, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        if (request.id() == null || !WorkspaceService.ID.matcher(request.id()).matches()) {
            throw new IllegalArgumentException("id must match " + WorkspaceService.ID.pattern());
        }
        requireName(request.name());
        if (!store.insert(workspaceId, request.id(), request.name(), ContentHash.canonicalJson(request.spec()), identity.user())) {
            throw new ConflictException("Agent " + request.id() + " already exists in " + workspaceId);
        }
        return toDto(find(workspaceId, request.id()));
    }

    @Transactional
    public AgentDefinitionDto saveDraft(String workspaceId, String id, AgentDraftRequest request, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        AgentRow row = requireActive(find(workspaceId, id));
        requireName(request.name());
        int revision = requireRevision(request.revision());
        if (!store.updateDraft(workspaceId, id, revision, request.name(), ContentHash.canonicalJson(request.spec()), identity.user())) {
            throw staleDraft(row);
        }
        return toDto(find(workspaceId, id));
    }

    public AgentValidationDto validate(String workspaceId, String id, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        AgentRow row = find(workspaceId, id);
        AgentSpec spec = ContentHash.read(row.draftSpecJson(), AgentSpec.class);
        List<String> errors = AgentSpecValidator.validate(row.draftName(), spec, models.authorizedModels());
        String hash = errors.isEmpty() ? ContentHash.ofAgent(row.draftName(), spec) : null;
        return new AgentValidationDto(errors.isEmpty(), errors, hash, row.draftRevision());
    }

    @Transactional
    public AgentVersionDto publish(String workspaceId, String id, Integer revision, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        AgentRow row = requireActive(find(workspaceId, id));
        if (row.draftRevision() != requireRevision(revision)) {
            throw staleDraft(row);
        }
        AgentSpec spec = ContentHash.read(row.draftSpecJson(), AgentSpec.class);
        List<String> errors = AgentSpecValidator.validate(row.draftName(), spec, models.authorizedModels());
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        String hash = ContentHash.ofAgent(row.draftName(), spec);
        List<VersionRow> versions = store.versions(workspaceId, id);
        VersionRow latest = versions.isEmpty() ? null : versions.get(versions.size() - 1);
        if (latest != null && latest.contentHash().equals(hash)) {
            throw new ConflictException("Draft is unchanged since published version " + latest.version());
        }
        int next = latest == null ? 1 : latest.version() + 1;
        VersionRow version = new VersionRow(workspaceId, id, next, row.draftName(),
                ContentHash.canonicalJson(spec), hash, null, identity.user());
        if (!store.insertVersion(version)) {
            throw new ConflictException("Agent " + id + " was published concurrently; reload and retry");
        }
        store.setCurrentVersion(workspaceId, id, next, identity.user());
        return toDto(store.version(workspaceId, id, next).orElseThrow());
    }

    public List<AgentVersionDto> versions(String workspaceId, String id, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        find(workspaceId, id);
        return store.versions(workspaceId, id).stream().map(AgentRegistryService::toDto).toList();
    }

    public AgentVersionDto version(String workspaceId, String id, int version, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return toDto(findVersion(workspaceId, id, version));
    }

    @Transactional
    public AgentDefinitionDto rollback(String workspaceId, String id, Integer version, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        requireActive(find(workspaceId, id));
        if (version == null) {
            throw new IllegalArgumentException("version is required");
        }
        findVersion(workspaceId, id, version);
        store.setCurrentVersion(workspaceId, id, version, identity.user());
        return toDto(find(workspaceId, id));
    }

    @Transactional
    public AgentDefinitionDto retire(String workspaceId, String id, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        requireActive(find(workspaceId, id));
        store.setStatus(workspaceId, id, Status.RETIRED, identity.user());
        return toDto(find(workspaceId, id));
    }

    /**
     * What a new run pins: the current published version of an active agent - its stored content
     * re-hashed so a tampered row fails loudly instead of running - and the model it will call
     * now. A disabled model or a revoked/expired connection fails here with the reason, unless the
     * agent declared an available fallback; there is no silent default model.
     */
    public ResolvedAgentDto resolveForRun(String workspaceId, String id, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.OPERATOR);
        AgentRow row = requireActive(find(workspaceId, id));
        if (row.currentVersion() == null) {
            throw new ConflictException("Agent " + id + " has no published version");
        }
        VersionRow version = findVersion(workspaceId, id, row.currentVersion());
        AgentSpec spec = ContentHash.read(version.specJson(), AgentSpec.class);
        if (!ContentHash.ofAgent(version.name(), spec).equals(version.contentHash())) {
            throw new IllegalStateException("Agent " + id + " v" + version.version() + " content does not match its hash");
        }
        ModelCatalog.ResolvedModel model = models.resolve(spec.model());
        return new ResolvedAgentDto(toDto(version), model.model(), model.providerModel(), model.connectionId(), model.fallback());
    }

    private AgentRow find(String workspaceId, String id) {
        return store.find(workspaceId, id).orElseThrow(() -> new NotFoundException("No agent " + id + " in " + workspaceId));
    }

    private VersionRow findVersion(String workspaceId, String id, int version) {
        return store.version(workspaceId, id, version)
                .orElseThrow(() -> new NotFoundException("No version " + version + " of agent " + id + " in " + workspaceId));
    }

    private static AgentRow requireActive(AgentRow row) {
        if (row.status() == Status.RETIRED) {
            throw new ConflictException("Agent " + row.id() + " is retired");
        }
        return row;
    }

    private static int requireRevision(Integer revision) {
        if (revision == null) {
            throw new IllegalArgumentException("revision is required");
        }
        return revision;
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
    }

    private ConflictException staleDraft(AgentRow row) {
        int current = store.find(row.workspaceId(), row.id()).map(AgentRow::draftRevision).orElse(row.draftRevision());
        return new ConflictException("Draft of agent " + row.id() + " is at revision " + current
                + "; reload before saving or publishing");
    }

    private AgentDefinitionDto toDto(AgentRow row) {
        List<VersionRow> versions = store.versions(row.workspaceId(), row.id());
        Integer latest = versions.isEmpty() ? null : versions.get(versions.size() - 1).version();
        return new AgentDefinitionDto(row.workspaceId(), row.id(), row.status().name(), row.draftName(),
                ContentHash.read(row.draftSpecJson(), AgentSpec.class), row.draftRevision(), row.currentVersion(),
                latest, row.updatedAt(), row.updatedBy());
    }

    private static AgentVersionDto toDto(VersionRow row) {
        return new AgentVersionDto(row.workspaceId(), row.agentId(), row.version(), row.name(),
                ContentHash.read(row.specJson(), AgentSpec.class), row.contentHash(), row.publishedAt(), row.publishedBy());
    }
}
