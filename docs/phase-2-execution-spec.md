# Phase 2 execution spec: governed execution and federation

This spec turns Phase 2 of [configurable-agent-platform.md](configurable-agent-platform.md) into slices, in the same format as [phase-1-execution-spec.md](phase-1-execution-spec.md). **Slices 2.1–2.5 are implemented**; slices 2.6–2.8 are specified and not yet built.

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

**As built (differences from the plan above, and what they mean):**

- **One lifecycle implementation.** `DefinitionStore` + `VersionedDefinitions` hold the draft, publish, rollback and retire rules for both agents and tools. Each kind keeps its own tables (`agent_definitions*`, `tool_definitions*`).
- **Only the pinned tool id is trusted.** The model calls a tool by its id. Names and descriptions it echoes are never used to authorize anything.
- **Credential redaction.** If a response body contains the connection's credential verbatim (an API that echoes headers, for example), the executor replaces it with `[REDACTED]` before the model sees it. Encoded forms, such as base64, are not detected.
- **Spring AI never executes tools.** `OpenAiCompatibleModelInvoker` offers tools as definitions only, and each callback throws if called. A change in Spring AI's defaults therefore can't run a tool outside `ToolExecutor`.
- **Path parameters.** Values are percent-encoded, including `/`. `.` and `..` are refused. Tool paths may not contain `//`, `..`, a backslash, whitespace, a query or a fragment, so a path cannot escape the connection's base URL.
- **Known gap: DNS rebinding.** The HTTP client resolves the host again when it connects. A name whose DNS answer changes between the check and the connect is therefore not pinned to the checked addresses. Slice 2.4's egress proxy closes it for sandbox traffic only (it connects to the address it checked); `http` tools still resolve twice.
- **Endpoint added: `GET /api/workspaces/{ws}/connections`** (members). It lists the `HTTP_API` connections granted to the workspace, without secret references, so tool authors can choose one.
- **Configuration.** `pdlc.egress.allowed-private-hosts` (`PDLC_EGRESS_ALLOWED_PRIVATE_HOSTS`) is set on both control-plane and agents, and the two values must match.

**Exit evidence** (live: Postgres 16, a Temporal dev server, control-plane, agents, an OpenAI-compatible stub that emits `tool_calls`, and a recording test API):

- **Policy scenario.** The agent pinned `get-order` (READ), `cancel-order` (WRITE) and `hop`. The model requested three calls:
  - `get-order` was **ALLOWED** (HTTP 200).
  - `cancel-order` was **DENIED** ("requires an approval"). The test API received no request for it.
  - A `get-order` call with an extra `reveal` argument was **DENIED** as an undeclared argument.
  - The test API's echo of the `Authorization` header reached the model as `Bearer [REDACTED]`.
- **No secret leaked.** The secret value appeared 0 times in the control-plane and agents logs, the model requests, the Temporal server log, `platform_runs`, `platform_tool_calls` and the run's Temporal history.
- **Metadata address.** An `HTTP_API` connection to `http://169.254.169.254` is rejected when it is created. A tool whose API answers `302 → http://169.254.169.254/...` records "redirect not followed", and no second request is made.
- **Revocation mid-run.** The grant was revoked between turns 1 and 2: turn 1 was ALLOWED, and turn 2 was DENIED ("not granted to workspace") with no HTTP request. New runs are then refused at start, with every affected tool named.
- **Turn limit.** A model that keeps calling tools ends `FAILED`: "tool loop limit reached: maxModelTurns=4 …". The failure is not retried.
- **Studio (Playwright).** A tool was created, saved, validated and published through the UI. A validation finding appears for an undeclared path parameter. The agent editor shows the pinned tools and the loop limits. The run detail shows the tool-call trace. At phone width there is no horizontal scroll.

## Slice 2.2 — Write effects and approvals

- **Approval-gated writes.** A `WRITE` call pauses the run: `AgentRunWorkflow` waits on a signal. The approval is tied to (run, tool version, canonical args hash) and to the reviewer's capability.
  - Changed arguments invalidate it.
  - The wait times out into escalation; it is never auto-approved.
- **Before every write**, an effect-intent row and an idempotency key (run, turn, call id) are recorded. Retries reuse the key.
- **Unknown outcomes.** A timeout after the request was sent is an unknown outcome. It is reconciled through the target's idempotency support, or the run pauses for an operator.
- **API and UI:** an approval inbox (API and Studio).
- **Exit:** an approved write executes exactly once. A retried delivery doesn't duplicate it where the target supports idempotency, and pauses as unknown where it doesn't.

**As built:**

- **Pausable runs.** A run can pause, so its conversation is stored in `platform_run_messages`, never in Temporal history. Each `invoke` is a resumable segment:
  - the model's turn is stored before any of its calls run, and each result is stored as it completes;
  - a retry or a resume re-runs only unanswered calls and never asks the model again for a turn already stored;
  - turn and call limits and token usage are rebuilt from the store, so they hold across pauses.
  - `platform_runs.active_ms` makes `timeoutSeconds` bound active time only; waiting for a human does not count.
- **Workflow.** `AgentRunWorkflowImpl`, behind `getVersion("approvals")`:
  - It re-invokes after each pause.
  - An approval wait escalates at `approval.escalateAfterMinutes` (default 60), which records `escalated_at` and posts a best-effort `NotifyPort` notice.
  - It then expires at `expireAfterMinutes` (default 1440) into a denial the model sees.
  - An `UNKNOWN` effect waits for an operator with no deadline.
  - Signals carry ids only. If a signal is lost, the decision is re-read at the escalation check.
- **Approval binding.** `platform_approvals` is unique per (run, turn, call id). The executor runs a write only under an `APPROVED` row whose tool version and args hash equal the call's. When a model re-requests with changed arguments, that is a new call, so it needs a new approval.
- **Who decides.**
  - Approvals: a workspace `REVIEWER`, never the user who started the run (maker-checker), and only while `PENDING` (409 otherwise).
  - Effect resolution: an `OPERATOR`, and only while the effect is `UNKNOWN`.
- **Effects.** Before anything is sent, `platform_effects` records `INTENDED` under the key `run:turn:callId`, then moves to `SENT` → `SUCCEEDED`/`FAILED`/`UNKNOWN`.
  - A known outcome is never resent.
  - `idempotency: HEADER` tools send `Idempotency-Key` and, after a timeout, resend once with the same key.
  - `NONE` tools pause for an operator, who resolves the effect as `SUCCEEDED`, `FAILED` or `RETRY`.
- **API:**
  - `GET /api/workspaces/{ws}/approvals[?status=]`
  - `POST .../approvals/{id}/approve|reject {reason}`
  - `GET .../runs/{runId}/effects`
  - `POST .../runs/{runId}/effects/{id}/resolve {outcome, note}`
- **Studio:**
  - An Approvals view with a pending-count badge, the exact arguments and their hash, and approve/reject with a reason. The run's starter sees why they cannot decide.
  - Run detail shows the pause state, a Writes table (state, send count, idempotency key) and operator resolution.
  - The tool editor has idempotency and approval-timing fields.

**Exit evidence** (live: Postgres 16, a Temporal dev server, control-plane, agents, a stub LLM that emits `tool_calls`, and a recording test API that deduplicates on `Idempotency-Key`):

1. **Approved write, exactly once.**
   - The run paused `AWAITING_APPROVAL` with 0 requests sent.
   - The run's starter (also a REVIEWER) got 403.
   - After another reviewer approved, exactly one `POST` went out with `Idempotency-Key: <run>:1:call_1`, and the run `SUCCEEDED`.
2. **Crash mid-write, target deduplicates.** Agents was `kill -9`'d while the target held the approved POST.
   - The effect stayed `SENT`.
   - After restart, Temporal's retry resent it with the same key, and the target returned its stored result (`deduplicated: true`).
   - It was **applied once**, the effect shows `SUCCEEDED` after 2 sends, and the run `SUCCEEDED`.
3. **Crash mid-write, no idempotency support.** The same crash against a `NONE` tool left the run `NEEDS_OPERATOR` (effect `UNKNOWN`).
   - A non-operator's resolve got 403.
   - The operator resolved it `SUCCEEDED` with a note, and the model saw the operator's note.
   - **No second POST** was sent.
4. **Rejection.** The reason ("order 77 already shipped") reached the model as a denial, no request was sent, and the run finished.
5. **Changed arguments.** The first approval ({"orderId":"100"}) was rejected. The model's new request ({"orderId":"101"}) got a new approval with a different args hash; only that approved call was sent.
6. **Never approved by time.** With a 1/2-minute tool setting, the approval escalated at +1 min and expired at +2 min. The model saw "expired without a decision", and **no request was ever sent**.
7. **Studio (Playwright).**
   - The starter sees the request with Approve disabled and the reason why.
   - Run detail shows the approval pause.
   - The reviewer approves from the inbox, and the run resumes.
   - Writes and the trace show the effect and its key.
   - The tool editor shows the write settings.
   - At phone width there is no horizontal scroll.

## Slice 2.3 — Remote MCP tools

- **Discovery:** discover a remote HTTP MCP server's tools through the egress policy, with its OAuth authorization flow.
- **Review:** preview each tool and approve it individually as a `ToolSpec` of kind `mcp`. A fingerprint of the schema and description is versioned; a changed server capability needs re-review before a new version can run.
- **Execution** goes through the same `ToolExecutor` checks.
- **Exit:** an approved MCP tool runs. An unapproved or changed tool is refused.

**As built:**

- **Connections.**
  - An enterprise Admin creates an `MCP_SERVER` connection, and its URL is checked by `EgressPolicy` when saved. It is granted to workspaces like `HTTP_API`.
  - Auth is `API_KEY` (a static bearer by `kv://` reference), `NONE`, or `OAUTH_CLIENT_CREDENTIALS`: the client id is stored in `connections.oauth_client_id` (V21), and the client secret is held by `kv://` reference.
  - A tool's connection must be of its kind (`http` → `HTTP_API`, `mcp` → `MCP_SERVER`).
- **Own client, not the SDK transport.** `adapters/.../mcp/McpHttpClient` is a minimal Streamable-HTTP JSON-RPC client:
  - `initialize`, paginated `tools/list`, `tools/call`; JSON or SSE; `Mcp-Session-Id`;
  - the egress guard on every request, no redirects, a hard deadline (including a stalled body), and byte caps.
  - `McpOAuth` follows RFC 9728 → RFC 8414 → a `client_credentials` token with an RFC 8707 `resource`. Every URL is guarded, the metadata's `resource` must be the server itself, and the token is cached until expiry.
  - Browser sign-in (authorization code + PKCE) is a **follow-up**: it needs a platform-writable secret store for refresh tokens.
- **Discovery.** `POST /api/workspaces/{ws}/connections/{id}/mcp-discovery` (AUTHOR; the connection must be granted) runs `McpDiscoveryWorkflow` on `REASONING`, so the credential is used only in the agents worker. Each server tool gets a state:
  - `NEW`, `APPROVED`, `CHANGED` or `REMOVED`, compared with the workspace's published `kind: mcp` tools;
  - or `UNSUPPORTED_SCHEMA`, with the reasons.
- **Approval is the tool lifecycle.** A `kind: mcp` draft pins `mcpTool`, the server's `inputSchema` and `mcpFingerprint` (`McpFingerprint` = canonical hash of name, description, input schema and annotations); publishing it is the approval. The reviewer picks READ or WRITE; `readOnlyHint` is shown only as a suggestion.
- **Execution** goes through `ToolExecutor`:
  - All the 2.1/2.2 checks apply, then the server's current listing (cached per run segment) must contain the tool with the pinned fingerprint and an identical schema. Otherwise the call is **DENIED** without calling the tool.
  - READ runs `tools/call`.
  - WRITE uses the 2.2 approval and effect-intent path; a known outcome is replayed before the server is contacted. MCP has no idempotency key, so a possibly-sent outcome waits for an operator.
  - Bearer and OAuth tokens are redacted from results.
- **Schema subset.** `OutputSchema` now accepts annotation keywords (`title`, `default`, `examples`, `format`, `$schema`) and enforces `additionalProperties`, `minimum`/`maximum`, `minLength`/`maxLength`, `minItems`/`maxItems` and `pattern` (at most 200 characters). Anything else still blocks approval.

**Exit evidence** (live: Postgres 16, a Temporal dev server, control-plane, agents, a stub LLM, and a Python Streamable-HTTP MCP server with an OAuth client-credentials authorization server):

1. **Setup and discovery.**
   - An `MCP_SERVER` connection to `169.254.169.254` is rejected.
   - Discovery before the grant gets 409.
   - Discovery after the grant shows the OAuth flow in the server log (401 → token issued with `resource=…/mcp` → `tools/list`) and lists `lookup_order`, `cancel_order` and `admin_wipe` as NEW.
2. **Approval in the Studio (Playwright).** `lookup_order` was approved as READ and `cancel_order` as WRITE. Discovery then showed APPROVED/APPROVED/NEW.
3. **An approved MCP tool runs.** An agent pinning `lookup-order` answered using the server's result (`order 42: shipped, arriving Friday`).
4. **An unapproved tool is refused.** The model's call to `admin-wipe` was DENIED ("not available to this agent"), and the server received no `tools/call`.
5. **A changed tool is refused.** The server changed `lookup_order`'s description:
   - the next call was DENIED ("changed lookup_order since it was reviewed; it needs re-review") with only a `tools/list` on the server;
   - discovery showed CHANGED;
   - after "Update draft to server version" → publish v2 in the Studio and an agent re-pin, the call succeeded again.
6. **An MCP write goes through the approval inbox.** It paused with 0 server calls. The run's starter was refused. After a reviewer approved, exactly one `tools/call cancel_order` went out, and the effect is `SUCCEEDED`.
7. **No secret leaked.** The client secret and every issued access token appeared 0 times in the control-plane and agents logs, the model requests, the Temporal log, run, trace, message, effect and connection rows, and every run's workflow history.

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

**As built:**

- **Enterprise image catalog** (`sandbox_images`, V22; `/api/platform/sandbox-images`).
  - Only the enterprise Admin adds, re-pins or retires entries; any signed-in user can list them.
  - An entry is a digest-pinned image (`name@sha256:<64 hex>`; tags are refused) with:
    - a declared input schema and an optional output schema;
    - the hosts the package may reach (`host` or `host:port`, at most 20);
    - CPU (50–4000m), memory (32–4096 MiB) and time (1–600 s) limits.
- **`kind: sandbox` tools.** A tool names a catalog entry (`sandboxImage`) and the exact `sandboxImageRef` it was reviewed with. It has no connection, method or path, and its idempotency is NONE.
  - Publishing needs an active entry that still carries that ref and the same input schema, and the tool's timeout may not exceed the image's.
  - Re-pinning or retiring an entry makes agents pinning the tool refuse to start ("…needs re-review"), and running agents are denied their next call.
  - The Studio's "Update draft to the catalog image" button, followed by publishing a new version, is the re-review.
- **`SandboxPort`** (core) has two adapters, both refusing every call when no isolation runtime is configured:
  - **`KubernetesJobSandbox`** runs one Job per call under `runtimeClassName`. The pod:
    - runs as non-root (65534) with `RuntimeDefault` seccomp, a read-only root filesystem, no privilege escalation and all capabilities dropped;
    - has no service-account token, no service links and no host namespaces;
    - has CPU and memory requests equal to its limits, `activeDeadlineSeconds`, and no retries;
    - gets only two volumes, size-capped `emptyDir`s for `/workspace` and `/tmp`.
    
    Input and the proxy credential go in a per-call Secret, never in the Job spec. Timeout, completion and `terminateRun` delete the Job with `propagationPolicy=Foreground` (and its Secret). `ensureNetworkPolicy` installs `pdlc-sandbox-egress`: no ingress, and egress only to the agents pods' proxy port and cluster DNS. `infra/k8s/sandbox.yaml` has the namespace (Pod Security `restricted`), the `gvisor` RuntimeClass, an `agents-sandbox` service account limited to that namespace, and the proxy Service.
  - **`DockerSandbox`** (local development) applies the same rules with `docker run --runtime runsc`:
    - `--read-only`, `--user 65534`, `--cap-drop ALL`, `no-new-privileges`;
    - CPU, memory and pids limits;
    - tmpfs `/workspace` and `/tmp`, and no mounts;
    - input only through an owner-only env file.
    
    It runs on an `--internal` network whose only way out is a socat relay to the egress proxy. Docker's embedded DNS does not work under gVisor, so the relay's address is pinned with `--add-host` and the container gets no resolver: packages resolve nothing themselves.
- **Egress proxy** (`SandboxEgressProxy`, in the agents worker). It is the only route out: absolute-form HTTP and `CONNECT`.
  - Each call gets a random credential in its proxy URL, scoped to the image's hosts and expiring with the call's timeout. The credential is revoked when the call returns, however it ends, and every credential of a run is revoked when the run is cancelled.
  - The proxy checks, in order: the credential (407), then the approved host (403), then `EgressPolicy`. It connects to the address it checked, which closes the DNS-rebinding gap noted in 2.1.
  - Every decision is logged and summarised in the call's trace (`egress: api:443 allowed, other:443 denied`).
- **Execution** goes through `ToolExecutor`. After the pin, hash, retirement and argument checks:
  1. The catalog entry is re-read: it must be active, with the reviewed ref and the same input schema. An isolation runtime must be configured.
  2. A WRITE uses the 2.2 approval and effect-intent path. A package has no idempotency key, so a timeout or infrastructure error after starting waits for an operator.
  3. The validated arguments reach the package as `PDLC_INPUT`. The timeout is the smallest of the tool's, the image's and the run's remaining time.
  4. The package must exit 0 and print one JSON value that matches the image's output schema. Otherwise the model gets a tool error with the (capped) stdout.
  5. The proxy credential is redacted from anything returned.
- **Cancellation.** The workflow's ABANDON cancellation runs `markCancelled` while the tool activity is still blocked. `markCancelled` calls `ToolExecutor.cancelRun`, which revokes the run's credentials and then kills its containers or Jobs (`docker rm -f` by the `pdlc.run` label, or a Kubernetes deletecollection).
- **Configuration** (`pdlc.sandbox.*` in the agents `application.yml`):
  - `provider` is `none` (the default), `docker` or `kubernetes`;
  - `runtime` is the Docker runtime or the RuntimeClass;
  - the proxy's bind host, port and advertised host.

**Exit evidence** (live, under Docker with gVisor `runsc` 20250113: Postgres 16, a Temporal dev server, control-plane, agents with `provider=docker`, a stub LLM, a local registry for a digest-pinned probe image, and a Python upstream standing in for an approved and an unapproved API):

1. **Catalog.**
   - A workspace admin adding an image gets 403, and a tag-only ref gets 400.
   - The enterprise Admin adds `localhost:5000/pdlc/sandbox-probe@sha256:ab1f9c…`.
   - The sandbox tool and a pinning agent publish in `ops` and `finance`.
2. **Exit: the package can't read a host sentinel file.** Asked to `cat /tmp/pdlc-host-sentinel.txt` (present on the host), the package reports `hostFileReadable:false`, `uid 65534`, `dockerSocket:false`, and the kernel `Starting gVisor…`.
3. **Exit: the package can't see another workspace's artifact.**
   - A `finance` call writes `/workspace/artifact.txt`.
   - The next `ops` call, and the next `finance` call, both find `artifactFound:false, workspaceEntries:0`: every call gets a fresh workspace.
4. **Exit: the package can't reach an unapproved endpoint.** The run trace shows `egress: approved-api.local:18090 allowed, unapproved-api.local:18090 denied`, and the upstream logged only `GET /ok`.

   | Attempt | Result |
   |---|---|
   | Approved host, through the proxy | 200 |
   | Unapproved host, through the proxy | 403 |
   | Direct connection to the host | no route (`000`) |
   | Direct connection to 169.254.169.254 | no route (`000`) |
5. **Exit: terminating the sandbox revokes its credentials.**
   - A package posts its proxy URL to the approved API (a leaked credential) and sleeps. Used from outside, the leaked credential gets 200.
   - Cancelling the run removed the container within 2 s (exit 137). The same credential then gets **407**, and the run is `CANCELLED`.
   - The credential appears 0 times in the control-plane and agents logs, the model requests, a data-only `pg_dump`, and the run's workflow history (17 events). No env files or sandbox containers are left.
6. **Re-review.** Re-pinning the catalog entry to another digest makes the next run fail to start ("sandbox image probe now pins … needs re-review"). Restoring it lets runs start again.
7. **Kubernetes.** Checked against a k3s v1.31 API server with `infra/k8s/sandbox.yaml` applied, as the `agents-sandbox` service account:
   - `KubernetesJobSandboxClusterTest` passes (4 tests): the Job is accepted with every restriction, the NetworkPolicy is installed once, and a run and `terminateRun` leave no Job, pod or Secret behind.
   - The adapter's pod template passes the `restricted` Pod Security level (server dry run), while the same pod with privilege escalation is refused.
   - A delete returns with the `foregroundDeletion` finalizer, and the pod goes first.
   - The service account cannot create Jobs in `pdlc` or read Secrets.
   - Pods themselves cannot start in this CI container: runsc needs `CAP_SYS_RESOURCE` to set `oom_score_adj`, and the container lacks it. The Jobs therefore time out there. That run also found and fixed a bug: a Job's own `DeadlineExceeded` had been reported as exit code 1 instead of a timeout.
   - `scripts/sandbox-colima.sh` installs gVisor in the Colima VM, smoke-tests a gVisor pod, applies the manifest and runs the same cluster test. `--agents` also switches the agents Deployment to the kubernetes provider. The script has not been run on Colima as part of this change.
8. **Studio** (Playwright):
   - "New tool" offers "Sandbox image" with the catalog entries.
   - The editor shows the digest, the limits and the approved hosts, and the schema is read-only. The draft validates as publishable.
   - A re-pinned catalog image shows the re-review warning; a matching one does not.
   - The run trace shows the egress summary.
   - At 390 px wide, the page has no horizontal scroll.

## Slice 2.5 — A2A outbound adapter

- **Discovery:** fetch the Agent Card through the egress policy, then authorize the endpoint. The card itself is never a trust credential.
- **Protocol:** negotiate the interface and version; send a message or run the task lifecycle (submitted, working, input-required, auth-required, completed, failed, canceled); poll or stream.
- **Cancellation:** best-effort, with an acknowledgment state.
- **Registry:** `AgentSpec.runtime = "a2a"` names a connection and the remote skill.
- **Durability:** the remote task id is persisted as soon as it's known. The invocation identity is reused across retries, and an unknown outcome is reconciled through status.
- **Exit:** a controlled remote A2A agent completes a task, pauses at input-required, is cancelled, and fails remotely. Each case is shown with honest status semantics.

**As built:**

- **Registry.**
  - `AgentSpec` gains `remote {connectionId, skill}` (NON_NULL, so existing versions keep their hashes).
  - `runtime: "a2a"` requires `remote` and forbids a model binding and tools. The prompt still renders the message sent, and `limits.timeoutSeconds` and `outputSchema` still apply.
  - A new connection kind, `A2A_AGENT`, holds the agent's origin. It is egress-checked on save, granted to workspaces like tool connections, and may use a static bearer or OAuth client credentials.
  - Publishing and starting an a2a agent require that connection to be active, unexpired and granted. The run pins the connection, and it has no model (V23 makes `platform_runs.model` nullable).
- **Client** (`adapters/.../a2a/A2aClient`): the JSON-RPC binding of **A2A 1.0** or **0.3**, whichever the card offers, preferring 1.0.
  - 1.0 uses `SendMessage`/`SendStreamingMessage`/`GetTask`/`CancelTask`, ProtoJSON enums and `A2A-Version: 1.0`. 0.3 uses `message/send`/`message/stream`/`tasks/get`/`tasks/cancel` with `kind`-tagged objects.
  - The same rules apply as for the MCP client: the egress guard on every URL, no redirects, a hard deadline including a stalled event stream, byte caps, and `maybeSent` on timeouts.
  - Auth reuses `McpAuth`/`McpOAuth` (bearer, or RFC 9728 → 8414 → client credentials with `resource`).
- **The card is not a trust credential.** The platform takes only the skill list, the version and the JSON-RPC endpoint from it, and the endpoint must have the connection's origin (another origin is refused). The card is re-read on every invocation, and a skill it no longer offers fails the run without sending. The skill is sent as `metadata.skill`, since A2A has no standard way to address one.
- **Studio discovery.** `POST /api/workspaces/{ws}/connections/{id}/a2a-card` (AUTHOR, granted connection) runs `A2aCardWorkflow` in the agents worker and returns the name, version, streaming support and skills for the skill picker.
- **Durability** (V23 `platform_remote_sends`, `platform_remote_tasks`):
  - Every message has a stable id: `run:0` for the prompt, `run:<seq>` for a reply. Each is recorded INTENDED → SENT → ACKED, and the remote task id is saved the moment the agent answers.
  - A retry that finds a message SENT reconciles with `GetTask` on the saved task instead of sending again. When there is no task to ask about, the run fails as "outcome unknown … not resent".
  - Polling uses a 1–5 s backoff. It stops if the run is no longer RUNNING, and it counts against the agent's active time.
- **Status semantics:**

  | Remote state | Run |
  |---|---|
  | completed | `SUCCEEDED`: the artifacts' text is the output; a data part or JSON text is checked against `outputSchema` |
  | failed / rejected | `FAILED`, with the remote message |
  | canceled (by the remote agent itself) | `FAILED` ("the remote agent cancelled its task"): nobody cancelled the platform run |
  | input-required | `AWAITING_INPUT`: the question is stored as a `REMOTE_AGENT` message |
  | auth-required | `AWAITING_AUTH`: the platform cannot sign in for the agent, so only cancel ends it |
  | still working when the agent's time runs out | the remote task is asked to cancel, and the run fails |
- **Replies.**
  - `POST /api/workspaces/{ws}/runs/{id}/input {text}` (OPERATOR) atomically moves an `AWAITING_INPUT` run to `QUEUED`, so only one reply per question is accepted. It stores the reply as a `REMOTE_USER` message and signals `inputProvided`.
  - The next invocation sends the reply to the same remote task and context.
  - With no reply within 7 days, the remote task is asked to cancel and the run fails.
- **Cancellation** is best effort. `markCancelled` asks the remote agent to cancel and records `ACKNOWLEDGED`, `REFUSED` (not cancelable), `UNSUPPORTED`, `NOT_FOUND` or `FAILED` next to the run. The platform run is `CANCELLED` either way.
- **Studio:**
  - The agent form has a "Runs as" switch (Model / Remote A2A agent). The a2a form shows the connection picker, "Load skills" from the card, and a skill picker; the model, fallbacks and tools are hidden.
  - A run shows the remote task, its remote state and the cancel acknowledgement. `AWAITING_INPUT` shows the question with a reply box for operators, and `AWAITING_AUTH` explains that only cancel ends it.

**Exit evidence** (live: Postgres 16, a Temporal dev server, control-plane, agents, and a Python process serving two controlled remote agents: an A2A 1.0 agent at :4040 behind OAuth client credentials, which is polled, and an A2A 0.3 agent at :4041 with a static bearer, which streams):

1. **Setup.**
   - An `A2A_AGENT` at 169.254.169.254 is rejected, and reading a card before the grant gets 409.
   - After the grant, both cards read correctly: "Partner finance agent · 1.0 · polled" and "Partner research agent · 0.3 · streaming", each with 7 skills.
   - An a2a draft that names a model fails validation.
2. **Exit: completes.**
   - A2A 1.0: the remote log shows 401 → token issued (`resource=…/a2a`) → `SendMessage` (`A2A-Version: 1.0`, message `run:0`) → two `GetTask` → completed. The run is `SUCCEEDED` with the artifact as output and remote state `COMPLETED`.
   - A2A 0.3: `message/stream` completes in one streamed exchange.
3. **Exit: pauses at input-required.**
   - The run went `AWAITING_INPUT` with the question "Which region should the report cover?".
   - A REVIEWER-only user's reply got 403. The operator's reply "EMEA" moved the run to `QUEUED`, and a second reply got 409.
   - The remote log shows the reply as `SendMessage … messageId=run:2 taskId=<the same task>`. The run is `SUCCEEDED` (attempts 2), and both sends are `ACKED`.
   - The same flow passed through the Studio against the 0.3 agent (Playwright).
4. **Exit: cancelled.**
   - A cancelable remote task: `CancelTask → canceled`, the run is `CANCELLED`, and the remote cancel is `ACKNOWLEDGED`.
   - A non-cancelable task: `CancelTask → refused`, the run is still `CANCELLED`, the remote state is `WORKING`, and the remote cancel is `REFUSED`, as the agent answered.
5. **Exit: fails remotely.** The run is `FAILED` with "the remote agent reported failed: the partner ledger is unavailable".
6. **auth-required** held the run at `AWAITING_AUTH`, and cancelling ended it (the remote cancel was acknowledged).
7. **Card re-check.** With the skill removed from the card, the next run failed with "the remote agent's card no longer offers skill summarise", and nothing was sent.
8. **Reconcile after a crash.** The agents worker was killed (`kill -9`) while a remote task was working. After Temporal retried the activity, attempt 2 called `GetTask` on the saved task id and the run `SUCCEEDED`. The remote log shows exactly **one** `SendMessage`.
   - This run found a bug, which is fixed and covered by a test: the final remote state had not been recorded when the retry found the task already complete.
   - The same session also stopped abandoned activities from polling a task after its run was cancelled.
9. **No secret leaked.** The OAuth client secret, the static bearer and all 5 issued access tokens appear 0 times in the control-plane and agents logs, a data-only dump of the database, and 157 workflow-history events.
10. **Studio** (Playwright): the runtime switch, the skill picker loaded from the card, the paused run with the question and reply box, the completed run with its remote task and state, and no horizontal scroll at 390 px wide.

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
