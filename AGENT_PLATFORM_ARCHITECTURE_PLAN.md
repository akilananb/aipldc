# Configurable agent platform: architecture and phased roadmap

## Context
Evolve pdlc-pilot into a general-purpose agent platform where administrators can compose agents, tools, context, memory, validation and workflows without redeploying for every configuration change. PDLC becomes the first governed domain template rather than the platform's fixed process. The deployment serves one enterprise with isolated workspaces, enterprise identity and centrally governed integrations; extensibility includes configuration and sandboxed execution.

The requested deliverable is **architecture and a phased roadmap**, not a full API/schema implementation specification. This is a design-only handoff: approval of this document does not authorize building the whole platform, changing application files or provisioning infrastructure. Present the architecture, recommendations, phase dependencies and acceptance scenarios below as the deliverable; implementation requires a subsequent phase-specific execution specification. All requested capabilities remain in the roadmap.

## Current implementation evidence

| Area | Observed implementation | Architectural consequence |
|---|---|---|
| Lifecycle | `core/src/main/java/ai/pdlc/core/workflow/FeatureWorkflowImpl.java` implements fixed intake, story, G1, plan, build, G2, release, G3, deploy and monitor flow. `run()` uses `Workflow.getVersion("adaptive-grill-rounds", ...)` for an existing replay-compatible change. | Retain Temporal durability, but introduce a distinct data-driven workflow type. Do not replace the implementation under an existing workflow history. |
| Configuration | `core/src/main/java/ai/pdlc/core/config/Profile.java` contains board/repo/notify/agents/gates. Discovery of `PdlcConfig` and Spring wiring found startup-loaded profiles, not a registry or configuration API. | Database-backed published definitions become authoritative for new platform runs. YAML remains deployment bootstrap, not a competing live editor. |
| Prompts | `agents/src/main/java/ai/pdlc/agents/templates/PromptTemplates.java` renders Mustache from an override directory or bundled classpath; it rereads files per invocation. | Reuse Mustache syntax, but snapshot published prompt content. Editing a file during a run must not change its pinned definition. |
| Agents/models/skills | Per-domain agent classes and role dispatch are fixed. Model names are registered at application startup. Discovery found two packaged grill skills embedded as instructions, not a general skills registry. | Introduce a definition-driven native runner and model catalog; creating an agent must not require a Java class or Spring bean. |
| Context | `agents/src/main/java/ai/pdlc/agents/activities/AgentContext.java` reads board items/comments and individual repo files; failure returns null/empty, and `readFile()` is silent. No ingestion, vector search, memory lifecycle or context budgeting was found. | Knowledge and memory require new persistent services, not renamed audit records or a process-local cache. |
| Identity | `control-plane/src/main/java/ai/pdlc/controlplane/identity/IdentityResolver.java` trusts `X-User` and `X-Role`. Discovery found environment-based `SecretsPort` resolution, not scoped tool credentials. | Enterprise authentication, workspace authorization and credential isolation precede configurable execution. |
| ACP execution | `build-worker/src/acp.ts:runAcpSession` spawns `npx --yes acpx@latest ... --approve-all`, with `env: process.env`, on the host. | Existing code is an ACP integration starting point, not an isolation boundary. Do not expose its current host-command configuration to workspace admins. |
| UI | `ui/package.json` has React 18, Router 6, TanStack Query, Radix Themes and CodeMirror, but no canvas library or test script. Discovery found item/review routes and reusable `Surface`/mutation patterns, not an admin studio. | Extend the existing UI/design system. A workflow canvas is new; the current task-status graph is not an authoring engine. |
| Storage/migration | Discovery found Spring Data JDBC record entities and additive Flyway migrations through `V11__scenario_reviews.sql`. Temporal history owns execution progression; application Postgres holds projections and audit. | Keep this separation. Determine the next unused migration number at implementation time; do not use the older repository guide's V7 example. |

## Approach

### 1. Establish the platform boundary and ownership

Use four logical areas; do not create a microservice for every feature:

1. **Authoring/control plane:** identity, workspaces, asset registries, publication, connection grants, run entry points, approval inbox and audit. Extend `control-plane/` using existing record/entity/repository/controller conventions.
2. **Durable runtime:** a new generic workflow interpreter in `core/` on Temporal. It coordinates deterministic graph transitions, human waits, timers and activity results. It never executes model calls, HTTP requests, scripts or database reads directly.
3. **Execution workers:** keep native reasoning in `agents/`; retain the Node build-worker's claim/heartbeat/cancellation pattern for coding. Introduce isolated execution capacity for tools, connectors, document parsing, custom verifiers and approved extension packages. Maintain separate queue ownership; do not mix disjoint activity registrations onto the same queue.
4. **Knowledge/context services:** durable source metadata, ACLs, retrieval and memory APIs owned by the control plane, with ingestion/extraction running asynchronously in restricted workers. All agent runtimes use the same authorized context service rather than maintaining private, inconsistent copies.

Keep Java/Spring, Temporal, React and Postgres. Keep Embabel as the native model integration rather than introducing a second native agent framework. Add pgvector and Postgres full-text search for hybrid retrieval, plus object storage for original documents and large artifacts. The application database stores metadata, grants, definitions, run projections and memory records; Temporal stores execution history. A graph index, when enabled, is a derived retrieval view—not authority for approvals, permissions or source content.

General-platform resources are **Workspace, AgentDefinition, SkillDefinition, ToolDefinition, Connection, WorkflowDefinition, ValidationPolicy, KnowledgeCollection, ContextPolicy, MemoryPolicy, Run and Artifact**. These names describe the architecture, not existing Java classes or finalized wire schemas. PDLC-specific stories, board mappings, repository actions, gates and release documents live in a versioned PDLC package layered on those resources. Do not make every run require a `WorkItemRef` or PDLC `CanonicalState`.

### 2. Make publication—not mutable files—the configuration contract

Agents, skills, tool schemas, workflows, validators and context/memory policies share a lifecycle: **draft → validated → published immutable version → retired**. Editing a published resource creates a draft; rollback selects a previous published version for new runs. Referenced versions cannot be physically deleted while needed for run provenance.

Publishing atomically validates and pins the full dependency set: prompt/skills, model binding, tools, workflow nodes, schemas and policies. Missing or retired dependencies and incompatible inputs/outputs block publication with actionable errors. Concurrent edits fail as conflicts, rather than silently overwriting another administrator's draft. Import/export includes definitions and dependency references, never credentials, private memories or an implicit trust grant.

A run receives a resolved, immutable definition bundle and content hashes at its start. New publications affect new runs, not current execution. Pinning behavior does **not** pin permission forever: current credential revocation, workspace access removal and platform emergency-deny policies still stop future protected operations. There is no automatic live editing of an executing graph.

Import existing YAML role settings and bundled prompts as initial published PDLC assets. Record the import once. Do not continuously overwrite admin edits from YAML or silently fall back to old files when a registry definition is missing. Keep existing workflows on their old behavior until the explicit migration in step 10.

### 3. Put identity, credentials and execution policy below every feature

Replace the header shim for the enterprise surface with enterprise OIDC through Spring Security, using a backend-managed browser session, HttpOnly cookies and CSRF protection. Use scoped service identities for worker/API traffic; do not reuse human browser sessions. Workspace access must be enforced at every resource lookup, database query, run operation, artifact fetch, search and memory retrieval—not merely by hiding navigation.

Separate capabilities:
- Enterprise administrators approve issuers, model/provider connections, egress destinations, executable images and enterprise policies.
- Workspace administrators manage workspace membership and publish assets within those grants.
- Authors edit drafts; operators run/cancel workflows; reviewers approve designated artifacts; knowledge curators publish collections and promoted memories. One person may hold several capabilities, except where a workflow enforces separation of duties.

Credentials belong to **Connections**, never prompts or tool-definition JSON. Distinguish a workspace service connection from a user's delegated connection. Resolve short-lived credentials in the authorized execution adapter; the model and browser never receive the secret value. Support API keys/PATs, OAuth delegated authorization, OAuth client credentials and mTLS through connection types. Maintain consent/scope, expiry, rotation and revocation state; a revoked or expired connection fails visibly rather than falling back to a broader credential.

Enforce tool policy immediately before execution: current workspace and actor/run identity, published tool allowlist, approved operation, destination, data classification, argument schema, budget and required approval. Tool descriptions and MCP annotations are untrusted metadata, not authorization. Writes/destructive actions require a policy-authorized path; an approval binds to the exact operation/arguments or artifact version and is invalid after changes.

Outbound policy covers tool calls, OAuth discovery, A2A cards, imports, redirects, callback URLs and source ingestion. Deny arbitrary internal/metadata/link-local targets; explicitly approved enterprise private endpoints are allowed only through enterprise-configured destination rules. Enforce DNS/redirect-aware checks and network egress restrictions, not just URL string validation.

Run scripts, local MCP servers, ACP agents, parsers and custom verifier packages as non-root, immutable-image workloads with resource/time limits, restricted filesystems, per-run workspaces, explicit environment allowlists and default-deny egress. No host filesystem mounts or container-runtime socket. Use an isolation-capable runtime for uploaded/untrusted executable packages; if unavailable, disable that execution class rather than silently launching on the host. Pin executable/image/package versions instead of `@latest`; remove inherited full-host environment and blanket auto-approval from the configurable execution path.

Cancellation and timeout terminate the entire isolated workload/process tree and revoke its temporary credentials; stopping only a wrapper process is insufficient.

### 4. Build Agent Studio, skills and the governed tool registry

**Agent Studio** provides identity/description, native-or-external runtime, prompt editor with declared variables, input/output schema, model binding and generation limits, skill versions, granted tools, knowledge collections, context/memory policies, delegation rules and execution budgets. Include draft preview, compare versions, test invocation, publication history and disable/retire controls. Customization is configuration, not generation of a new Java class.

Use the existing Mustache renderer's syntax with a restricted data-only variable map; admin templates must not read arbitrary filesystem paths or load executable partials. Render published content and validate required variables before invocation. Model selection comes from an authorized runtime-resolved model catalog; a new model binding takes effect without restarting agent roles. An unavailable model produces an explicit error; alternate models run only when the published agent declares an allowed fallback. Do not copy today's silent default-model behavior into the new contract.

A **skill** is a versioned package of instructions, examples, supporting resources and declared requirements. Importing a skill does not grant its requested tools. Instruction-only skills stay text; executable resources use the sandbox extension path. Imported instructions cannot override enterprise policy. Keep the existing grill skills as seed content, but do not re-enable an unbounded skill/tool exploration loop.

Native agents use an explicit bounded tool-use loop: assemble context → model response → validate requested tool call → policy/approval → tool execution → bounded result → next turn or typed output. Limit model turns, tool calls, nested delegations, wall time and provider-supported usage. Failure to measure a remote token count must be shown as unknown, not fabricated or presented as an enforced token cap.

The **tool registry** separates reusable operation definitions from authenticated connection instances:
- API tools: import OpenAPI or define an explicit method/path and request/response schema; publish only selected operations. Remote references and server URLs undergo the same import/egress policy.
- MCP tools: discover, preview and approve individual tools; retain a versioned schema/description fingerprint. Capability changes require review before a new version can run. Support remote HTTP MCP with its authorization flow; local stdio servers run only in the sandbox with scoped environment credentials.
- Custom tools/verifiers: signed or enterprise-approved immutable worker packages with declared input/output schema, required capabilities and limits. Workspace admins configure approved packages; installing an arbitrary executable is an enterprise-controlled action.

Use one policy-enforcing execution path for direct workflow tool nodes and model-requested tool calls. A prompt should never be able to reach a privileged side door that the workflow designer cannot use.

### 5. Integrate external agents without erasing protocol differences

Define a common invocation model carrying workspace, run/node invocation identity, pinned agent version, typed input, permitted context/artifact references, deadline and cancellation. Persist remote task/session identifiers. Normalize returned artifacts/status/errors while preserving provider-specific capabilities; advertise unsupported streaming, cancellation, authentication or usage accounting honestly.

Treat remote artifact URLs and returned metadata as untrusted. Fetch referenced content only through the authorized egress/content-ingestion path; a successful remote agent call does not authorize arbitrary follow-on downloads or browser rendering.

| Adapter | Intended role | Required behavior |
|---|---|---|
| Native | Definition-driven agent using the retained Embabel/model stack | Enforce context/tool policy and output contracts locally. |
| A2A | Remote autonomous agents and governed agent-to-agent delegation | Discover an Agent Card, authorize the endpoint, negotiate supported interface/version, handle immediate messages or task lifecycle, artifacts, input-required/auth-required states, polling/streaming and cancellation where supported. An Agent Card is not a trust credential. |
| ACP | Coding/session agents controlled by a client | Reuse the local ACP experience in an isolated runner, with filesystem/terminal/permission mediation and cancellation. Support approved remote ACP transports through a capability-tested adapter, not by treating every URL as ACP. |
| REST | Existing HTTP agent services without A2A | Declare synchronous response or asynchronous task/status/cancel endpoint mapping, schemas, authentication, deadline and remote idempotency support. No guessing job states from arbitrary JSON. |
| gRPC | Existing protobuf services without A2A | Register descriptors, service/method and message mapping; use TLS/mTLS and deadlines. Permit only declared unary/streaming operations. Reflection does not automatically publish all server methods. |

A2A itself defines REST/HTTP, JSON-RPC and gRPC bindings; those are distinct from generic REST/gRPC adapters for non-A2A services. ACP here means **Agent Client Protocol**, matching this repository's coding-agent usage. MCP exposes tools/resources; it is not a replacement for A2A task delegation.

Permit both outbound A2A delegation and an authenticated A2A ingress for explicitly published platform agents/workflows. Internal delegation uses the same invocation/policy model without requiring HTTP between every local agent. Bound recipient allowlists, delegation depth, total run budget and data leaving the workspace. Remote agents receive only approved context, not unrestricted platform memory or credential access.

Record effect intents before remote writes. Reuse a stable invocation/idempotency identity across retries and store the remote task ID as soon as known. A timeout is an **unknown outcome**, not proof of failure: reconcile by remote status/idempotency support, or pause for operator resolution when the provider offers neither. Do not retry non-idempotent effects blindly. Cancellation is best-effort remote cancellation with an explicit acknowledgment state; it does not undo already-completed effects.

### 6. Organize knowledge before adding autonomous memory

A **KnowledgeCollection** is a workspace-scoped collection of versioned source documents and their evidence, not an unstructured prompt attachment folder. Track source owner, connector, source URL/ID, source version/commit, extraction status, classification, ACLs, content hash and synchronization state. Organize by source hierarchy, project/collection, document type, labels and source-backed entity links.

Ingestion runs asynchronously: authenticate source → enumerate approved scope → fetch/version → scan and parse → extract text/structure/media → chunk with locators → embed/index → publish searchable version. Keep partial/failed imports visible; a failed fetch is not an empty successful document. Deduplicate within the authorization boundary, retain revision provenance, handle rate limits and resumable synchronization, and process deletion/permission changes. Immutable object storage holds originals; Postgres holds metadata, chunk locators, full-text/vector indexes and grants.

Required source paths:
- **Uploads:** PDF, DOCX, PPTX, text and Markdown; images/scanned PDFs through OCR. Enforce MIME/size/parser/resource limits and quarantine malformed/encrypted/unsupported files with a clear failure reason. Do not execute embedded macros or follow document instructions.
- **Confluence:** selected spaces/pages and attachments, page hierarchy/version and restrictions, incremental refresh. Cloud REST v2 is the default integration; Data Center remains a separately declared connector variant, never silently treated as Cloud.
- **Figma:** selected files, node/layer structure, components, versions and permitted rendered images, with links back to file/node. Preserve visual context instead of flattening every design into text; use multimodal input only for approved capable models.
- **ADO repositories:** selected organization/project/repository/path and commit-based snapshots using the Git API; changed-file synchronization, file/line/commit citations. The existing ADO board adapter is not an ADO repository connector. Read-only knowledge indexing is separate from a write-capable coding/repository connection.

Retrieval uses ACL-filtered lexical plus vector search, rank fusion and bounded reranking, with citations to document/page/node/file/line/version. Filter before ranking and before exposing snippets, counts, entities or graph paths. Enforce the effective actor/run grants and upstream entitlement—not the broad reach of the ingestion service account. When upstream permission mapping cannot be established, keep the source private to the authorized connector owner and disable shared retrieval. Current access must be revalidated at delivery; expired/unknown entitlement is denied, not served from a stale cache.

External content is evidence with provenance and trust labels, never system instructions. Source deletion/revocation invalidates retrieval, caches, derived summaries, memory facts and graph edges. Physical deletion follows retention policy; audit retains only permitted non-content metadata. Define embedding model/version per index and switch an index only after a complete rebuild; do not mix incompatible vectors or publish incomplete extraction as ready.

Generated answers, artifacts, context traces and summaries inherit the intersection of their contributing sources' access restrictions and applicable classification. Being in the same workspace does not grant access to all derived outputs. Broadening visibility requires an explicit authorized publication/declassification action; the model cannot make that decision. Apply source revocation/deletion to these derived outputs as well as retrieval indexes.

### 7. Make context assembly and memory separately inspectable

**Context engineering** becomes a first-class configurable pipeline, shared by native agents and external-agent requests:
1. Resolve trusted platform policy and the pinned agent/skill instructions.
2. Add the user task, bounded conversation state and typed upstream artifacts.
3. Retrieve authorized relevant source passages, facts and past outcomes for this invocation.
4. Deduplicate and rank; reserve model output/tool budget; compact conversation and evidence with source pointers. Never discard policy or required task input silently to make the context fit.
5. Produce a context manifest: selected source versions, citations, retrieval reasons, excluded/trimmed items, memory IDs and token accounting. Persist sensitive payloads only in access-controlled content storage, not raw Temporal history or public logs.

Admins configure collection selection, recency, retrieval depth, budgets, summarization, memory scopes and required/optional sources. Required-source failure blocks with a visible reason; optional-source failure is a visible warning. If trusted instructions plus required input exceed model capacity, reject the invocation rather than silently truncate. Cache keys include workspace, authorization scope, source versions and policy—not just the query string. Provide a **Context Inspector** to answer “What did this agent see, why, and what was omitted?” without exposing private model chain-of-thought.

Memory is not one unlimited chat transcript:

| Memory kind | Content and scope | Write/retention contract |
|---|---|---|
| Short-term/working | Current session messages, intermediate state and bounded summaries for one run/thread | Durable enough for resume; expires under workspace retention. Temporal holds control state, content storage holds large messages/artifacts. A cache alone is not durability. |
| Episodic | Completed/failed task summaries: goal, relevant actions, evidence, reviewer feedback and measured outcome | Append provenance-linked episodes. Retain failures as failures; do not learn a successful procedure from an unverified result. Retrieval remains workspace/project/actor scoped. |
| Long-term semantic | Approved facts, preferences, decisions and constraints with source and validity history | Agents propose candidates; authorized curation or an explicit published policy promotes them. Correct/supersede/retract rather than silently replace contradictory claims. |
| Procedural | Reusable instructions and validated successful practices | Publish as versioned skills/templates, not an opaque memory instruction that bypasses review. |

Support inspect, search, correct, forget, retention and permitted scope sharing in Memory administration. Personal preferences are private by default; cross-workspace sharing requires explicit publication. Derived memories inherit the intersection of their sources' access restrictions. Forget/deletion propagates to embeddings, graph indexes, caches and derived summaries; audit/history must not re-expose erased content. No automatic fine-tuning or self-modification is needed to provide these memory types.

For **graphs**, start with explicit source/artifact/entity relationships in Postgres. Add Graphify as an isolated optional enrichment provider for code/document relationships and impact queries; retain extracted-versus-inferred labels. Add a self-hosted Graphiti adapter backed by a supported graph database for temporal facts/episodic relationships only after the evaluation gate below. These complement—not replace—hybrid retrieval and source authority. A path through a hidden entity must not reveal that entity or an otherwise forbidden relationship.

### 8. Make workflows executable data, with explicit loops and verification

Use one canonical, versioned, typed graph document for forms, canvas, API/import and chat authoring. Canvas positions are presentation metadata; runtime semantics do not depend on them. The backend validates and compiles a published graph into the Temporal execution plan. A workflow is **not just a DAG**: allow structured loops while rejecting arbitrary cyclic wiring.

Node capabilities: input/start, agent, tool, retrieval, transform, branch, parallel fork/join, bounded foreach, bounded loop, subworkflow, human input, approval, validation, verification, wait/timer and artifact/output. Manual, authenticated webhook and scheduled triggers bind to a published version. Expressions use a restricted deterministic expression language, not JavaScript/Python/SpEL evaluation inside the workflow process. Runtime code and interpreters remain deployed software; new prompts, schemas and graph compositions are configuration.

Publication must reject missing nodes/references, invalid types, unreachable required work, ambiguous branch routing, undeclared merges, unauthorized tool dependencies, unbounded loops/recursion and invalid approval roles. Parallel branches write distinct output namespaces; an explicit transform merges outputs instead of last-writer-wins mutation. Subworkflows pin their definitions and share the parent's global limits.

Loops declare continuation/termination conditions, maximum iterations, elapsed-time limit and budget. Exhaustion yields an explicit failed/escalated outcome, never success. Retries for transient errors are separate from deliberate revise-and-recheck loops. Human waits are durable; timeout escalates or ends as specified, never auto-approves. Long histories use checkpoint/continue-as-new boundaries without resetting global budget or losing pending input/approval identities. Large content travels as authorized artifact references rather than accumulating in workflow history.

Distinguish **validation** from **verification**:
- Validation: schema, required fields, deterministic rules and policy checks, before invocation/publication and after output.
- Verification: independent evidence that the task succeeded—tests, build results, artifact checks, API probes, comparison with source material or human review.
- Model-based rubrics are configurable assessments, not objective proof. They cannot override a failing required deterministic check or a missing human approval.

Administrators configure reusable rule sets and rubric versions, required/advisory severity, evidence expectations, repair-loop bounds and failure/escalation routing. Validators return structured findings linked to artifact versions. Custom executable verifiers use the same isolated worker boundary as tools; arbitrary validators never execute in a controller or Temporal workflow thread.

Approvals bind to the exact artifact/definition version and reviewer capability. A revised artifact invalidates prior approvals for that artifact. Retain maker/checker separation and current PDLC blocking-comment behavior in the PDLC template. Dynamic planning by an agent is permitted only inside a bounded delegated-task node: generated steps must pass the same schema/policy compiler and cannot add privileges, rewrite the parent graph or grant themselves approval.

### 9. Provide one coherent administration and authoring experience

Extend the existing `ui/src/App.tsx` routing and `AppShell` navigation with workspace selection and: **Agents, Workflows, Tools & Connections, Knowledge, Memory, Runs & Approvals, Administration**. Skills, models and evaluation policies are managed catalogs linked from relevant editors. Reuse the existing `Surface` tabs/inspector, design tokens, query error display and mutation/invalidation patterns; workspace identity belongs in every query cache key.

The **Workflow Designer** uses `@xyflow/react` (React Flow) as a canvas component, not as an execution engine. Provide a node palette, typed ports, explicit loop containers, property forms, validation panel, version comparison, test run and execution overlay. Offer a keyboard-accessible structured list/form editor equivalent to canvas editing; empty, loading, conflict and authorization-error states are explicit. Forms for inputs and human tasks derive from published schemas. Do not accept arbitrary admin-supplied React/HTML as a UI extension mechanism.

The **chat designer** is another author of the same draft graph. Example: “Read the Figma design and Confluence requirements, ask an architect and QA in parallel, revise at most twice, then get a lead's approval.” It resolves only catalog assets visible in that workspace, asks when requirements change privileges or behavior, proposes a graph diff, explains tool/data access, validates it and runs a non-production simulation. The user accepts the diff and separately publishes. Conversation alone never grants connections, executes production writes or silently publishes a graph. Handle stale drafts through revision conflicts rather than applying a patch to a newer draft invisibly.

Test mode is isolated and visibly distinct from production: recorded fixtures or safe read-only calls by default, with writes disabled unless explicitly authorized for a test connection. Trace actual inputs/outputs, tool requests, approved actions, evidence, elapsed time and available usage; do not present a generated rationale as an execution log.

**Run Explorer** joins the graph trace, pinned asset versions, context manifest, tool policy decisions, artifacts, validation results, approvals and outcomes. Replaying recorded execution for inspection is distinct from a new execution, which can produce new LLM output or side effects. Provide cancel/pause-at-safe-boundary and an enterprise kill switch; no promise that cancellation reverses a remote action.

### 10. Migrate PDLC without breaking active work

Publish the existing PDLC as a package of agents, prompts/skills, workflow definitions, artifact schemas, board/repo actions, validators and role/gate policies. Preserve adaptive clarification, sequential processing of split stories, bounded automated repair, task/build verification, blocking comments, G1/G2/G3 approvals, release documents and both audit trails. Do not turn the current deliberately sequential story behavior into parallel execution as a side effect of adding a graph engine.

Introduce the generic interpreter as a **new Temporal workflow type**; use `control-plane/src/main/java/ai/pdlc/controlplane/temporal/FeatureWorkflowStarter.java` as the existing start-boundary integration anchor. Resolve the chosen published PDLC workflow version before starting new work. Keep workflow-start idempotency and persist which runtime/type owns a work item so controllers route queries/signals correctly. Existing executions remain on their original implementation and configuration; never reinterpret history or transplant a waiting gate into the new graph.

Maintain the old deployment only for draining executions and the supported history replay/reset window. Route all new PDLC work through the published template after parity verification, then remove obsolete starters/dispatch and hardcoded UI role/mention catalogs when no longer needed. Discover callers with LSP references when implementing; relevant observed areas include workflow stubs, item/artifact/PR/release controllers, agent activities, UI gate/mention lists and worker registration. This planned drain is not a permanent compatibility mode.

New general workflows use platform run/artifact audit without needing a git repository. The PDLC package additionally invokes the existing review-trail behavior so `review.md` and `review_events` remain aligned. Demonstrate one non-PDLC workflow without story, repository or release fields to prove genuine domain separation.

## Phased delivery roadmap

Each phase is a user-visible end-to-end increment, not a scaffolding milestone. Phases are not estimates or single-sprint commitments. Subsequent implementation specifications should follow these boundaries rather than attempt the entire platform in one change.

| Phase | Deliverable | Prerequisite and parallelism | Exit evidence |
|---|---|---|---|
| **1 — Secure Agent Studio** | Enterprise sessions, isolated workspaces, service identities, scoped connection references, immutable asset publication, authorized model catalog, native agent/skill/prompt editor and a single-agent durable run with bounded working context. | First. Reuse existing UI and native model integration. Establish the generic run identity/version contract here; do not create a temporary unrelated runner. | Admin publishes a new agent without code/restart; an existing run remains pinned after revision; another workspace cannot discover/read/run it. |
| **2 — Governed execution and federation** | Authenticated API and MCP tool registry, sandbox execution, native tool loop, external ACP/REST/gRPC/A2A adapters, bounded A2A delegation and selectively published A2A ingress. | Depends on phase 1. Adapter implementations can proceed independently once the invocation, policy and credential contracts are fixed; each adapter must complete its real lifecycle tests. | Each adapter completes a real task and exposes its supported cancellation/status semantics; secret isolation, denied tools and unknown-outcome handling are demonstrated. |
| **3 — Knowledge and context** | Uploads; Confluence, Figma and ADO repository connectors; organized collections, provenance, hybrid retrieval and Context Inspector. | Depends on phase 1 plus the shared connection/egress boundary from phase 2. Connector work is independent of advanced workflow authoring and other protocol adapters. | A cited answer uses authorized evidence across sources; revisions update citations; revocation removes access; failed imports stay visibly failed. |
| **4 — Dynamic runtime and checks** | Typed graph compiler/interpreter, branches/parallelism/structured loops/subworkflows, human tasks/gates, custom validation and sandbox verification, typed artifact state and run trace. | Depends on phases 1–2. Can proceed alongside phase 3. Start with form/structured authoring of the same graph later rendered by the canvas. | A revise/verify loop exits correctly, exhaustion fails visibly, restart resumes a human wait, and non-idempotent timeouts do not duplicate effects. |
| **5 — Visual designer and PDLC template** | React Flow canvas plus equivalent form editor, graph validation/run overlay; published PDLC package, generic run explorer and safe new-run cutover. | Depends on phase 4; context-enabled examples additionally use phase 3. | Canvas→save→reload preserves executable meaning; full PDLC parity passes while a legacy run still finishes; a non-PDLC run requires no PDLC record. |
| **6 — Conversational authoring** | Workspace-aware design chat proposing validated graph diffs, test simulation, accept/discard, version conflicts and separate publication. | Depends on phases 3–5. It consumes the compiler/catalog; it does not define a second workflow language. | Natural-language request produces the intended graph and bounded loop; unavailable tools are not invented; chat cannot bypass publication or credential consent. |
| **7 — Governed memory and graph enrichment** | Episodic capture, curated long-term facts, procedural promotion to skills, memory administration/deletion; explicit knowledge graph, Graphify enrichment and evaluated Graphiti temporal adapter. | Requires phase 3 and the run/evidence model established in phases 1/4. Can proceed alongside phase 6. Short-term memory already works from phase 1. | Correct/supersede/forget changes subsequent retrieval; temporal and impact queries cite evidence; graph retrieval never crosses ACL boundaries. |

## Technology decisions and better options

- **Retain Temporal; do not replace it to obtain a canvas.** Its existing durable waits and activity boundary are assets. React Flow addresses authoring, while the typed interpreter supplies runtime behavior. LangGraph is a credible runtime for independently hosted Python/TypeScript agents, which this platform can call through A2A/REST; do not make it a second owner of the same enterprise gate state.
- **Compose the platform from maintained components.** Reuse protocol SDKs, OIDC, React Flow, Postgres/pgvector and object storage. Build the product-specific publication model, policy boundary, workspace semantics, run evidence and PDLC package rather than inventing a protocol or distributed state engine.
- **Graphify and Graphiti solve different problems.** Graphify's inspected v8 documentation describes local AST-based code graphs and semantic document/media extraction with explained edge types. Graphiti documents temporal facts, provenance and episodic graph construction, and requires operating its supporting graph infrastructure. Neither repository's marketing benchmark proves suitability for this corpus.
- **Graph adoption is evidence-gated, not mandatory infrastructure from day one.** Keep hybrid retrieval plus explicit relationships as the production baseline. Run the phase-7 impact and superseded-fact scenarios against that baseline, then Graphify/Graphiti. Enable an adapter only if it fixes a recorded retrieval failure within the workspace's configured latency/cost limits and passes all access/deletion checks; otherwise leave that optional adapter disabled and retain the benchmark result. The platform still delivers organized knowledge and all three memory classes.
- **Dify is the concrete buy-versus-build reference.** Its published feature set overlaps visual workflows, model management, RAG and agents. If differentiated enterprise governance and the existing PDLC runtime are not strategic, adopting it deserves a separate product decision. For this roadmap retain the existing product; do not bolt another complete authoring/runtime platform inside it and inherit two permission, versioning and execution models. Existing Dify-hosted agents can integrate as external agents after interface/auth validation.
- **High-value additions already incorporated:** context inspection, version comparison, replay-versus-rerun distinction, evaluation before publication, source lineage, revocation and a kill switch. These make configuration safe to operate; an unrestricted plugin marketplace or autonomous self-rewriting platform is not required.

## Critical files and anchors

These are non-obvious integration anchors, not a complete implementation file list. Reread them at phase-specific planning time; use symbol references before changing public contracts.

1. `core/src/main/java/ai/pdlc/core/workflow/FeatureWorkflowImpl.java` — `run()` and its `Workflow.getVersion` branch; preserve historical execution and sequential story semantics.
2. `control-plane/src/main/java/ai/pdlc/controlplane/temporal/FeatureWorkflowStarter.java` — `start(WorkItemRef)`; existing deterministic workflow ID and duplicate-start handling must survive new-run routing.
3. `agents/src/main/java/ai/pdlc/agents/templates/PromptTemplates.java` — `render`/`load`; distinguish reusable template syntax from mutable-file lookup, which cannot define a pinned run.
4. `build-worker/src/acp.ts` — `runAcpSession`; current host subprocess, inherited environment and auto-approval require replacement before admin-configured execution is safe.
5. `scripts/e2e-demo-phase4.sh` — seeded live demo prerequisites and full lifecycle checks; it currently uses dev identity headers and a real target repository, so it is not safe to run as a read-only planning check.

## Verification

### Planning evidence and limits

This architecture was grounded through read-only repository inspection of the four independent slices (workflow/configuration, agent execution, knowledge/security, UI) and the primary sources linked below. No builds, servers, migrations, integrations or platform prototypes were run during planning. The architecture is a proposal; protocol compatibility, isolation and retrieval improvements are not claimed as already implemented or measured.

### Behavioral acceptance scenarios for delivery phases

Use isolated test workspaces **Engineering** and **Finance**, distinct members and connector grants, a published agent v1/v2, a controlled API that records requests, a controlled remote agent for each supported protocol, and a knowledge corpus with deliberate ACL and revision changes. These are future fixtures, not existing repository assets.

1. **Versions and authorization (phase 1):** start v1 with prompt “return the label ALPHA”; publish v2 using “BETA” while the run waits before invocation. The existing run resolves its pinned v1 definition, the next run uses v2, and an unauthorized workspace cannot retrieve either definition or its artifacts. Inspect pinned hashes as well as output; model output alone does not establish isolation.
2. **Tool and secret policy (phase 2):** grant read-only tool A, ask an agent to call write tool B and to reveal a connection secret. B is denied before an HTTP request; no secret appears in prompt, response, browser, logs or workflow history. Reject a tool destination redirecting to a metadata address. Revoke a connection during a run; the next protected operation is denied.
3. **External lifecycle (phase 2):** native, A2A, ACP, generic REST and generic gRPC each execute a real minimal task. Exercise asynchronous/input-required behavior where supported, timeout, remote failure and cancellation. Re-deliver an invocation after a remote write succeeds but the response is lost: provider sees one write when idempotency exists, or the run pauses as unknown when reconciliation is unavailable. Do not claim exactly-once delivery.
4. **Isolation (phase 2):** a custom extension attempts to read a host-only sentinel, a different workspace's artifact and an unapproved network endpoint. All fail. Allowed workspace files and its approved tool endpoint remain usable. Worker termination ends the execution without leaking its scoped credentials.
5. **Sources and context (phase 3):** upload a PDF containing a release constraint; sync a Confluence page, ADO file at a known commit and a Figma node. Ask for a synthesis. Output cites the correct passage/page, commit/file and design node; Context Inspector shows the selections and budget. Change/delete/revoke a source and confirm no stale/forbidden passage or derived fact remains retrievable. A malformed upload stays failed, never indexed as an empty success.
6. **Workflow behavior (phase 4):** author agent→validator→bounded repair loop→approval→read-only output. Configure two repair attempts with an always-failing verifier; it terminates in the configured failure/escalation, not success. Change the verifier to pass on the second attempt; it reaches approval exactly once. Reject a graph containing an unbounded cycle or incompatible connection.
7. **Durability and gates (phases 4–5):** stop/restart the worker while awaiting approval; execution resumes the same pending decision. Approve artifact v1, revise to v2, and confirm the old approval cannot release v2. A maker cannot approve their own artifact where separation of duties applies. Parallel branches retain distinct outputs and the configured join behavior.
8. **Authoring (phases 5–6):** compose the Figma/Confluence parallel architect+QA example in the canvas; save/reload without semantic change. Build the same intent through chat; inspect and accept the diff. A stale chat draft conflicts, an invented tool is rejected, and requesting “publish and bypass approval” cannot bypass the explicit publish action or enterprise policy. Verify keyboard-only editing and narrow-screen form access in a real browser.
9. **Memory and graphs (phase 7):** record a failed task episode, then a successful corrected episode. Retrieve them with distinct outcomes. Promote “Service X owner is Alice”, then supersede it with “Bob”; current-time query returns Bob and an authorized historical query retains provenance for Alice. Forget the original source and confirm derived vectors/summaries/graph facts no longer expose it. Compare code-impact and temporal queries with the baseline as described in the graph adoption gate.
10. **Migration (phase 5):** finish one pre-cutover PDLC execution on the old runtime while a new one runs the published template. Both preserve gate/audit behavior, and duplicated ingress cannot start two runs for the same intended work. Then run a document-review workflow with no work-item/repo/release dependency.

### Repository verification commands and prerequisites for later implementation

Do not run these in plan mode. Execute only against an isolated development environment, not shared real workflows or production integrations.

- Root, Java 21/Maven available: `mvn test`. Testcontainers-backed tests need a live Docker-compatible daemon; real external adapter tests additionally require their configured credentials and must not be reported as exercised when skipped.
- `ui/`, dependencies installed: `npm run build` (TypeScript plus Vite). Browser verification uses the actual admin surface at `http://localhost:5173`, with the API at `http://localhost:8081`; use real browser interaction, network/error inspection and visual confirmation rather than treating typecheck as UI proof.
- `build-worker/`, dependencies installed: `npm run build` then `npm test`. Existing ACP tests do not prove a live ACP session; phase 2 additionally requires the actual sandboxed minimal-agent run.
- Root, isolated Colima/k3s/Tilt stack with seeded local restaurant demo, `curl`/`jq`, live configured model access and a standalone build-worker: `BASE_URL=http://localhost:8081 TARGET_REPO_PATH=<isolated-restaurant-runtime> scripts/e2e-demo-phase4.sh`. The inspected script uses `control-plane/src/main/resources/demo/restaurant-demo.json`, reads that target repository and mutates the seeded live feature. Its comment requires the worker's `PDLC_API_URL`, `BUILD_FILTER_PROFILE` and `ANTHROPIC_OAUTH_TOKEN`; use the actual selected worker/provider configuration, not a guessed substitute.
- When real OIDC replaces the demo header contract, migrate the e2e fixture authentication with the implementation; do not preserve a production header bypass merely to keep the script green. Retain deterministic Temporal fake-activity tests for loop/gate behavior and real adapter/sandbox smoke runs for protocol/security boundaries.

## Assumptions & contingencies

- Product decisions: general-purpose platform, PDLC first template; one enterprise/many workspaces; configuration plus sandboxed extensions; architecture/roadmap deliverable only.
- No enterprise IdP, vault vendor, approved sandbox provider, target scale or latency SLO was supplied. The architecture requires their capabilities but does not claim to have chosen or configured vendor-specific services. A later implementation specification must bind the enterprise deployment details; unavailable services keep their privileged features disabled, never replaced by header auth, plaintext database secrets or host execution.
- Confluence defaults to Cloud for the roadmap. If the enterprise uses Data Center, implement that connector against its own supported API/auth rather than reusing Cloud URLs and assuming equivalence.
- “Graphfy” is addressed as both graph-based knowledge organization and the concrete Graphify candidate found during research. No Graphify product dependency is assumed mandatory; its enrichment role remains replaceable. Graphiti is a separate temporal-memory candidate.
- If an external protocol cannot provide a required lifecycle feature, show that limitation and reject workflows that require it; use another explicitly registered adapter only through a new published agent version. Do not silently downgrade authentication, cancellation or outcome semantics.
- If a graph provider fails evaluation, retain hybrid retrieval and explicit relationships. This changes the optional accelerator, not the promised memory, context or knowledge-management capabilities.

## Primary sources

These sources describe available capabilities, not performance/security validation of this proposed system.

- A2A specification and bindings: https://a2a-protocol.org/latest/specification/ (inspected page identifies released version 1.0.0).
- Agent Client Protocol introduction: https://agentclientprotocol.com/get-started/introduction (local stdio and remote scenarios; remote support is described as evolving).
- MCP authorization: https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization (HTTP OAuth discovery and transport-specific credential handling).
- React Flow: https://reactflow.dev/learn (`@xyflow/react` node/edge UI).
- pgvector: https://github.com/pgvector/pgvector (Postgres vector search).
- Graphify inspected v8 documentation: https://github.com/Graphify-Labs/graphify/tree/v8 (code/document graph enrichment).
- Graphiti: https://github.com/getzep/graphiti (temporal context graphs and provenance).
- LangGraph: https://docs.langchain.com/oss/python/langgraph/overview (stateful agent orchestration).
- Dify: https://github.com/langgenius/dify (visual workflows, models, RAG and agents).
- Figma REST API: https://developers.figma.com/docs/rest-api/ (files/nodes/images, versions, tokens/OAuth).
- Confluence Cloud REST v2: https://developer.atlassian.com/cloud/confluence/rest/v2/intro/ (authentication, permission context and cursor pagination).
- Azure DevOps Git items: https://learn.microsoft.com/en-us/rest/api/azure/devops/git/items/get?view=azure-devops-rest-7.1 (versioned repository content).
