package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.ForbiddenException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.dto.WorkspaceDto;
import ai.pdlc.controlplane.web.dto.WorkspaceMemberDto;
import ai.pdlc.controlplane.web.dto.WorkspaceRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Workspaces and membership, plus the single access check every workspace-scoped resource goes
 * through ({@link #require}). A caller who is not a member gets 404 for the workspace and for
 * everything inside it, so workspace names and asset ids cannot be discovered by probing; a member
 * missing the needed capability gets 403.
 *
 * <p>The enterprise {@code Admin} role may create workspaces and list them all (names only), but
 * it is not a membership: reading or changing a workspace's content still needs a grant.
 */
@Service
public class WorkspaceService {

    public static final String ENTERPRISE_ADMIN_ROLE = "Admin";
    static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,39}$");

    private final WorkspaceStore store;

    public WorkspaceService(WorkspaceStore store) {
        this.store = store;
    }

    @Transactional
    public WorkspaceDto create(WorkspaceRequest request, Identity identity) {
        requireEnterpriseAdmin(identity);
        List<String> errors = new ArrayList<>();
        if (request.id() == null || !ID.matcher(request.id()).matches()) {
            errors.add("id must match " + ID.pattern());
        }
        if (request.name() == null || request.name().isBlank()) {
            errors.add("name is required");
        }
        if (request.admins() == null || request.admins().isEmpty()
                || request.admins().stream().anyMatch(a -> a == null || a.isBlank())) {
            errors.add("at least one workspace admin is required");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        if (!store.insert(request.id(), request.name(), identity.user())) {
            throw new ConflictException("Workspace " + request.id() + " already exists");
        }
        for (String admin : request.admins()) {
            store.setMember(request.id(), admin, EnumSet.of(Capability.WORKSPACE_ADMIN), identity.user());
        }
        return toDto(store.find(request.id()).orElseThrow(), store.capabilities(request.id(), identity.user()));
    }

    public List<WorkspaceDto> list(Identity identity) {
        List<WorkspaceStore.WorkspaceRow> rows = isEnterpriseAdmin(identity)
                ? store.all()
                : store.forMember(identity.user());
        return rows.stream().map(w -> toDto(w, store.capabilities(w.id(), identity.user()))).toList();
    }

    public WorkspaceDto get(String workspaceId, Identity identity) {
        Set<Capability> caps = requireMember(workspaceId, identity);
        return toDto(store.find(workspaceId).orElseThrow(), caps);
    }

    public List<WorkspaceMemberDto> members(String workspaceId, Identity identity) {
        requireMember(workspaceId, identity);
        return store.members(workspaceId).entrySet().stream()
                .map(e -> new WorkspaceMemberDto(e.getKey(), e.getValue()))
                .toList();
    }

    /** The PDLC projects this workspace's assets serve (members only). */
    public List<WorkspaceStore.LinkedProject> projects(String workspaceId, Identity identity) {
        requireMember(workspaceId, identity);
        return store.projects(workspaceId);
    }

    @Transactional
    public WorkspaceMemberDto setMember(String workspaceId, String userId, Set<Capability> capabilities, Identity identity) {
        require(workspaceId, identity, Capability.WORKSPACE_ADMIN);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        Set<Capability> caps = capabilities == null || capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(capabilities);
        if (!caps.contains(Capability.WORKSPACE_ADMIN) && isLastAdmin(workspaceId, userId)) {
            throw new ConflictException("Cannot remove the last WORKSPACE_ADMIN of " + workspaceId);
        }
        store.setMember(workspaceId, userId, caps, identity.user());
        return new WorkspaceMemberDto(userId, caps);
    }

    /**
     * Resolves the caller's capabilities in the workspace, or throws 404 when the workspace does
     * not exist or the caller is not a member (indistinguishable on purpose).
     */
    public Set<Capability> requireMember(String workspaceId, Identity identity) {
        Set<Capability> caps = workspaceId == null ? Set.of() : store.capabilities(workspaceId, identity.user());
        if (caps.isEmpty()) {
            throw new NotFoundException("No workspace " + workspaceId);
        }
        return caps;
    }

    /** As {@link #requireMember}, then 403 unless the caller holds at least one of {@code anyOf}. */
    public Set<Capability> require(String workspaceId, Identity identity, Capability... anyOf) {
        Set<Capability> caps = requireMember(workspaceId, identity);
        if (Arrays.stream(anyOf).noneMatch(caps::contains)) {
            throw new ForbiddenException("Requires " + Arrays.toString(anyOf) + " in workspace " + workspaceId);
        }
        return caps;
    }

    private boolean isLastAdmin(String workspaceId, String userId) {
        Map<String, Set<Capability>> members = store.members(workspaceId);
        Set<Capability> current = members.getOrDefault(userId, Set.of());
        if (!current.contains(Capability.WORKSPACE_ADMIN)) {
            return false;
        }
        return members.values().stream().filter(c -> c.contains(Capability.WORKSPACE_ADMIN)).count() == 1;
    }

    private static void requireEnterpriseAdmin(Identity identity) {
        if (!isEnterpriseAdmin(identity)) {
            throw new ForbiddenException("Role " + identity.role() + " is not an enterprise Admin");
        }
    }

    private static boolean isEnterpriseAdmin(Identity identity) {
        return ENTERPRISE_ADMIN_ROLE.equals(identity.role());
    }

    private static WorkspaceDto toDto(WorkspaceStore.WorkspaceRow row, Set<Capability> caps) {
        return new WorkspaceDto(row.id(), row.name(), row.createdAt(), row.createdBy(), caps);
    }
}
