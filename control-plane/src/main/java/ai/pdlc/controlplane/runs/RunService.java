package ai.pdlc.controlplane.runs;

import ai.pdlc.controlplane.identity.Identity;
import ai.pdlc.controlplane.platform.AgentRegistryService;
import ai.pdlc.controlplane.platform.Capability;
import ai.pdlc.controlplane.platform.WorkspaceService;
import ai.pdlc.controlplane.runs.RunStore.RunRow;
import ai.pdlc.controlplane.web.ConflictException;
import ai.pdlc.controlplane.web.NotFoundException;
import ai.pdlc.controlplane.web.ServiceUnavailableException;
import ai.pdlc.controlplane.web.dto.ResolvedAgentDto;
import ai.pdlc.controlplane.web.dto.RunDto;
import ai.pdlc.controlplane.web.dto.ToolCallDto;
import ai.pdlc.controlplane.web.dto.StartRunRequest;
import ai.pdlc.core.platform.AgentInputs;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Durable single-agent runs (docs/phase-1-execution-spec.md slice 4), the runs module.
 *
 * <p>Starting a run resolves - through the registry, which checks {@code OPERATOR} - the agent's
 * current published version and the model to call, validates the inputs against that version,
 * and records all of it on a {@code QUEUED} row before starting {@code AgentRunWorkflow}. That row
 * is the pin: publishing v2 afterwards never changes what this run executes. The workflow carries
 * only the run id; inputs and outputs stay in the row.
 */
@Service
public class RunService {

    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunStore store;
    private final RunLauncher launcher;
    private final AgentRegistryService registry;
    private final WorkspaceService workspaces;

    public RunService(RunStore store, RunLauncher launcher, AgentRegistryService registry, WorkspaceService workspaces) {
        this.store = store;
        this.launcher = launcher;
        this.registry = registry;
        this.workspaces = workspaces;
    }

    public RunDto start(String workspaceId, String agentId, StartRunRequest request, Identity identity) {
        String key = request.idempotencyKey() == null || request.idempotencyKey().isBlank() ? null : request.idempotencyKey();
        if (key != null) {
            workspaces.require(workspaceId, identity, Capability.OPERATOR);
            var existing = store.findByIdempotencyKey(workspaceId, key);
            if (existing.isPresent()) {
                return sameRun(existing.get(), agentId, key);
            }
        }
        ResolvedAgentDto resolved = registry.resolveForRun(workspaceId, agentId, identity);
        Map<String, String> inputs = request.inputs() == null ? Map.of() : request.inputs();
        List<String> errors = AgentInputs.validate(resolved.definition().spec(), inputs);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        UUID id = UUID.randomUUID();
        String workflowId = "agent-run-" + id;
        RunRow row = new RunRow(id, workspaceId, agentId, resolved.definition().version(),
                resolved.definition().contentHash(), resolved.model(), resolved.providerModel(), resolved.connectionId(),
                resolved.fallback(), write(inputs), "QUEUED", null, null, null, null, null, 0, key, workflowId,
                identity.user(), null, null, null);
        if (!store.insert(row)) {
            // Lost a race with a concurrent start using the same idempotency key.
            return sameRun(store.findByIdempotencyKey(workspaceId, key).orElseThrow(), agentId, key);
        }
        try {
            launcher.start(workflowId, id.toString(), resolved.definition().spec().limits().timeoutSeconds());
        } catch (RuntimeException e) {
            store.failToStart(id, "Could not start the run workflow: " + e.getMessage());
            throw new ServiceUnavailableException("Could not start run " + id + ": workflow service unavailable", e);
        }
        return toDto(store.find(id).orElseThrow());
    }

    public RunDto get(String workspaceId, String runId, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        RunRow row = find(workspaceId, runId);
        return withRemote(toDto(row), store.remote(row.id()).orElse(null));
    }

    static final int MAX_REPLY_CHARS = 20_000;

    /**
     * An operator answers a remote agent that stopped at input-required (slice 2.5). The reply is
     * stored with the run and the workflow is signalled; the next invocation sends it to the same
     * remote task. Only one reply is accepted per question.
     */
    public RunDto reply(String workspaceId, String runId, String text, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.OPERATOR);
        RunRow row = find(workspaceId, runId);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        if (text.length() > MAX_REPLY_CHARS) {
            throw new IllegalArgumentException("text may be at most " + MAX_REPLY_CHARS + " characters");
        }
        if (!"AWAITING_INPUT".equals(row.status())) {
            throw new ConflictException("Run " + runId + " is " + row.status() + ", not waiting for input");
        }
        int seq = store.acceptInput(row.id(), text, identity.user())
                .orElseThrow(() -> new ConflictException("Run " + runId + " is no longer waiting for input"));
        try {
            launcher.signalInput(row.workflowId(), row.id() + ":" + seq);
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("Reply stored, but run " + runId + " could not be resumed: workflow service unavailable", e);
        }
        return get(workspaceId, runId, identity);
    }

    /** The run's tool-call trace (members): decisions and outcomes, never credentials or response bodies. */
    public List<ToolCallDto> toolCalls(String workspaceId, String runId, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return store.toolCalls(find(workspaceId, runId).id());
    }

    public List<RunDto> list(String workspaceId, String agentId, Identity identity) {
        workspaces.requireMember(workspaceId, identity);
        return store.list(workspaceId, agentId, 50).stream().map(RunService::toDto).toList();
    }

    /** Best-effort: the workflow marks the run CANCELLED; a model call already in flight is abandoned, not undone. */
    public RunDto cancel(String workspaceId, String runId, Identity identity) {
        workspaces.require(workspaceId, identity, Capability.OPERATOR);
        RunRow row = find(workspaceId, runId);
        if (TERMINAL.contains(row.status())) {
            throw new ConflictException("Run " + runId + " is already " + row.status());
        }
        try {
            launcher.cancel(row.workflowId());
        } catch (RuntimeException e) {
            throw new ServiceUnavailableException("Could not cancel run " + runId + ": workflow service unavailable", e);
        }
        return toDto(store.find(row.id()).orElseThrow());
    }

    private RunRow find(String workspaceId, String runId) {
        UUID id;
        try {
            id = UUID.fromString(runId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("No run " + runId + " in " + workspaceId);
        }
        return store.find(id).filter(r -> r.workspaceId().equals(workspaceId))
                .orElseThrow(() -> new NotFoundException("No run " + runId + " in " + workspaceId));
    }

    private static RunDto sameRun(RunRow existing, String agentId, String key) {
        if (!existing.agentId().equals(agentId)) {
            throw new ConflictException("Idempotency key " + key + " was already used for agent " + existing.agentId());
        }
        return toDto(existing);
    }

    private static String write(Map<String, String> inputs) {
        try {
            return JSON.writeValueAsString(inputs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RunDto withRemote(RunDto d, RunStore.RemoteRow remote) {
        if (remote == null) {
            return d;
        }
        return new RunDto(d.id(), d.workspaceId(), d.agentId(), d.agentVersion(), d.contentHash(), d.model(), d.providerModel(),
                d.connectionId(), d.fallback(), d.inputs(), d.status(), d.outputText(), d.output(), d.error(), d.promptTokens(),
                d.completionTokens(), d.attempts(), d.idempotencyKey(), d.createdBy(), d.createdAt(), d.startedAt(),
                d.finishedAt(), new RunDto.Remote(remote.dialect(), remote.taskId(), remote.contextId(), remote.state(),
                        remote.statusText(), remote.cancel()));
    }

    static RunDto toDto(RunRow r) {
        try {
            Map<String, String> inputs = JSON.readValue(r.inputJson(), new TypeReference<Map<String, String>>() { });
            Object output = r.outputJson() == null ? null : JSON.readValue(r.outputJson(), Object.class);
            return new RunDto(r.id().toString(), r.workspaceId(), r.agentId(), r.agentVersion(), r.contentHash(), r.model(),
                    r.providerModel(), r.connectionId(), r.fallback(), inputs, r.status(), r.outputText(), output, r.error(),
                    r.promptTokens(), r.completionTokens(), r.attempts(), r.idempotencyKey(), r.createdBy(), r.createdAt(),
                    r.startedAt(), r.finishedAt(), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
