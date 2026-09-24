package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.ConnectionService;
import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.DefinitionStore.DefinitionRow;
import ai.pdlc.controlplane.platform.DefinitionStore.Status;
import ai.pdlc.controlplane.platform.DefinitionStore.VersionRow;
import ai.pdlc.controlplane.web.dto.ToolDefinitionDto;
import ai.pdlc.controlplane.web.dto.ToolDraftRequest;
import ai.pdlc.controlplane.web.dto.ToolValidationDto;
import ai.pdlc.controlplane.web.dto.ToolVersionDto;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.ContentHash;
import ai.pdlc.core.platform.ToolSpec;
import ai.pdlc.core.platform.ToolSpecValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * The ToolDefinition registry (docs/phase-2-execution-spec.md slice 2.1): the same draft →
 * immutable version → retire lifecycle as agents ({@link VersionedDefinitions}), plus the rule that
 * a tool may only be published - and a published tool only used - while its connection is an
 * active, unexpired {@code HTTP_API} connection granted to the workspace.
 *
 * <p>Registry checks are for authors; they never authorize a call. The agents worker's
 * {@code ToolExecutor} re-checks grant and connection before every call.
 */
@Service
public class ToolRegistryService {

    private final VersionedDefinitions<ToolSpec> registry;
    private final WorkspaceService workspaces;
    private final ConnectionService connections;

    public ToolRegistryService(ToolRegistryStore store, WorkspaceService workspaces, ConnectionService connections) {
        this.registry = new VersionedDefinitions<>(store, "Tool", ToolSpec.class, ContentHash::ofTool);
        this.workspaces = workspaces;
        this.connections = connections;
    }

    public List<ToolDefinitionDto> list(String workspaceId, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return registry.store().list(workspaceId).stream().map(this::toDto).toList();
    }

    public ToolDefinitionDto get(String workspaceId, String id, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return toDto(registry.find(workspaceId, id));
    }

    @Transactional
    public ToolDefinitionDto create(String workspaceId, ToolDraftRequest request, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        return toDto(registry.create(workspaceId, request.id(), request.name(), request.spec(), identity.user()));
    }

    @Transactional
    public ToolDefinitionDto saveDraft(String workspaceId, String id, ToolDraftRequest request, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        return toDto(registry.saveDraft(workspaceId, id, request.revision(), request.name(), request.spec(), identity.user()));
    }

    public ToolValidationDto validate(String workspaceId, String id, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.AUTHOR, Capability.WORKSPACE_ADMIN);
        DefinitionRow row = registry.find(workspaceId, id);
        ToolSpec spec = registry.spec(row.draftSpecJson());
        List<String> errors = check(workspaceId, row.draftName(), spec);
        return new ToolValidationDto(errors.isEmpty(), errors, errors.isEmpty() ? registry.hash(row.draftName(), spec) : null,
                row.draftRevision());
    }

    @Transactional
    public ToolVersionDto publish(String workspaceId, String id, Integer revision, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        return toDto(registry.publish(workspaceId, id, revision, identity.user(),
                (name, spec) -> check(workspaceId, name, spec)));
    }

    public List<ToolVersionDto> versions(String workspaceId, String id, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        registry.find(workspaceId, id);
        return registry.store().versions(workspaceId, id).stream().map(this::toDto).toList();
    }

    public ToolVersionDto version(String workspaceId, String id, int version, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return toDto(registry.findVersion(workspaceId, id, version));
    }

    @Transactional
    public ToolDefinitionDto rollback(String workspaceId, String id, Integer version, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        return toDto(registry.rollback(workspaceId, id, version, identity.user()));
    }

    /** Also stops every agent version that pins this tool from starting new runs. */
    @Transactional
    public ToolDefinitionDto retire(String workspaceId, String id, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        return toDto(registry.retire(workspaceId, id, identity.user()));
    }

    /**
     * Why an agent pinning {@code refs} cannot be published or started now (empty = usable): each
     * pinned tool must exist in this workspace, be active, have that exact version with intact
     * content, and its connection must be usable by the workspace. Malformed refs are the
     * validator's to report and are skipped here.
     */
    public List<String> pinProblems(String workspaceId, List<AgentSpec.ToolRef> refs) {
        List<String> problems = new ArrayList<>();
        for (AgentSpec.ToolRef ref : refs) {
            if (ref == null || ref.tool() == null || ref.version() == null) {
                continue;
            }
            DefinitionRow tool = registry.store().find(workspaceId, ref.tool()).orElse(null);
            if (tool == null) {
                problems.add("tool " + ref.tool() + " does not exist in workspace " + workspaceId);
                continue;
            }
            if (tool.status() == Status.RETIRED) {
                problems.add("tool " + ref.tool() + " is retired");
                continue;
            }
            VersionRow version = registry.store().version(workspaceId, ref.tool(), ref.version()).orElse(null);
            if (version == null) {
                problems.add("tool " + ref.tool() + " has no published version " + ref.version());
                continue;
            }
            ToolSpec spec = registry.spec(registry.verified(version).specJson());
            connections.toolConnectionProblems(spec.connectionId(), workspaceId, connectionKind(spec)).stream()
                    .map(p -> "tool " + ref.tool() + " v" + ref.version() + ": " + p)
                    .forEach(problems::add);
        }
        return problems;
    }

    private List<String> check(String workspaceId, String name, ToolSpec spec) {
        List<String> errors = new ArrayList<>(ToolSpecValidator.validate(name, spec));
        if (spec != null && spec.connectionId() != null && !spec.connectionId().isBlank()) {
            errors.addAll(connections.toolConnectionProblems(spec.connectionId(), workspaceId, connectionKind(spec)));
        }
        return errors;
    }

    /** A workspace tool bound to an MCP server's tool, with what was last published for it. */
    public record McpBinding(String toolId, String status, String mcpTool, String draftFingerprint, Integer latestVersion,
                             String latestFingerprint) {
    }

    /** Every {@code kind: mcp} tool of the workspace on {@code connectionId} (no authorization: callers check it). */
    public List<McpBinding> mcpBindings(String workspaceId, String connectionId) {
        List<McpBinding> bindings = new ArrayList<>();
        for (DefinitionRow row : registry.store().list(workspaceId)) {
            ToolSpec draft = registry.spec(row.draftSpecJson());
            Integer latest = registry.latestVersion(workspaceId, row.id());
            ToolSpec published = latest == null ? null : registry.spec(registry.findVersion(workspaceId, row.id(), latest).specJson());
            ToolSpec reference = published != null ? published : draft;
            if (reference == null || !reference.isMcp() || !connectionId.equals(reference.connectionId())) {
                continue;
            }
            bindings.add(new McpBinding(row.id(), row.status().name(), reference.mcpTool(),
                    draft == null ? null : draft.mcpFingerprint(), latest, published == null ? null : published.mcpFingerprint()));
        }
        return bindings;
    }

    static String connectionKind(ToolSpec spec) {
        return spec.isMcp() ? ConnectionService.MCP_SERVER : ConnectionService.HTTP_API;
    }

    private ToolDefinitionDto toDto(DefinitionRow row) {
        return new ToolDefinitionDto(row.workspaceId(), row.id(), row.status().name(), row.draftName(),
                registry.spec(row.draftSpecJson()), row.draftRevision(), row.currentVersion(),
                registry.latestVersion(row.workspaceId(), row.id()), row.updatedAt(), row.updatedBy());
    }

    private ToolVersionDto toDto(VersionRow row) {
        return new ToolVersionDto(row.workspaceId(), row.definitionId(), row.version(), row.name(),
                registry.spec(row.specJson()), row.contentHash(), row.publishedAt(), row.publishedBy());
    }
}
