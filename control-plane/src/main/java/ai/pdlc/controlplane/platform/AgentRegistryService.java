package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.ConnectionService;
import ai.pdlc.controlplane.connections.ModelCatalog;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.DefinitionStore.DefinitionRow;
import ai.pdlc.controlplane.platform.DefinitionStore.VersionRow;
import ai.pdlc.controlplane.web.ConflictException;
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

import java.util.ArrayList;
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
 * Agents that pin tools (Phase 2 slice 2.1) are publishable and runnable only while every pinned
 * tool version is usable in the workspace ({@link ToolRegistryService#pinProblems}).
 *
 * <p>Every entry point first goes through {@link WorkspaceService#require}, so a non-member cannot
 * distinguish another workspace's agent from a missing one.
 */
@Service
public class AgentRegistryService {

    private final VersionedDefinitions<AgentSpec> registry;
    private final AgentRegistryStore store;
    private final WorkspaceService workspaces;
    private final ModelCatalog models;
    private final ToolRegistryService tools;
    private final ConnectionService connections;

    public AgentRegistryService(AgentRegistryStore store, WorkspaceService workspaces, ModelCatalog models,
                                ToolRegistryService tools, ConnectionService connections) {
        this.registry = new VersionedDefinitions<>(store, "Agent", AgentSpec.class, ContentHash::ofAgent);
        this.store = store;
        this.workspaces = workspaces;
        this.models = models;
        this.tools = tools;
        this.connections = connections;
    }

    public List<AgentDefinitionDto> list(String workspaceId, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return store.list(workspaceId).stream().map(this::toDto).toList();
    }

    public AgentDefinitionDto get(String workspaceId, String id, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return toDto(registry.find(workspaceId, id));
    }

    @Transactional
    public AgentDefinitionDto create(String workspaceId, AgentDraftRequest request, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        return toDto(registry.create(workspaceId, request.id(), request.name(), request.spec(), identity.user()));
    }

    @Transactional
    public AgentDefinitionDto saveDraft(String workspaceId, String id, AgentDraftRequest request, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        return toDto(registry.saveDraft(workspaceId, id, request.revision(), request.name(), request.spec(), identity.user()));
    }

    public AgentValidationDto validate(String workspaceId, String id, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        DefinitionRow row = registry.find(workspaceId, id);
        AgentSpec spec = registry.spec(row.draftSpecJson());
        List<String> errors = check(workspaceId, row.draftName(), spec);
        String hash = errors.isEmpty() ? ContentHash.ofAgent(row.draftName(), spec) : null;
        return new AgentValidationDto(errors.isEmpty(), errors, hash, row.draftRevision());
    }

    @Transactional
    public AgentVersionDto publish(String workspaceId, String id, Integer revision, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        return toDto(registry.publish(workspaceId, id, revision, identity.user(),
                (name, spec) -> check(workspaceId, name, spec)));
    }

    public List<AgentVersionDto> versions(String workspaceId, String id, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        registry.find(workspaceId, id);
        return store.versions(workspaceId, id).stream().map(AgentRegistryService::toDto).toList();
    }

    public AgentVersionDto version(String workspaceId, String id, int version, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return toDto(registry.findVersion(workspaceId, id, version));
    }

    @Transactional
    public AgentDefinitionDto rollback(String workspaceId, String id, Integer version, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        return toDto(registry.rollback(workspaceId, id, version, identity.user()));
    }

    @Transactional
    public AgentDefinitionDto retire(String workspaceId, String id, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        return toDto(registry.retire(workspaceId, id, identity.user()));
    }

    /**
     * What a new run pins: the current published version of an active agent - its stored content
     * re-hashed so a tampered row fails loudly instead of running - and the model it will call
     * now. A disabled model or a revoked/expired connection fails here with the reason, unless the
     * agent declared an available fallback; there is no silent default model. A pinned tool that
     * is no longer usable (retired, connection revoked or ungranted) blocks the run up front. An
     * {@code a2a} or {@code rest} agent (slices 2.5, 2.6) has no model: its {@code A2A_AGENT} or
     * {@code REST_AGENT} connection must be usable by the workspace, and the run pins that connection instead.
     */
    public ResolvedAgentDto resolveForRun(String workspaceId, String id, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.OPERATOR);
        DefinitionRow row = registry.requireActive(registry.find(workspaceId, id));
        if (row.currentVersion() == null) {
            throw new ConflictException("Agent " + id + " has no published version");
        }
        VersionRow version = registry.verified(registry.findVersion(workspaceId, id, row.currentVersion()));
        AgentSpec spec = registry.spec(version.specJson());
        if (spec.isA2a() || spec.usesRestRuntime()) {
            List<String> problems = remoteProblems(workspaceId, spec);
            if (!problems.isEmpty()) {
                throw new ConflictException("Agent " + id + " v" + version.version() + " cannot run: " + String.join("; ", problems));
            }
            return new ResolvedAgentDto(toDto(version), null, null, spec.remoteConnectionId(), false);
        }
        List<String> toolProblems = tools.pinProblems(workspaceId, spec.toolsOrEmpty());
        if (!toolProblems.isEmpty()) {
            throw new ConflictException("Agent " + id + " v" + version.version() + " cannot run: "
                    + String.join("; ", toolProblems));
        }
        ModelCatalog.ResolvedModel model = models.resolve(spec.model());
        return new ResolvedAgentDto(toDto(version), model.model(), model.providerModel(), model.connectionId(), model.fallback());
    }

    /** Outcome of a system import: {@code version} is null when only a draft was created. */
    record ImportResult(String agentId, Integer version, String contentHash, List<String> errors) {
    }

    /**
     * System import (no caller identity; {@code PdlcImportSeeder} only): creates the agent's draft
     * and, when it passes the same publication rules as {@link #publish}, publishes it as v1. A
     * definition that does not validate stays a draft, and the reasons are returned.
     */
    @Transactional
    ImportResult importPublished(String workspaceId, String id, String name, AgentSpec spec, String actor) {
        if (!store.insert(workspaceId, id, name, ContentHash.canonicalJson(spec), actor)) {
            throw new ConflictException("Agent " + id + " already exists in " + workspaceId);
        }
        List<String> errors = check(workspaceId, name, spec);
        if (!errors.isEmpty()) {
            return new ImportResult(id, null, null, errors);
        }
        String hash = ContentHash.ofAgent(name, spec);
        store.insertVersion(new VersionRow(workspaceId, id, 1, name, ContentHash.canonicalJson(spec), hash, null, actor));
        store.setCurrentVersion(workspaceId, id, 1, actor);
        return new ImportResult(id, 1, hash, List.of());
    }

    private List<String> check(String workspaceId, String name, AgentSpec spec) {
        List<String> errors = new ArrayList<>(AgentSpecValidator.validate(name, spec, models.authorizedModels()));
        if (spec != null && (spec.isA2a() || spec.usesRestRuntime())) {
            errors.addAll(remoteProblems(workspaceId, spec));
        } else if (spec != null) {
            errors.addAll(tools.pinProblems(workspaceId, spec.toolsOrEmpty()));
        }
        return errors;
    }

    /**
     * A remote runtime's connection must be usable by the workspace now: {@code A2A_AGENT} for a2a,
     * {@code REST_AGENT} for rest. Malformed bindings are the validator's to report.
     */
    private List<String> remoteProblems(String workspaceId, AgentSpec spec) {
        String connectionId = spec.remoteConnectionId();
        if (connectionId == null || connectionId.isBlank()) {
            return List.of();
        }
        return connections.toolConnectionProblems(connectionId, workspaceId,
                spec.isA2a() ? ConnectionService.A2A_AGENT : ConnectionService.REST_AGENT);
    }

    private AgentDefinitionDto toDto(DefinitionRow row) {
        return new AgentDefinitionDto(row.workspaceId(), row.id(), row.status().name(), row.draftName(),
                registry.spec(row.draftSpecJson()), row.draftRevision(), row.currentVersion(),
                registry.latestVersion(row.workspaceId(), row.id()), row.updatedAt(), row.updatedBy());
    }

    private static AgentVersionDto toDto(VersionRow row) {
        return new AgentVersionDto(row.workspaceId(), row.definitionId(), row.version(), row.name(),
                ContentHash.read(row.specJson(), AgentSpec.class), row.contentHash(), row.publishedAt(), row.publishedBy());
    }
}
