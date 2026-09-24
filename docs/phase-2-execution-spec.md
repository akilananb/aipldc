# Phase 2 execution spec: governed execution and federation

This spec turns Phase 2 of [configurable-agent-platform.md](configurable-agent-platform.md) into slices, in the same format as [phase-1-execution-spec.md](phase-1-execution-spec.md). **Slice 2.1 is implemented on its branch**; slices 2.2–2.8 are specified and not yet built.

**Phase 2 exit evidence** (from the roadmap):

- Each adapter completes a real task and exposes the cancellation and status semantics it supports.
- Secret isolation, denied tools and unknown-outcome handling are demonstrated.

## Decisions for this phase

| Topic | Decision |
|---|---|
| **One tool path** | Every tool call goes through the agents-side `ToolExecutor`: model-requested calls now, workflow tool nodes in Phase 4. There is no second path that skips policy. Tool descriptions and MCP annotations are untrusted metadata, never authorization. |
| **Tool loop ownership** | The runner owns the loop. A spike confirmed that Spring AI 2.0's `OpenAiChatModel` returns the model's `tool_calls` to the caller without executing them. So each turn sends the pinned tool definitions, receives `AssistantMessage.getToolCalls()`, executes them through policy, and appends `ToolResponseMessage`s. Turn, call and deadline limits are ours alone. |
| **Egress** | `EgressPolicy` (core) resolves every destination and rejects anything that is not `http(s)`. It also rejects loopback, link-local (including the 169.254.169.254 metadata address), private (RFC 1918), CGNAT, multicast, IPv6 ULA/link-local and unspecified addresses, unless the host is in the enterprise allowlist `pdlc.egress.allowed-private-hosts`. Tool calls never follow redirects. From slice 2.4, the NetworkPolicy and egress proxy enforce the same rule at the network layer, closing the DNS-rebinding window a check-then-connect leaves. |
| **Credentials** | Unchanged from Phase 1: connections hold `kv://` references, and the executor resolves them at the last moment. A tool call's secret goes only into the outgoing HTTP header; it never reaches the prompt, the model, the trace or workflow history. |
| **Sandbox** | Kubernetes Jobs with a configured `runtimeClass` (gVisor or Kata). The untrusted-package execution class is disabled when no isolation runtime is configured; it never falls back to the host. |
| **First adapter** | A2A (slice 2.5), then generic REST/gRPC (2.6), then ACP (2.7, needs the sandbox). |
| **Interop note** | Spring AI 2.0 adds `"strict": true` *inside* each tool's `parameters` schema. OpenAI-compatible gateways ignore unknown keys in non-strict mode. Verify this against each real provider before rollout. |

## Slice 2.1 — Governed API tools and native tool loop

**Tool definitions** (`core/.../platform/ToolSpec`). They are versioned like agents: draft, then validate, then immutable hashed versions, then retire.

- **Fields:** `description`, `kind` (`http`), `connectionId`, `method`, a `path` template (`/orders/{orderId}`), `inputSchema` (the enforced JSON-Schema subset), `effect` (`READ` or `WRITE`), `timeoutSeconds` and `maxResponseBytes`.
- **`ToolSpecValidator` rejects:** undeclared path parameters; GET with a `WRITE` effect or non-GET with a `READ` effect; out-of-range limits; unsupported schema keywords.

**Connections and grants.**

- There is a new connection kind, `HTTP_API` (enterprise Admin; base URL plus a `kv://` reference or `NONE`). Its host is checked with `EgressPolicy` when the connection is created.
- `connection_grants` records which workspaces may use a connection. Only the enterprise Admin grants or revokes.
- Publishing a tool requires its connection to be `HTTP_API`, active and granted to the workspace.

**Agents use tools.**

- `AgentSpec.tools = [{tool, version}]` pins exact published tool versions.
- `Limits` gains `maxModelTurns` (default 8, max 32) and `maxToolCalls` (default 16, max 64).
- Agent publication, and `resolveForRun`, check every pinned tool: the version is published, it is in the same workspace, the tool isn't retired, and its connection is still granted and available.

**`ToolExecutor` (agents), in order:**

1. Is the tool among the agent version's pinned tools?
2. Do the args validate against the input schema?
3. Is the effect allowed? `WRITE` is denied until slice 2.2 adds approvals.
4. Is the connection active, unexpired and granted, checked now?
5. Build the URL: path parameters are URL-encoded; GET arguments go into the query, anything else into a JSON body.
6. Run the egress check.
7. Resolve the secret.
8. Send the call: no redirects, a per-tool timeout, and a response capped at `maxResponseBytes` (truncated, and marked as truncated).

Every call, allowed or denied, is recorded in `platform_tool_calls`: run, attempt, turn, tool@version, args plus their canonical hash, decision, reason, status, duration, bytes and the truncated flag. A denied call is returned to the model as an error result; it doesn't fail the run.

**The loop.**

- Model turn, then execute the requested calls, then return the results, repeated until the model answers without tool calls.
- Exhausting `maxModelTurns`, `maxToolCalls` or the deadline ends the run as `FAILED` ("tool loop limit reached"), never as success.
- The final answer goes through the output schema as before.
- An activity retry replays the loop. That is safe because 2.1 executes `READ` effects only; each replayed call is recorded under its attempt number.

**API and UI.**

- `/api/workspaces/{ws}/tools` (the same lifecycle endpoints as agents).
- `POST/DELETE /api/platform/connections/{id}/grants/{ws}`.
- `GET /api/workspaces/{ws}/runs/{runId}/tool-calls`.
- Studio: a Tools page and editor, a tool picker in the agent editor, and a tool-call trace in the run detail.

**Exit (roadmap "tool and secret policy"):**

- An agent granted read-only tool A is asked to call write tool B and to reveal the connection secret.
- B is denied before any HTTP request.
- No secret appears in the prompt, output, logs, trace or workflow history.
- A destination that redirects to a metadata address is not followed.
- Revoking the connection during a run denies the next protected call.

## Slice 2.2 — Write effects and approvals

- **Approval-gated writes.** A `WRITE` call pauses the run: `AgentRunWorkflow` waits on a signal. The approval is tied to (run, tool version, canonical args hash) and to the reviewer's capability.
  - Changed arguments invalidate it.
  - The wait times out into escalation; it is never auto-approved.
- **Before every write**, an effect-intent row and an idempotency key (run, turn, call id) are recorded. Retries reuse the key.
- **Unknown outcomes.** A timeout after the request was sent is an unknown outcome. It is reconciled through the target's idempotency support, or the run pauses for an operator.
- **API and UI:** an approval inbox (API and Studio).
- **Exit:** an approved write executes exactly once. A retried delivery doesn't duplicate it where the target supports idempotency, and pauses as unknown where it doesn't.

## Slice 2.3 — Remote MCP tools

- **Discovery:** discover a remote HTTP MCP server's tools through the egress policy, with its OAuth authorization flow.
- **Review:** preview each tool and approve it individually as a `ToolSpec` of kind `mcp`. A fingerprint of the schema and description is versioned; a changed server capability needs re-review before a new version can run.
- **Execution** goes through the same `ToolExecutor` checks.
- **Exit:** an approved MCP tool runs. An unapproved or changed tool is refused.

## Slice 2.4 — Sandbox runner (Kubernetes Jobs with gVisor)

- **`SandboxPort`** (core) with a `KubernetesJobSandbox` adapter.
- **Each Job:**
  - runs from an immutable, pinned image, as non-root, with a read-only root filesystem;
  - has CPU, memory and time limits;
  - gets an emptyDir per-run workspace and only allowlisted environment variables;
  - has a default-deny NetworkPolicy, reaching approved destinations only through the egress proxy;
  - has no hostPath and no container-runtime socket;
  - uses `runtimeClassName` from config. If it's missing, untrusted packages are refused.
- **Cancellation and timeout** delete the Job, including its whole process tree, and revoke the Job's short-lived credentials.
- **Custom tools and verifiers** are enterprise-approved image references with declared input/output schemas.
- **Exit:** a custom package can't read a host sentinel file, another workspace's artifact, or an unapproved endpoint. Terminating the Job revokes its credentials.

## Slice 2.5 — A2A outbound adapter

- **Discovery:** fetch the Agent Card through the egress policy, then authorize the endpoint. The card itself is never a trust credential.
- **Protocol:** negotiate the interface and version; send a message or run the task lifecycle (submitted, working, input-required, auth-required, completed, failed, canceled); poll or stream.
- **Cancellation:** best-effort, with an acknowledgment state.
- **Registry:** `AgentSpec.runtime = "a2a"` names a connection and the remote skill.
- **Durability:** the remote task id is persisted as soon as it's known. The invocation identity is reused across retries, and an unknown outcome is reconciled through status.
- **Exit:** a controlled remote A2A agent completes a task, pauses at input-required, is cancelled, and fails remotely. Each case is shown with honest status semantics.

## Slice 2.6 — Generic REST and gRPC agent adapters

- **REST:** a declared synchronous mapping, or asynchronous task/status/cancel endpoint mappings, with schemas, auth, a deadline and remote idempotency support. Job state is never guessed from arbitrary JSON.
- **gRPC:** registered descriptors and a service/method mapping, TLS/mTLS and deadlines. Only declared unary or streaming methods are callable; reflection never publishes methods by itself.
- **Exit:** each adapter completes a real minimal task and exposes its supported lifecycle.

## Slice 2.7 — ACP in the sandbox

- **Build-worker hardening:** a pinned `acpx` version instead of `@latest`; no inherited host environment; no `--approve-all` (ACP permission requests are mediated by policy).
- **Isolation:** ACP sessions run as sandbox Jobs with a per-run git worktree.
- **Exit:** a sandboxed ACP coding task completes. A permission request outside policy is denied. Cancelling kills the session's process tree.

## Slice 2.8 — A2A ingress and delegation bounds

- **Ingress:** explicitly published platform agents get an authenticated A2A endpoint with an Agent Card.
- **Delegation bounds:** recipient allowlists, delegation depth, and a total run budget. Only approved context leaves the workspace.
- **Exit:** an external A2A client invokes a published agent. An unpublished agent is unreachable. A delegation beyond the depth limit is refused.

## Verification commands

The same as Phase 1 (see its "Verification commands"). Also, the agents tool tests run against local HTTP servers and need no network.
