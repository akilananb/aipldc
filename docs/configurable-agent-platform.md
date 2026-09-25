# Configurable agent platform: architecture and phased roadmap

> **Status: design only.** This document is an architecture and phased roadmap. Approving it does
> not authorize building the whole platform, changing application files or provisioning
> infrastructure. Each phase needs its own execution specification before implementation starts.
> All requested capabilities remain in the roadmap.

## Context

The goal is to evolve pdlc-pilot into a general-purpose agent platform. Administrators should be
able to compose agents, tools, context, memory, validation and workflows without a redeploy for
every configuration change. PDLC becomes the first governed domain template instead of the
platform's fixed process.

The deployment serves one enterprise with isolated workspaces, enterprise identity and centrally
governed integrations. Extensibility covers configuration and sandboxed execution.

## Current implementation evidence

| Area | Observed implementation | Architectural consequence |
|---|---|---|
| Lifecycle | `core/src/main/java/ai/pdlc/core/workflow/FeatureWorkflowImpl.java` implements a fixed flow: intake, story, G1, plan, build, G2, release, G3, deploy and monitor. `run()` uses `Workflow.getVersion("adaptive-grill-rounds", ...)` for an existing replay-compatible change. | Keep Temporal durability, but introduce a separate data-driven workflow type. Do not replace the implementation under an existing workflow history. |
| Configuration | `core/src/main/java/ai/pdlc/core/config/Profile.java` holds board/repo/notify/agents/gates. Project-level config (board, repos, gates, docs, brief) is already DB-backed: `V13__projects.sql` adds a `projects` table. `ProjectSeeder` seeds it once from `pdlc.yaml`, and afterwards Admins edit it through the `/projects` UI. Nothing in that table is versioned: edits change the row in place, with no draft→publish lifecycle, no immutable versions and no pinning to a run. Agent roles, prompts directory and gateway still load from YAML at startup. | Database-backed *published, versioned* definitions become the source of truth for new platform runs, building on the existing `projects` table rather than competing with it. YAML stays a deployment bootstrap, not a second live editor. |
| Prompts | `agents/src/main/java/ai/pdlc/agents/templates/PromptTemplates.java` renders Mustache from an override directory or the bundled classpath, and re-reads files on every invocation. | Reuse the Mustache syntax, but snapshot published prompt content. Editing a file during a run must not change that run's pinned definition. |
| Agents/models/skills | Per-domain agent classes and role dispatch are fixed. Model names are registered at application startup. Discovery found two packaged grill skills embedded as instructions, not a general skills registry. | Introduce a definition-driven native runner and a model catalog. Creating an agent must not require a Java class or Spring bean. |
| Context | `agents/src/main/java/ai/pdlc/agents/activities/AgentContext.java` reads board items/comments and individual repo files. On failure it returns null or empty, and `readFile()` fails silently. No ingestion, vector search, memory lifecycle or context budgeting was found. | Knowledge and memory need new persistent services, not renamed audit records or a process-local cache. |
| Identity | `control-plane/src/main/java/ai/pdlc/controlplane/identity/IdentityResolver.java` trusts the `X-User` and `X-Role` headers. Discovery found environment-based `SecretsPort` resolution, not scoped tool credentials. | Enterprise authentication, workspace authorization and credential isolation must come before configurable execution. |
| ACP execution | `build-worker/src/acp.ts:runAcpSession` spawns `npx --yes acpx@latest ... --approve-all` on the host with `env: process.env`. | The existing code is an ACP integration starting point, not an isolation boundary. Do not expose its current host-command configuration to workspace admins. |
| UI | `ui/package.json` has React 18, Router 6, TanStack Query, Radix Themes and CodeMirror, but no canvas library and no test script. Discovery found item/review routes and reusable Surface/mutation patterns, including the Admin `/projects` editor, but no admin studio. | Extend the existing UI and design system. A workflow canvas is new; the current task-status graph is not an authoring engine. |
| Storage/migration | Spring Data JDBC record entities and additive Flyway migrations, currently through `V14__build_task_leases.sql`. Temporal history owns execution progression; application Postgres holds projections and audit. | Keep this split. Work out the next unused migration number at implementation time; do not use the older repository guide's `V7` example. |

## Approach

### 1. Establish the platform boundary and ownership

Use four logical areas. Do not create a microservice for every feature; see
[Scalability and service boundaries](#scalability-and-service-boundaries) below:

- **Authoring/control plane:** identity, workspaces, asset registries, publication, connection grants, run entry points, approval inbox and audit. Extend `control-plane/` using the existing record/entity/repository/controller conventions.
- **Durable runtime:** a new generic workflow interpreter in `core/` on Temporal. It coordinates deterministic graph transitions, human waits, timers and activity results. It never makes model calls, HTTP requests, script runs or database reads itself.
- **Execution workers:** keep native reasoning in `agents/`. Keep the Node build-worker's claim/heartbeat/cancellation pattern for coding. Add isolated execution capacity for tools, connectors, document parsing, custom verifiers and approved extension packages. Keep queue ownership separate: never put disjoint activity registrations on the same queue.
- **Knowledge/context services:** durable source metadata, ACLs, retrieval and memory APIs, owned by the control plane. Ingestion and extraction run asynchronously in restricted workers. Every agent runtime uses the same authorized context service rather than keeping a private, inconsistent copy.

Keep Java/Spring, Temporal, React and Postgres. Keep Embabel as the native model integration rather than adding a second native agent framework. Add pgvector and Postgres full-text search for hybrid retrieval, plus object storage for original documents and large artifacts.

#### Scalability and service boundaries

**Decision: the control plane is a modular monolith; execution is independently scaled workers.**
We don't split the platform into a microservice per feature. The load that grows with usage is
model calls, tool and sandbox execution, document ingestion and retrieval. All of that already runs
outside the control plane, in workers that scale on their own. The control plane does
authorization, definitions and projections. Splitting it now would add network hops, distributed
transactions and more deployments, without removing any bottleneck.

**Module boundaries inside the control plane.** Each module is a package that owns its tables and
exposes a service interface. No module reads or writes another module's tables. Cross-module work
goes through that module's service, or through Temporal for anything long-running. These rules keep
extraction cheap later.

| Module | Owns | Examples |
|---|---|---|
| Identity & workspaces | sessions, service identities, `workspaces`, `workspace_members` | `WorkspaceService.require` is the one access check every module calls |
| Registry | `agent_definitions`, `agent_definition_versions`, and later skills, tools, workflows and policies | `AgentRegistryService.resolveForRun` |
| Runs | run projections, artifacts, approvals inbox | Starts Temporal workflows; never reads registry tables directly |
| Connections | connection metadata, secret references, model catalog | Credentials are resolved only inside execution adapters |
| Knowledge & memory | collections, sources, chunks, embeddings, memory records | Ingestion workers write through this module's API |
| PDLC package | today's `work_items`, `comments`, `prs`, `review_events`, … | Uses the platform modules; they never depend on it |

New platform tables use a module prefix where the name would otherwise be ambiguous, for example
`platform_runs` and `knowledge_sources`.

**How each component scales:**

| Component | Scaling approach |
|---|---|
| control-plane | Stateless replicas behind a load balancer. Once there is more than one replica, browser sessions are stored in Postgres (Spring Session JDBC), so there are no sticky sessions. |
| agents (native reasoning) | Worker replicas on the `REASONING` queue. Model concurrency is limited per provider connection, not per pod. |
| Sandbox / tool / ingestion workers | Separate pools on their own Temporal queues, sized for their workload (CPU for parsing, network for connectors). |
| build-worker | Pull-based claim/lease, so more hosts means more parallel builds. |
| Temporal | Managed or clustered Temporal. Large payloads go by reference, keeping workflow history small. |
| Postgres | Vertical scaling first, then read replicas for retrieval and projections. pgvector indexes are sized per collection. |
| Object storage | Holds originals and large artifacts, so the database stays metadata-sized. |

**When a module becomes its own service.** Extract a module only when there is evidence for it:

- It needs a different scaling profile or hardware (for example GPU embedding, or a large search index).
- It needs a different release cadence or on-call ownership.
- It needs a stricter isolation or compliance boundary.

Knowledge ingestion and retrieval is the most likely first candidate. An extracted module keeps its
tables and service interface. Other modules then call it over HTTP or gRPC, authenticating with the
same service identities that Phase 1 slice 2 introduces for workers. It does not share a database
with the modules that call it.

- The application database stores metadata, grants, definitions, run projections and memory records.
- Temporal stores execution history.
- A graph index, when enabled, is a derived retrieval view. It is never the authority for approvals, permissions or source content.

The general platform resources are:

- Workspace
- AgentDefinition
- SkillDefinition
- ToolDefinition
- Connection
- WorkflowDefinition
- ValidationPolicy
- KnowledgeCollection
- ContextPolicy
- MemoryPolicy
- Run
- Artifact

These names describe the architecture. They are not existing Java classes or final wire schemas.

PDLC-specific stories, board mappings, repository actions, gates and release documents live in a versioned PDLC package built on those resources. Do not make every run require a `WorkItemRef` or PDLC `CanonicalState`.

### 2. Make publication, not mutable files, the configuration contract

Agents, skills, tool schemas, workflows, validators and context/memory policies share one lifecycle: **draft → validated → published immutable version → retired**.

- Editing a published resource creates a new draft.
- Rollback selects an earlier published version for new runs.
- A version that run provenance still needs cannot be physically deleted.

Publishing is atomic. It validates and pins the full dependency set: prompt and skills, model binding, tools, workflow nodes, schemas and policies.

- Missing or retired dependencies, or incompatible inputs and outputs, block publication with errors that say what to fix.
- Concurrent edits fail as conflicts instead of silently overwriting another administrator's draft.
- Import/export carries definitions and dependency references. It never carries credentials, private memories or an implicit trust grant.

At start, a run receives a resolved, immutable definition bundle and its content hashes. New publications affect only new runs, never a run already executing. Pinning a definition does not pin permissions: credential revocation, removal of workspace access and platform emergency-deny policies still stop future protected operations. An executing graph is never edited live.

The existing configuration becomes the first published PDLC assets:

- Import the current YAML role settings, bundled prompts and the existing `projects` rows (from `V13__projects.sql`).
- Record the import once.
- Do not keep overwriting admin edits from YAML, and do not silently fall back to old files when a registry definition is missing.
- Existing workflows keep their old behavior until the explicit migration in step 10.

### 3. Put identity, credentials and execution policy below every feature

**Authentication.** Replace the header shim on the enterprise surface with enterprise OIDC through Spring Security: a backend-managed browser session, HttpOnly cookies and CSRF protection. Worker and API traffic uses scoped service identities; never reuse a human's browser session.

**Workspace access.** Enforce it at every resource lookup, database query, run operation, artifact fetch, search and memory retrieval. Hiding navigation is not enforcement.

**Capabilities.** Keep these separate:

- Enterprise administrators approve identity issuers, model/provider connections, egress destinations, executable images and enterprise policies.
- Workspace administrators manage workspace membership and publish assets within those grants.
- Authors edit drafts.
- Operators run and cancel workflows.
- Reviewers approve designated artifacts.
- Knowledge curators publish collections and promoted memories.

One person may hold several capabilities, except where a workflow enforces separation of duties.

**Credentials.** Credentials belong to Connections, never to prompts or tool-definition JSON.

- Distinguish a workspace service connection from a user's delegated connection.
- The authorized execution adapter resolves short-lived credentials. The model and the browser never receive the secret value.
- Connection types cover API keys/PATs, OAuth delegated authorization, OAuth client credentials and mTLS.
- Track consent/scope, expiry, rotation and revocation state. A revoked or expired connection fails visibly; it never falls back to a broader credential.

**Tool policy.** Check policy immediately before each execution, covering:

- current workspace and actor/run identity
- published tool allowlist and approved operation
- destination and data classification
- argument schema
- budget
- required approval

Tool descriptions and MCP annotations are untrusted metadata, not authorization. Writes and destructive actions require a policy-authorized path. An approval binds to the exact operation and arguments, or the exact artifact version, and any change invalidates it.

**Outbound network policy.** This covers tool calls, OAuth discovery, A2A cards, imports, redirects, callback URLs and source ingestion.

- Deny arbitrary internal, metadata and link-local targets.
- Explicitly approved enterprise private endpoints are allowed only through enterprise-configured destination rules.
- Check DNS resolution and redirects and restrict network egress; URL string validation alone is not enough.

**Sandboxed execution.** Scripts, local MCP servers, ACP agents, parsers and custom verifier packages run as isolated workloads:

- non-root, immutable images
- resource and time limits
- restricted filesystems and a per-run workspace
- an explicit environment allowlist
- egress denied by default
- no host filesystem mounts and no container-runtime socket

Uploaded or untrusted executable packages need an isolation-capable runtime. If none is available, disable that execution class; never fall back to launching on the host. Pin executable, image and package versions instead of using `@latest`. Remove the inherited full-host environment and blanket auto-approval from the configurable execution path.

Cancellation and timeout terminate the whole isolated workload and process tree and revoke its temporary credentials. Stopping only a wrapper process is not enough.

### 4. Build Agent Studio, skills and the governed tool registry

**Agent Studio.** Customization is configuration, not generation of a new Java class. Each agent definition covers:

- identity and description
- native or external runtime
- prompt editor with declared variables
- input and output schema
- model binding and generation limits
- skill versions
- granted tools and knowledge collections
- context and memory policies
- delegation rules and execution budgets

The Studio also offers draft preview, version comparison, test invocation, publication history and disable/retire controls.

**Prompts.** Use the existing renderer's Mustache syntax with a restricted, data-only variable map. Admin templates must not read arbitrary filesystem paths or load executable partials. Render published content and check that required variables are present before invoking the model.

**Models.** Model selection comes from an authorized model catalog resolved at runtime.

- A new model binding takes effect without restarting agent roles.
- An unavailable model produces an explicit error.
- An alternate model runs only when the published agent declares an allowed fallback.
- Do not carry today's silent default-model behavior into the new contract.

**Skills.** A skill is a versioned package of instructions, examples, supporting resources and declared requirements.

- Importing a skill does not grant the tools it requests.
- Instruction-only skills stay text. Executable resources go through the sandbox extension path.
- Imported instructions cannot override enterprise policy.
- Keep the existing grill skills as seed content, but do not re-enable an unbounded skill/tool exploration loop.

**Native tool loop.** Native agents use an explicit, bounded loop:

1. Assemble context.
2. Get the model response.
3. Validate any requested tool call.
4. Check policy and approval.
5. Execute the tool.
6. Bound the result.
7. Take the next turn, or return typed output.

Limit model turns, tool calls, nested delegations, wall time and provider-reported usage. If a remote token count can't be measured, show it as unknown. Never fabricate it or present it as an enforced token cap.

**Tool registry.** Keep reusable operation definitions separate from authenticated connection instances:

- **API tools:** import OpenAPI, or define an explicit method, path and request/response schema. Publish only the selected operations. Remote references and server URLs go through the same import/egress policy.
- **MCP tools:** discover, preview and approve individual tools, and keep a versioned fingerprint of each tool's schema and description. A capability change needs review before a new version can run. Support remote HTTP MCP with its authorization flow. Local stdio servers run only in the sandbox, with scoped environment credentials.
- **Custom tools/verifiers:** signed or enterprise-approved immutable worker packages that declare their input/output schema, required capabilities and limits. Workspace admins configure approved packages; installing an arbitrary executable is an enterprise-controlled action.

Direct workflow tool nodes and model-requested tool calls go through one policy-enforcing execution path. A prompt must never be able to reach a privileged side door that the workflow designer cannot use.

### 5. Integrate external agents without erasing protocol differences

Define one invocation model. It carries the workspace, run/node invocation identity, pinned agent version, typed input, permitted context and artifact references, deadline and cancellation.

- Persist remote task and session identifiers.
- Normalize returned artifacts, status and errors, but keep provider-specific capabilities.
- Report honestly when a provider doesn't support streaming, cancellation, authentication or usage accounting.

Remote artifact URLs and returned metadata are untrusted. Fetch referenced content only through the authorized egress and content-ingestion path. A successful remote agent call does not authorize arbitrary follow-on downloads or rendering in the browser.

| Adapter | Intended role | Required behavior |
|---|---|---|
| Native | A definition-driven agent on the retained Embabel/model stack | Enforce context/tool policy and output contracts locally. |
| A2A | Remote autonomous agents and governed agent-to-agent delegation | Discover an Agent Card and authorize the endpoint. Negotiate the supported interface and version. Handle immediate messages or the task lifecycle, artifacts, input-required and auth-required states, and polling, streaming and cancellation where supported. An Agent Card is not a trust credential. |
| ACP | Coding/session agents controlled by a client | Reuse the local ACP experience in an isolated runner, with filesystem/terminal/permission mediation and cancellation. Support approved remote ACP transports through a capability-tested adapter; don't treat every URL as ACP. |
| REST | Existing HTTP agent services without A2A | Declare a synchronous response, or an asynchronous task/status/cancel endpoint mapping. Also declare schemas, authentication, deadline and remote idempotency support. Never guess job states from arbitrary JSON. |
| gRPC | Existing protobuf services without A2A | Register descriptors and the service/method and message mapping. Use TLS/mTLS and deadlines. Permit only declared unary or streaming operations. Reflection does not automatically publish every server method. |

Protocol terms:

- A2A defines its own REST/HTTP, JSON-RPC and gRPC bindings. These are distinct from the generic REST/gRPC adapters for non-A2A services.
- ACP here means Agent Client Protocol, matching this repository's coding-agent usage.
- MCP exposes tools and resources. It does not replace A2A task delegation.

Delegation works in both directions:

- Allow outbound A2A delegation, and an authenticated A2A ingress for explicitly published platform agents and workflows.
- Internal delegation uses the same invocation and policy model, without requiring HTTP between local agents.
- Bound recipient allowlists, delegation depth, total run budget and the data leaving the workspace.
- Remote agents receive only approved context. They never get unrestricted platform memory or credential access.

**Remote writes and retries:**

- Record the effect intent before a remote write.
- Reuse one stable invocation/idempotency identity across retries, and store the remote task ID as soon as it is known.
- A timeout is an unknown outcome, not proof of failure. Reconcile through remote status or idempotency support. If the provider offers neither, pause for an operator to resolve it.
- Never blindly retry non-idempotent effects.
- Cancellation is a best-effort remote cancellation with an explicit acknowledgment state. It does not undo effects that already completed.

### 6. Organize knowledge before adding autonomous memory

A KnowledgeCollection is a workspace-scoped set of versioned source documents and their evidence. It is not an unstructured folder of prompt attachments.

For each source, track its owner, connector, source URL/ID, version or commit, extraction status, classification, ACLs, content hash and synchronization state. Organize by source hierarchy, project/collection, document type, labels and source-backed entity links.

**Ingestion** runs asynchronously, in this order:

1. Authenticate the source.
2. Enumerate the approved scope.
3. Fetch and version.
4. Scan and parse.
5. Extract text, structure and media.
6. Chunk with locators.
7. Embed and index.
8. Publish the searchable version.

Ingestion rules:

- Partial and failed imports stay visible. A failed fetch is never recorded as an empty successful document.
- Deduplicate only within the authorization boundary, and keep revision provenance.
- Handle rate limits and resumable synchronization, and process deletions and permission changes.
- Immutable object storage holds the originals. Postgres holds metadata, chunk locators, full-text and vector indexes, and grants.

**Required source paths:**

- **Uploads:** PDF, DOCX, PPTX, text and Markdown, plus images and scanned PDFs through OCR. Enforce MIME, size, parser and resource limits. Quarantine malformed, encrypted or unsupported files with a clear failure reason. Never run embedded macros or follow instructions found inside a document.
- **Confluence:** selected spaces, pages and attachments, with page hierarchy, version, restrictions and incremental refresh. Cloud REST v2 is the default integration. Data Center is a separately declared connector variant and is never silently treated as Cloud.
- **Figma:** selected files, node/layer structure, components, versions and permitted rendered images, with links back to the file and node. Keep the visual context instead of flattening every design into text. Use multimodal input only with approved, capable models.
- **ADO repositories:** selected organization/project/repository/path and commit-based snapshots through the Git API. Sync changed files and cite file, line and commit. The existing ADO board adapter is not an ADO repository connector. Read-only knowledge indexing is separate from a write-capable coding/repository connection.

**Retrieval** combines lexical and vector search with rank fusion and bounded reranking. Citations point to document/page/node/file/line/version.

- Apply ACL filters before ranking and before exposing snippets, counts, entities or graph paths.
- Enforce the effective actor/run grants and the upstream entitlement, not the broad reach of the ingestion service account.
- If upstream permissions can't be mapped, keep the source private to the authorized connector owner and disable shared retrieval.
- Revalidate current access at delivery. Expired or unknown entitlement is denied; it is never served from a stale cache.

**Trust and lifecycle:**

- External content is evidence with provenance and trust labels, never system instructions.
- Deleting or revoking a source invalidates its retrieval results, caches, derived summaries, memory facts and graph edges.
- Physical deletion follows the retention policy; audit keeps only permitted non-content metadata.
- Each index declares its embedding model and version. Switch an index only after a complete rebuild. Never mix incompatible vectors or publish an incomplete extraction as ready.

**Derived outputs.** Generated answers, artifacts, context traces and summaries inherit the intersection of their sources' access restrictions and applicable classification. Being in the same workspace does not grant access to every derived output. Widening visibility needs an explicit, authorized publication or declassification action; the model can't make that decision. Source revocation and deletion apply to these derived outputs as well as to retrieval indexes.

### 7. Make context assembly and memory separately inspectable

Context engineering becomes a first-class, configurable pipeline, shared by native agents and external-agent requests:

1. Resolve trusted platform policy and the pinned agent and skill instructions.
2. Add the user task, bounded conversation state and typed upstream artifacts.
3. Retrieve the authorized source passages, facts and past outcomes relevant to this invocation.
4. Deduplicate and rank. Reserve budget for model output and tools. Compact conversation and evidence, keeping source pointers. Never silently discard policy or required task input to make the context fit.
5. Produce a context manifest: selected source versions, citations, retrieval reasons, excluded or trimmed items, memory IDs and token accounting. Store sensitive payloads only in access-controlled content storage, never in raw Temporal history or public logs.

Admins configure collection selection, recency, retrieval depth, budgets, summarization, memory scopes, and which sources are required or optional.

- A required source that fails blocks the invocation with a visible reason. An optional source that fails produces a visible warning.
- If trusted instructions plus required input exceed model capacity, reject the invocation. Never silently truncate.
- Cache keys include workspace, authorization scope, source versions and policy, not just the query string.

A **Context Inspector** answers "What did this agent see, why, and what was omitted?" without exposing the model's private chain-of-thought.

Memory is not one unlimited chat transcript:

| Memory kind | Content and scope | Write/retention contract |
|---|---|---|
| Short-term/working | Current session messages, intermediate state and bounded summaries for one run/thread | Durable enough to resume, and expires under workspace retention. Temporal holds control state; content storage holds large messages and artifacts. A cache alone is not durability. |
| Episodic | Summaries of completed and failed tasks: goal, relevant actions, evidence, reviewer feedback and measured outcome | Append episodes linked to provenance. Keep failures recorded as failures, and never learn a "successful" procedure from an unverified result. Retrieval stays scoped to workspace, project and actor. |
| Long-term semantic | Approved facts, preferences, decisions and constraints, with source and validity history | Agents propose candidates; authorized curation or an explicit published policy promotes them. Contradictions are handled by correct, supersede or retract, never by silent replacement. |
| Procedural | Reusable instructions and validated successful practices | Published as versioned skills or templates, never as an opaque memory instruction that bypasses review. |

**Memory administration** supports inspect, search, correct, forget, retention and permitted scope sharing.

- Personal preferences are private by default. Sharing across workspaces requires explicit publication.
- Derived memories inherit the intersection of their sources' access restrictions.
- Forget and delete propagate to embeddings, graph indexes, caches and derived summaries. Audit and history must not re-expose erased content.
- None of these memory types needs automatic fine-tuning or self-modification.

**Graphs:**

- Start with explicit source/artifact/entity relationships in Postgres.
- Add Graphify as an isolated, optional enrichment provider for code/document relationships and impact queries. Keep the labels that distinguish extracted from inferred edges.
- Add a self-hosted Graphiti adapter, backed by a supported graph database, for temporal facts and episodic relationships only, and only after the evaluation gate below.
- Graphs complement hybrid retrieval and source authority; they don't replace them.
- A path through a hidden entity must not reveal that entity or any otherwise forbidden relationship.

### 8. Make workflows executable data, with explicit loops and verification

**One graph document.** Forms, canvas, API/import and chat authoring all edit one canonical, versioned, typed graph document.

- Canvas positions are presentation metadata; runtime semantics never depend on them.
- The backend validates a published graph and compiles it into the Temporal execution plan.
- A workflow is not limited to a DAG: structured loops are allowed, but arbitrary cyclic wiring is rejected.

**Node types:**

- input/start and artifact/output
- agent, tool and retrieval
- transform
- branch, and parallel fork/join
- bounded foreach, bounded loop and subworkflow
- human input and approval
- validation and verification
- wait/timer

**Triggers:** manual, authenticated webhook and scheduled triggers, each bound to a published version.

**Expressions:** a restricted, deterministic expression language. No JavaScript, Python or SpEL evaluation inside the workflow process. Runtime code and interpreters remain deployed software; new prompts, schemas and graph compositions are configuration.

**Publication checks.** Publication rejects:

- missing nodes or references, and invalid types
- unreachable required work
- ambiguous branch routing and undeclared merges
- unauthorized tool dependencies
- unbounded loops or recursion
- invalid approval roles

Parallel branches write to separate output namespaces. An explicit transform merges them; there is no last-writer-wins mutation. Subworkflows pin their definitions and share the parent's global limits.

**Loops and waits:**

- Every loop declares continuation and termination conditions, a maximum iteration count, an elapsed-time limit and a budget. Exhaustion produces an explicit failed or escalated outcome, never success.
- Retries for transient errors are separate from deliberate revise-and-recheck loops.
- Human waits are durable. A timeout escalates or ends the run as specified; it never auto-approves.
- Long histories use checkpoint/continue-as-new boundaries without resetting the global budget or losing pending input and approval identities.
- Large content travels as authorized artifact references rather than accumulating in workflow history.

**Validation versus verification:**

- **Validation:** schema, required fields, deterministic rules and policy checks, applied before invocation or publication and after output.
- **Verification:** independent evidence that the task succeeded: tests, build results, artifact checks, API probes, comparison with source material, or human review.

Model-based rubrics are configurable assessments, not objective proof. They can't override a failing required deterministic check or a missing human approval.

Administrators configure reusable rule sets and rubric versions, required or advisory severity, expected evidence, repair-loop bounds, and failure/escalation routing. Validators return structured findings linked to artifact versions. Custom executable verifiers use the same isolated worker boundary as tools. Arbitrary validators never run in a controller or in a Temporal workflow thread.

**Approvals:**

- An approval binds to the exact artifact or definition version and to the reviewer's capability.
- Revising an artifact invalidates earlier approvals of that artifact.
- The PDLC template keeps maker/checker separation and the current blocking-comment behavior.

**Dynamic planning.** An agent may plan its own steps only inside a bounded delegated-task node. The generated steps go through the same schema/policy compiler. They cannot add privileges, rewrite the parent graph or grant themselves approval.

### 9. Provide one coherent administration and authoring experience

**Navigation.** Extend the existing `ui/src/App.tsx` routing and AppShell navigation with workspace selection and these sections: Agents, Workflows, Tools & Connections, Knowledge, Memory, Runs & Approvals, Administration.

- Skills, models and evaluation policies are managed catalogs, linked from the editors that use them.
- Reuse the existing Surface tabs/inspector, design tokens, query error display and mutation/invalidation patterns, including the Admin `/projects` editor.
- Workspace identity belongs in every query cache key.

**Workflow Designer.** Use `@xyflow/react` (React Flow) as a canvas component, not as an execution engine.

- Provide a node palette, typed ports, explicit loop containers, property forms, a validation panel, version comparison, test run and an execution overlay.
- Offer a keyboard-accessible structured list/form editor that is equivalent to canvas editing.
- Make empty, loading, conflict and authorization-error states explicit.
- Forms for inputs and human tasks are derived from published schemas.
- Don't accept arbitrary admin-supplied React or HTML as a UI extension mechanism.

**Chat designer.** The chat designer is another author of the same draft graph. Example request: "Read the Figma design and Confluence requirements, ask an architect and QA in parallel, revise at most twice, then get a lead's approval." The chat designer:

- resolves only catalog assets visible in that workspace
- asks when a requirement would change privileges or behavior
- proposes a graph diff and explains its tool and data access
- validates the diff and runs a non-production simulation

The user accepts the diff and publishes it as a separate step. Conversation alone never grants connections, executes production writes or silently publishes a graph. Stale drafts are handled as revision conflicts; a patch is never applied invisibly to a newer draft.

**Test mode** is isolated from production and visibly different.

- By default it uses recorded fixtures or safe read-only calls. Writes stay disabled unless explicitly authorized for a test connection.
- It traces actual inputs and outputs, tool requests, approved actions, evidence, elapsed time and available usage.
- A generated rationale is never presented as an execution log.

**Run Explorer** joins the graph trace, pinned asset versions, context manifest, tool policy decisions, artifacts, validation results, approvals and outcomes.

- Replaying a recorded execution for inspection is different from a new execution, which can produce new LLM output or side effects.
- Provide cancel, pause-at-safe-boundary and an enterprise kill switch.
- Never promise that cancellation reverses a remote action.

### 10. Migrate PDLC without breaking active work

**The PDLC package.** Publish the existing PDLC as a package of agents, prompts/skills, workflow definitions, artifact schemas, board/repo actions, validators and role/gate policies. It must preserve:

- adaptive clarification
- sequential processing of split stories
- bounded automated repair
- task and build verification
- blocking comments
- G1/G2/G3 approvals
- release documents
- both audit trails

Adding a graph engine must not turn today's deliberately sequential story processing into parallel execution as a side effect.

**Starting new runs.** Add the generic interpreter as a new Temporal workflow type.

- Use `control-plane/src/main/java/ai/pdlc/controlplane/temporal/FeatureWorkflowStarter.java` as the anchor where the new type plugs into the existing start boundary.
- Resolve the chosen published PDLC workflow version before starting new work.
- Keep workflow-start idempotency.
- Persist which runtime and workflow type owns each work item, so controllers route queries and signals correctly.
- Existing executions stay on their original implementation and configuration. Never reinterpret history or transplant a waiting gate into the new graph.

**Cutover and drain.**

- Keep the old deployment only to drain existing executions and for the supported history replay/reset window. This planned drain is not a permanent compatibility mode.
- After parity is verified, route all new PDLC work through the published template.
- Once they are no longer needed, remove obsolete starters and dispatch, and the hardcoded UI role and mention catalogs.
- At implementation time, find callers with LSP references. Areas observed so far: workflow stubs, the item/artifact/PR/release controllers, agent activities, the UI gate and mention lists, and worker registration.

**Audit.** New general workflows use the platform's run/artifact audit and don't need a git repository. The PDLC package also calls the existing review-trail behavior, so `review.md` and `review_events` stay aligned.

**Domain separation.** Demonstrate one non-PDLC workflow that has no story, repository or release fields.

## Phased delivery roadmap

Each phase is a user-visible, end-to-end increment, not a scaffolding milestone. Phases are not estimates or single-sprint commitments. Later implementation specifications should follow these boundaries rather than attempt the whole platform in one change.

| Phase | Deliverable | Prerequisite and parallelism | Exit evidence |
|---|---|---|---|
| 1 — Secure Agent Studio | Enterprise sessions, isolated workspaces, service identities, scoped connection references, immutable asset publication, an authorized model catalog, a native agent/skill/prompt editor, and a single-agent durable run with bounded working context. | First. Reuse the existing UI and native model integration. Establish the generic run identity/version contract here; don't create a temporary, unrelated runner. | An admin publishes a new agent without code changes or a restart. An existing run stays pinned after a revision. Another workspace cannot discover, read or run the agent. |
| 2 — Governed execution and federation | Authenticated API and MCP tool registry, sandbox execution, the native tool loop, external ACP/REST/gRPC/A2A adapters, bounded A2A delegation and selectively published A2A ingress. | Depends on phase 1. Once the invocation, policy and credential contracts are fixed, adapters can be built independently; each must pass its real lifecycle tests. | Each adapter completes a real task and shows the cancellation/status semantics it supports. Secret isolation, denied tools and unknown-outcome handling are demonstrated. |
| 3 — Knowledge and context | Uploads; Confluence, Figma and ADO repository connectors; organized collections, provenance, hybrid retrieval and the Context Inspector. | Depends on phase 1 plus the shared connection/egress boundary from phase 2. Connector work is independent of advanced workflow authoring and of the other protocol adapters. | A cited answer uses authorized evidence across sources. Revisions update citations. Revocation removes access. Failed imports stay visibly failed. |
| 4 — Dynamic runtime and checks | Typed graph compiler and interpreter; branches, parallelism, structured loops and subworkflows; human tasks and gates; custom validation and sandbox verification; typed artifact state and run trace. | Depends on phases 1–2; can run alongside phase 3. Start with form/structured authoring of the same graph that the canvas renders later. | A revise/verify loop exits correctly, exhaustion fails visibly, a restart resumes a human wait, and non-idempotent timeouts don't duplicate effects. |
| 5 — Visual designer and PDLC template | React Flow canvas plus the equivalent form editor, graph validation and run overlay; the published PDLC package, a generic Run Explorer and a safe cutover for new runs. | Depends on phase 4. Context-enabled examples also need phase 3. | Canvas → save → reload keeps the executable meaning. Full PDLC parity passes while a legacy run still finishes. A non-PDLC run needs no PDLC record. |
| 6 — Conversational authoring | A workspace-aware design chat that proposes validated graph diffs, with test simulation, accept/discard, version conflicts and separate publication. | Depends on phases 3–5. It uses the existing compiler and catalog and does not define a second workflow language. | A natural-language request produces the intended graph and bounded loop. Unavailable tools are not invented. Chat cannot bypass publication or credential consent. |
| 7 — Governed memory and graph enrichment | Episodic capture, curated long-term facts, procedural promotion to skills, memory administration and deletion; an explicit knowledge graph, Graphify enrichment and an evaluated Graphiti temporal adapter. | Requires phase 3 and the run/evidence model from phases 1 and 4. Can run alongside phase 6. Short-term memory already works from phase 1. | Correct/supersede/forget changes later retrieval. Temporal and impact queries cite evidence. Graph retrieval never crosses ACL boundaries. |

## Technology decisions and better options

- **Keep Temporal; don't replace it to get a canvas.** Its durable waits and activity boundary are assets. React Flow handles authoring and the typed interpreter supplies runtime behavior. LangGraph is a credible runtime for independently hosted Python/TypeScript agents, which the platform can call through A2A/REST, but it must not become a second owner of the same enterprise gate state.
- **Compose the platform from maintained components.** Reuse protocol SDKs, OIDC, React Flow, Postgres/pgvector and object storage. Build only the product-specific parts: the publication model, policy boundary, workspace semantics, run evidence and the PDLC package. Don't invent a protocol or a distributed state engine.
- **Graphify and Graphiti solve different problems.** The inspected Graphify v8 documentation describes local AST-based code graphs and semantic document/media extraction with explained edge types. Graphiti documents temporal facts, provenance and episodic graph construction, and requires running its own graph infrastructure. Neither project's marketing benchmark proves it suits this corpus.
- **Graph adoption is gated on evidence, not mandatory from day one.**
  - The production baseline is hybrid retrieval plus explicit relationships.
  - In phase 7, run the impact and superseded-fact scenarios against that baseline first, then against Graphify and Graphiti.
  - Enable an adapter only if it fixes a recorded retrieval failure within the workspace's configured latency and cost limits and passes every access and deletion check. Otherwise leave it disabled and keep the benchmark result.
  - Either way, the platform still delivers organized knowledge and all three memory classes.
- **Dify is the concrete buy-versus-build reference.** Its published features overlap visual workflows, model management, RAG and agents. If differentiated enterprise governance and the existing PDLC runtime are not strategic, adopting Dify deserves a separate product decision. This roadmap keeps the existing product. Don't embed a second complete authoring/runtime platform inside it and inherit two permission, versioning and execution models. Existing Dify-hosted agents can integrate as external agents after their interface and auth are validated.
- **High-value additions already included:** context inspection, version comparison, the replay-versus-rerun distinction, evaluation before publication, source lineage, revocation and a kill switch. These make configuration safe to operate. An unrestricted plugin marketplace or an autonomous self-rewriting platform is not required.

## Critical files and anchors

These are non-obvious integration anchors, not a complete list of files to change. Reread them when planning each phase, and check symbol references before changing public contracts.

- `core/src/main/java/ai/pdlc/core/workflow/FeatureWorkflowImpl.java`: `run()` and its `Workflow.getVersion` branch. Preserve historical execution and sequential story semantics.
- `control-plane/src/main/java/ai/pdlc/controlplane/temporal/FeatureWorkflowStarter.java`: `start(WorkItemRef)`. Its deterministic workflow ID and duplicate-start handling must survive the new-run routing.
- `agents/src/main/java/ai/pdlc/agents/templates/PromptTemplates.java`: `render`/`load`. Separate the reusable template syntax from the mutable-file lookup, which can't define a pinned run.
- `control-plane/src/main/java/ai/pdlc/controlplane/projects/ProjectSeeder.java` and `V13__projects.sql`: the existing DB-backed project config, and the seed source for the first published PDLC assets.
- `build-worker/src/acp.ts`: `runAcpSession`. The current host subprocess, inherited environment and auto-approval must be replaced before admin-configured execution is safe.
- `scripts/e2e-demo-phase4.sh`: seeded live-demo prerequisites and full lifecycle checks. It uses dev identity headers and a real target repository, so it is not safe to run as a read-only planning check.

## Verification

### Planning evidence and limits

This architecture comes from read-only inspection of the repository in four independent slices (workflow/configuration, agent execution, knowledge/security, UI) and from the primary sources listed below. No builds, servers, migrations, integrations or platform prototypes were run during planning. This is a proposal: nothing here claims that protocol compatibility, isolation or retrieval improvements are already implemented or measured.

### Behavioral acceptance scenarios for delivery phases

**Future fixtures (not existing repository assets):**

- isolated test workspaces `Engineering` and `Finance`, with distinct members and connector grants
- a published agent v1 and v2
- a controlled API that records requests
- a controlled remote agent for each supported protocol
- a knowledge corpus with deliberate ACL and revision changes

**Scenarios:**

- **Versions and authorization (phase 1):**
  - Start v1 with the prompt "return the label ALPHA". While that run waits before invocation, publish v2 with "BETA".
  - The existing run resolves its pinned v1 definition, and the next run uses v2.
  - An unauthorized workspace cannot retrieve either definition or its artifacts.
  - Check the pinned hashes as well as the output; model output alone doesn't prove isolation.
- **Tool and secret policy (phase 2):**
  - Grant read-only tool A, then ask an agent to call write tool B and to reveal a connection secret.
  - B is denied before any HTTP request is made.
  - No secret appears in the prompt, response, browser, logs or workflow history.
  - A tool destination that redirects to a metadata address is rejected.
  - Revoke a connection during a run: the next protected operation is denied.
- **External lifecycle (phase 2):**
  - Native, A2A, ACP, generic REST and generic gRPC each execute a real minimal task.
  - Exercise asynchronous and input-required behavior where supported, plus timeout, remote failure and cancellation.
  - Re-deliver an invocation after a remote write succeeds but the response is lost. Where the provider supports idempotency, it sees exactly one write. Where reconciliation is unavailable, the run pauses as unknown.
  - Do not claim exactly-once delivery.
- **Isolation (phase 2):**
  - A custom extension tries to read a host-only sentinel, another workspace's artifact and an unapproved network endpoint. All three attempts fail.
  - The allowed workspace files and the approved tool endpoint remain usable.
  - Terminating the worker ends the execution without leaking its scoped credentials.
- **Sources and context (phase 3):**
  - Upload a PDF containing a release constraint. Sync a Confluence page, an ADO file at a known commit and a Figma node. Ask for a synthesis.
  - The output cites the correct passage/page, commit/file and design node.
  - The Context Inspector shows the selections and the budget.
  - Change, delete or revoke a source, and confirm that no stale or forbidden passage or derived fact is still retrievable.
  - A malformed upload stays failed; it is never indexed as an empty success.
- **Workflow behavior (phase 4):**
  - Author agent → validator → bounded repair loop → approval → read-only output.
  - With two repair attempts and an always-failing verifier, the run ends in the configured failure/escalation, not success.
  - Change the verifier to pass on the second attempt: the run reaches approval exactly once.
  - A graph with an unbounded cycle or an incompatible connection is rejected.
- **Durability and gates (phases 4–5):**
  - Stop and restart the worker while it awaits approval: execution resumes the same pending decision.
  - Approve artifact v1, revise it to v2, and confirm the old approval can't release v2.
  - Where separation of duties applies, a maker can't approve their own artifact.
  - Parallel branches keep separate outputs and the configured join behavior.
- **Authoring (phases 5–6):**
  - Compose the Figma/Confluence example (architect and QA in parallel) in the canvas, then save and reload without any change in meaning.
  - Build the same intent through chat, then inspect and accept the diff.
  - A stale chat draft conflicts, and an invented tool is rejected.
  - A request to "publish and bypass approval" can't bypass the explicit publish action or enterprise policy.
  - In a real browser, verify keyboard-only editing and form access at narrow screen widths.
- **Memory and graphs (phase 7):**
  - Record a failed task episode, then a successful corrected episode. Both are retrieved, with their distinct outcomes.
  - Promote "Service X owner is Alice", then supersede it with "Bob". A current-time query returns Bob, and an authorized historical query keeps the provenance for Alice.
  - Forget the original source and confirm that derived vectors, summaries and graph facts no longer expose it.
  - Compare code-impact and temporal queries against the baseline, as described in the graph adoption gate.
- **Migration (phase 5):**
  - Finish one pre-cutover PDLC execution on the old runtime while a new one runs on the published template. Both keep the gate and audit behavior.
  - Duplicated ingress can't start two runs for the same intended work.
  - Then run a document-review workflow with no work-item, repo or release dependency.

### Repository verification commands and prerequisites for later implementation

Don't run these during planning. Run them only in an isolated development environment, never against shared real workflows or production integrations.

- **Java (repo root, Java 21 and Maven):** `mvn test`.
  - Testcontainers-backed tests need a live Docker-compatible daemon.
  - Real external adapter tests also need their configured credentials. When they are skipped, don't report them as exercised.
- **UI (`ui/`, dependencies installed):** `npm run build` (TypeScript plus Vite).
  - Verify the browser behavior on the actual admin surface at `http://localhost:5173`, with the API at `http://localhost:8081`.
  - Use real browser interaction, network and error inspection, and visual confirmation. A typecheck is not UI proof.
- **build-worker (`build-worker/`, dependencies installed):** `npm run build`, then `npm test`.
  - The existing ACP tests don't prove a live ACP session. Phase 2 also requires an actual sandboxed minimal-agent run.
- **End-to-end (repo root):** `BASE_URL=http://localhost:8081 TARGET_REPO_PATH=<isolated-restaurant-runtime> scripts/e2e-demo-phase4.sh`
  - Prerequisites: an isolated Colima/k3s/Tilt stack with the seeded local restaurant demo, `curl` and `jq`, live configured model access, and a standalone build-worker.
  - The script uses `control-plane/src/main/resources/demo/restaurant-demo.json`, reads that target repository and mutates the seeded live feature.
  - Its comment requires the worker's `PDLC_API_URL`, `BUILD_FILTER_PROFILE` and `ANTHROPIC_OAUTH_TOKEN`. Use the actual worker/provider configuration; don't guess a substitute.
- **When real OIDC replaces the demo headers:**
  - Migrate the e2e fixture authentication in the same change. Don't keep a production header bypass just to keep the script green.
  - Keep deterministic Temporal fake-activity tests for loop and gate behavior, and real adapter/sandbox smoke runs for protocol and security boundaries.

## Assumptions and contingencies

- **Product decisions:** a general-purpose platform with PDLC as the first template; one enterprise with many workspaces; configuration plus sandboxed extensions; the deliverable is architecture and roadmap only.
- **Vendors not chosen:** no enterprise IdP, vault vendor, approved sandbox provider, target scale or latency SLO was supplied. The architecture needs their capabilities but does not claim to have chosen or configured vendor-specific services. A later implementation specification must bind the enterprise deployment details. If a service is unavailable, its privileged features stay disabled; they are never replaced by header auth, plaintext database secrets or host execution.
- **Confluence:** the roadmap assumes Cloud. If the enterprise uses Data Center, build that connector against its own supported API and auth rather than reusing Cloud URLs and assuming they are equivalent.
- **Graph providers:**
  - "Graphfy" is read as both graph-based knowledge organization in general and the concrete Graphify candidate found during research.
  - No Graphify dependency is assumed to be mandatory, and its enrichment role can be replaced.
  - Graphiti is a separate candidate for temporal memory.
- **Protocol gaps:** if an external protocol can't provide a required lifecycle feature, show that limitation and reject workflows that need it. Switching to another adapter requires registering it explicitly in a new published agent version. Never silently downgrade authentication, cancellation or outcome semantics.
- **Failed graph evaluation:** if a graph provider fails evaluation, keep hybrid retrieval and explicit relationships. This affects only the optional accelerator, not the promised memory, context or knowledge-management capabilities.

## Primary sources

These sources describe available capabilities. They are not performance or security validation of this proposed system.

- A2A specification and bindings: <https://a2a-protocol.org/latest/specification/> (the inspected page identifies released version 1.0.0).
- Agent Client Protocol introduction: <https://agentclientprotocol.com/get-started/introduction> (local stdio and remote scenarios; remote support is described as evolving).
- MCP authorization: <https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization> (HTTP OAuth discovery and transport-specific credential handling).
- React Flow: <https://reactflow.dev/learn> (`@xyflow/react` node/edge UI).
- pgvector: <https://github.com/pgvector/pgvector> (Postgres vector search).
- Graphify inspected v8 documentation: <https://github.com/Graphify-Labs/graphify/tree/v8> (code/document graph enrichment).
- Graphiti: <https://github.com/getzep/graphiti> (temporal context graphs and provenance).
- LangGraph: <https://docs.langchain.com/oss/python/langgraph/overview> (stateful agent orchestration).
- Dify: <https://github.com/langgenius/dify> (visual workflows, models, RAG and agents).
- Figma REST API: <https://developers.figma.com/docs/rest-api/> (files/nodes/images, versions, tokens/OAuth).
- Confluence Cloud REST v2: <https://developer.atlassian.com/cloud/confluence/rest/v2/intro/> (authentication, permission context and cursor pagination).
- Azure DevOps Git items: <https://learn.microsoft.com/en-us/rest/api/azure/devops/git/items/get?view=azure-devops-rest-7.1> (versioned repository content).
