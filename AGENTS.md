# Repository Guidelines

## Project Overview

pdlc-pilot is an AI-driven Product Development Lifecycle (PDLC) pilot: a multi-agent system that
runs a feature through intake → grill (clarifying questions) → PO story draft → gate 1 (spec
review/approval) → plan → build (autonomous coding loop) → review → gate 2 (PR review) → release
pack → gate 3 (release sign-off) → deploy → monitor, with humans as gate checkers, not
implementers. Every agent (grill/PO/plan/review/release/monitor/mention) is a stateless,
one-shot LLM caller orchestrated by a durable Temporal workflow; a human reviewer can also
`@-mention` an agent (`@analyst`, `@architect`, `@qa`) directly on a review comment for an
ad-hoc, approval-gated analysis.

Architecture ground truth lives in `docs/` and is cited verbatim from Java Javadoc — see
**Important Files** below. Preserve this citation convention (`tech-stack §N`,
`agent-playbook.md §N`, `orchestration-decision §N`) when adding cross-references.

## Architecture & Data Flow

**Hexagonal ports/adapters + a split Temporal worker topology, spread across 5 independently
deployable components:**

```
        REST (React UI)              Temporal (signals/queries/activities)
             |                                    |
      +------v-------+   BOARD/BUILD queues  +----v-----+   REASONING queue
      | control-plane |<---------------------->| agents  |
      | (Spring Boot) |    shared Postgres     | (Spring |
      +------+--------+<---------------------->|  Boot)  |
             |                                  +----+----+
             | REST (claim/heartbeat/result)         | Embabel `Ai` -> LLM gateway
      +------v-------+                               |
      | build-worker |  (Node/TS, standalone,    per-domain agents (grill/po/plan/
      | (ACP/omp)    |   polls control-plane)     review/release/monitor/mention)
      +--------------+
```

- **`core/`** — pure domain (no Spring, no web): domain records, hexagonal port interfaces
  (`BoardPort`, `RepoPort`, `CiPort`, `MetricsPort`, `SecretsPort`, `NotifyPort`), `pdlc.yaml`
  config loader, pure `review.md` formatters, and **both** Temporal workflow interfaces + impls
  (`FeatureWorkflow`/`Impl`, `AgentMentionWorkflow`/`Impl`). Workflow impls live here so
  control-plane's client and agents' worker share one class/version.
- **`adapters/`** — one package per external system implementing core ports: `ado` (Azure
  DevOps board), `github`/`localgit` (repo, real git plumbing), `inmemory` (test-double
  board+repo, used by the `local` profile), `remoteboard` (HTTP proxy), `localci`,
  `localmetrics`, `secrets`. No Spring annotations; wired manually per-module.
- **`control-plane/`** — Spring Boot REST API + webhook ingress + persistence (Spring Data JDBC)
  + Flyway migrations + `review.md` audit-trail writer + Temporal **client** (starts
  `FeatureWorkflow`/`AgentMentionWorkflow`) **and** its own worker hosting only
  `BoardSideEffectsImpl` (queue `BOARD`) and `BuildActivitiesImpl` (queue `BUILD`) — never
  `REASONING`.
- **`agents/`** — Spring Boot service embedding Embabel's injectable `Ai` bean; hosts the sole
  worker for `AgentActivities` **and** the workflow implementation types, all on queue
  `REASONING`. Per-domain agents are one-shot LLM callers over Mustache-rendered prompts.
- **`build-worker/`** — standalone Node/TypeScript process, *not* Temporal-connected. Polls
  control-plane's REST API (`/api/build-tasks/claim`) for claimable tasks, drives a configurable
  ACP coding agent (default `omp acp`) against a real git worktree, reports heartbeats/results
  back over HTTP. See `build-worker/src/config.ts` for the full env surface.

**Why the queue split (`TaskQueues.REASONING`/`BOARD`/`BUILD`):** co-locating disjoint activity
sets on one Temporal queue caused "not registered" errors with exponential retry backoff up to
100s on nearly every gate step — see the Javadoc on `core/src/main/java/ai/pdlc/core/workflow/TaskQueues.java`.
`control-plane` and `agents` **never** depend on each other in Maven; they only connect via
Temporal + shared Postgres.

**Data flow example (the `@-mention` feature, most recently added):** UI posts a comment →
`ArtifactsController.addComment` parses `@analyst|@architect|@qa` via `AgentMentions.parse`,
saves the comment `status=running`, starts `AgentMentionWorkflow` → agents' worker runs
`MentionAgent.analyze` (renders a Mustache prompt, calls the LLM) → `AgentMentionWorkflowImpl`
calls `BoardSideEffectsImpl.saveAgentMentionResult` (status `pending`/`failed`) → UI polls (2s)
and shows the draft → a PO/SquadLead approves via `POST .../approve-agent-result` → appended to
`review.md` (`ReviewMdWriter.agentResultBlock`) and `review_events`.

## Key Directories

| Path | Purpose |
|---|---|
| `core/src/main/java/ai/pdlc/core/domain/` | ~30 pure domain records (`WorkItem`, `Comment`, `Approval`, `Anchor`, `*Handoff`, `AgentMentionRequest`, …) |
| `core/src/main/java/ai/pdlc/core/port/` | Hexagonal port interfaces implemented by `adapters/` |
| `core/src/main/java/ai/pdlc/core/config/` | `PdlcConfig`/`Profile`/`GateConfig` — typed `pdlc.yaml` loader |
| `core/src/main/java/ai/pdlc/core/review/` | Pure formatters: `ReviewMdWriter`, `GrillMdSerializer`, `ReleaseManifestSerializer` |
| `core/src/main/java/ai/pdlc/core/workflow/` | Temporal `@WorkflowInterface`/`@ActivityInterface` + impls, `TaskQueues` |
| `adapters/src/main/java/ai/pdlc/adapters/{ado,github,localgit,inmemory,remoteboard,localci,localmetrics,secrets}/` | Port implementations |
| `control-plane/src/main/java/ai/pdlc/controlplane/web/` | REST controllers + `dto/` + `ApiExceptionHandler` |
| `control-plane/src/main/java/ai/pdlc/controlplane/persistence/` | Spring Data JDBC entities/repositories |
| `control-plane/src/main/java/ai/pdlc/controlplane/temporal/` | `BoardSideEffectsImpl`, `BuildActivitiesImpl`, `WorkerConfig`, `WorkflowStubs` |
| `control-plane/src/main/java/ai/pdlc/controlplane/review/` | `ReviewTrailService`, `CommentReanchorer`, `AgentMentions` |
| `control-plane/src/main/java/ai/pdlc/controlplane/platform/` + `core/.../platform/` | Configurable agent platform (docs/phase-1-execution-spec.md): workspaces, capabilities, versioned `AgentSpec` registry, `ContentHash` |
| `control-plane/src/main/java/ai/pdlc/controlplane/connections/` | Connections (secret *references* only) and the DB-backed `ModelCatalog`, seeded once from `pdlc.yaml` by `ModelCatalogSeeder` |
| `control-plane/.../platform/{ToolRegistryService,VersionedDefinitions,DefinitionStore}` + `agents/.../platform/{ToolExecutor,ToolStore}` + `core/.../platform/{ToolSpec*,ToolArgs,EgressPolicy}` | Governed API tools (docs/phase-2-execution-spec.md slice 2.1): versioned tool registry sharing the agent lifecycle, `HTTP_API` connections granted per workspace (`connection_grants`), the bounded tool loop and its `platform_tool_calls` trace |
| `adapters/.../mcp/` + `control-plane/.../connections/McpDiscoveryService` + `agents/.../platform/{McpToolCaller,McpCredentials,McpDiscoveryActivitiesImpl}` + `ui/src/studio/McpDiscoveryPanel.tsx` | Remote MCP tools (docs/phase-2-execution-spec.md slice 2.3): governed Streamable-HTTP client, OAuth client credentials, discovery/review, fingerprint-checked execution |
| `control-plane/.../runs/{ApprovalService,JdbcApprovalStore}` + `ui/src/studio/ApprovalsSection.tsx` | Write approvals inbox and effect resolution (docs/phase-2-execution-spec.md slice 2.2) |
| `core/.../port/SandboxPort` + `adapters/.../sandbox/` + `control-plane/.../sandbox/` + `agents/.../platform/SandboxToolRunner` + `agents/.../config/SandboxConfig` + `infra/k8s/sandbox.yaml` | Sandbox tools (docs/phase-2-execution-spec.md slice 2.4): enterprise image catalog (`sandbox_images`), `KubernetesJobSandbox` (Job per call under a gVisor RuntimeClass) and `DockerSandbox` (local dev under `runsc`), and the per-call credentialed `SandboxEgressProxy` |
| `adapters/.../a2a/` + `agents/.../platform/{A2aRunner,A2aCardActivitiesImpl}` + `control-plane/.../connections/A2aCardService` + `core/.../workflow/A2aCard*` | A2A outbound delegation (docs/phase-2-execution-spec.md slice 2.5): `runtime: a2a` agents on `A2A_AGENT` connections, A2A 1.0/0.3 JSON-RPC client, remote task tracking, operator replies |
| `control-plane/.../runs/` + `agents/.../platform/` + `core/.../workflow/AgentRunWorkflow*` | Durable single-agent runs: `RunService` pins version+model on a `platform_runs` row and starts `AgentRunWorkflow`; the agents-side `AgentRunActivitiesImpl` renders, calls the model at runtime (`OpenAiCompatibleModelInvoker`) and records the outcome |
| `control-plane/src/main/resources/db/migration/` | Flyway `V1__schema.sql` … `V23__a2a_runs.sql` |
| `agents/src/main/java/ai/pdlc/agents/{grill,po,plan,review,release,monitor,mention}/` | Per-domain LLM agent components |
| `agents/src/main/java/ai/pdlc/agents/activities/` | `AgentActivitiesImpl`, `AgentContext` (best-effort reads), `RunRecorder` |
| `agents/src/main/java/ai/pdlc/agents/templates/` | `PromptTemplates` (Mustache renderer) |
| `core/src/main/resources/prompts/*.mustache` | LLM prompt templates, one file per agent-call (in `core` so control-plane's `PdlcImportSeeder` can import them as platform agents; agents still loads them from the classpath) |
| `build-worker/src/` | Standalone Node ACP build agent (`worker.ts`, `poller.ts`, `acp.ts`, `buildTask.ts`, `repo.ts`, `verifier.ts`) |
| `ui/src/routes/` | `ReviewPage.tsx`, `TaskDetailPage.tsx`, item list |
| `ui/src/studio/` | Agent Studio (docs/phase-1-execution-spec.md slice 5): workspace-scoped agent list, editor, versions/diff/rollback, test runs; API client is `studio` in `ui/src/api.ts` |
| `ui/src/review/` | `CommentPanel.tsx`, `PreviewTab`/`SourceTab`/`DiffTab`/`ReviewMdTab`, `GateBadge.tsx` |
| `infra/` | `Tiltfile`-referenced `k8s/*.yaml`, `pdlc.yaml` (runtime config), `stub-llm/` (WireMock) |
| `docs/` | Architecture ground truth: `agent-playbook.md`, `orchestration-decision.md`, `tech-stack-architecture.md`, `storyboard.md` |
| `scripts/` | `e2e-demo.sh` (gate 1), `e2e-demo-phase3.sh` (build+gate 2), `e2e-demo-phase4.sh` (release+gate 3) — runnable, assertion-bearing walkthroughs |
| `target-repos/orders-service` | Real git checkout used by the `local` profile's `LocalGitRepoAdapter`; `openspec/` inside it is the canonical change-folder shape agents read/write |

## Development Commands

**Java (Maven multi-module reactor, Java 21):**
```bash
mvn test                                                    # full reactor test run
mvn -pl control-plane -am test                              # one module + its deps
mvn -pl control-plane test -Dtest=AgentMentionsTest         # one test class
mvn -pl control-plane test -Dtest=AgentMentionsTest#parsesEachSupportedAgentCaseInsensitively  # one method
mvn -q -o test -pl core,control-plane,agents -Dtest='!BoardSideEffectsImplTest,!PersistenceIntegrationTest,!BuildTaskLeaseTest,!PlatformRegistryIntegrationTest,!JdbcProjectDirectoryTest,!DemoReadOnlyTest,!DemoInitializerIntegrationTest,!LocalBoardAdapterIntegrationTest'  # skip Docker-dependent tests (see Testing & QA)
```

**UI (`ui/`, Node/Vite):**
```bash
cd ui && npm run dev       # dev server, http://localhost:5173, talks directly to localhost:8081
cd ui && npm run build     # tsc --noEmit && vite build  (this IS the type-check gate — no separate lint script)
cd ui && npm run preview
```

**build-worker (`build-worker/`, Node/TS):**
```bash
cd build-worker && npm run build   # tsc -p tsconfig.json
cd build-worker && npm test        # node --test --test-reporter=tap dist/*.test.js (build first)
cd build-worker && npm start       # node dist/worker.js — requires PDLC_API_URL + BUILD_FILTER_PROFILE env
```

**Full stack (Tilt + Colima k3s, from repo root):**
```bash
tilt up                                                        # dev loop: live rebuild/redeploy on file change
tilt ci                                                         # one-shot: build, apply, wait for readiness, exit
kubectl -n pdlc exec -it deploy/postgres -- psql -U pdlc -d pdlc -c "\d comments"   # inspect schema / verify Flyway applied
```
Services (all reachable on `localhost` via k3s LoadBalancer, forwarded by Colima): `postgres`
(5432), `temporal` (7233) + `temporal-ui` (8080), `stub-llm` (WireMock, 4000), `control-plane`
(8081), `agents` (8082), `ui` (5173→nginx:80). `build-worker` is **not** a Kubernetes
workload — run it as a standalone host process per `scripts/e2e-demo-phase3.sh`.

**End-to-end verification** (no test suite covers cross-service flows — these scripts are the
closest thing to integration tests):
```bash
scripts/e2e-demo.sh          # gate 1: webhook -> grill -> story -> comment -> approve
scripts/e2e-demo-phase3.sh   # + plan -> build loop -> PR -> gate 2 (needs build-worker running)
scripts/e2e-demo-phase4.sh   # + release pack -> gate 3 -> deploy -> monitor
```

## Code Conventions & Common Patterns

- **Domain records + wither factories.** Every domain type and persistence entity is a Java
  `record`. Persistence entities add a static `newRow(...)` factory and `withX(...)` copy
  methods instead of setters, e.g. `CommentEntity.newRow(...)`, `.withAgentRequest(agentName)`,
  `.withAgentResult(markdown, status)`, `.withAgentApproved(approvedBy)`. Never mutate — always
  produce a new record and `repository.save(...)` it.
- **Hexagonal ports.** `core/port/*.java` declares interfaces; `adapters/` implements them; each
  Spring module's own `AdapterBeans` config picks the concrete adapter per `pdlc.yaml`
  `board.provider`/`repo.provider`. Never call a concrete adapter directly from `core` or from a
  controller — depend on the port interface.
- **Temporal activity options template.** Copy `FeatureWorkflowImpl`'s `AGENT_ACTIVITY_OPTIONS`
  (`TaskQueues.REASONING`, 10 min) / `BOARD_ACTIVITY_OPTIONS` (`TaskQueues.BOARD`, 2 min) pattern
  for any new workflow; deviate deliberately when needed (e.g. `AgentMentionWorkflowImpl` bounds
  retries to `RetryOptions.newBuilder().setMaximumAttempts(2)` so a persistent LLM failure lands
  in a terminal `failed` state instead of retrying forever).
- **Starting a workflow from control-plane:** `WorkflowOptions` + `WorkflowClient.start(...)` +
  catch `WorkflowExecutionAlreadyStarted` (idempotent webhook-replay pattern) — see
  `WebhookController.startFeatureWorkflow` and `ArtifactsController.startMentionWorkflow`.
- **REST exceptions.** Throw `NotFoundException`/`ForbiddenException`/`ConflictException`
  (`control-plane/.../web/`) or `BuildTaskGoneException`; `ApiExceptionHandler`
  (`@RestControllerAdvice`) maps them to 404/403/409/410. Add new exception types there, don't
  return raw `ResponseEntity.status(...)` from controllers.
- **Gate-role checks.** Inline pattern used everywhere a G1/G2/G3 action needs authorization:
  `pdlcConfig.profile(story.profile()).gate("G1").roles().contains(identity.role())` else throw
  `ForbiddenException` (see `ItemsController.approve`, `ArtifactsController.approveAgentResult`).
- **Dual audit trail.** Every reviewer-visible mutation calls both
  `ReviewTrailService.appendReviewMd(storyRef, slug, block)` (git-committed markdown, formatted
  by a pure static method on `ReviewMdWriter`) **and** `appendReviewEvent(workItemId, kind,
  payloadMap)` (Postgres `review_events`). These two trails must always agree — see
  `tech-stack-architecture.md` §3.3.
- **LLM agent shape.** Every per-domain agent in `agents/src/main/java/ai/pdlc/agents/<domain>/`
  follows: constructor `(Ai ai, PromptTemplates templates, Profile activeProfile[, extra ports])`
  → private `promptRunner()` helper (`ai.withLlm(LlmOptions.withModel(roleModel))` if
  `activeProfile.agents().roles().get("<role>")` is configured, else `ai.withDefaultLlm()`, copy
  verbatim from `ReleaseAgent.java`) → render a Mustache prompt via `templates.render(name,
  viewMap)` → `promptRunner().generateText(prompt)`. Best-effort board/repo reads go through the
  static `AgentContext.readFile/readWorkItem/readComments` helpers (catch, log WARN, return
  null/empty — never propagate).
- **Adding an LLM role:** add `<role>: { model: ..., budget_tokens: ... }` under
  `agents.roles` in **both** `profiles.local` and `profiles.ado-pilot` in `infra/pdlc.yaml`;
  `AgentsApplication.applyLlmRoutingFromConfig()` auto-collects every `roles.*.model` at startup
  — no extra Java wiring needed for model routing.
- **Registering a new Temporal workflow type:** one line in
  `agents/src/main/java/ai/pdlc/agents/config/WorkerConfig.java`:
  `worker.registerWorkflowImplementationTypes(FeatureWorkflowImpl.class,
  AgentMentionWorkflowImpl.class, AgentRunWorkflowImpl.class)`.
- **Naming:** `*Controller` (REST), `*Entity`+`*Repository` (Spring Data JDBC pair), `*Dto`
  (wire records under `web/dto/`), `*Impl` (interface implementation), `*Config` (Spring
  `@Configuration`), `*Agent` (LLM caller under `agents/`), `Fake*` (in-process Temporal test
  doubles, package-private, colocated with the test).
- **UI mutations:** `@tanstack/react-query` `useMutation` per action, `onSuccess` invalidates
  `['artifact', id]` and/or `['item', id]`; artifact/item queries poll every 2s
  (`refetchInterval: 2000`) so async workflow state transitions (e.g. `running`→`pending`)
  surface without extra wiring. Identity is `useIdentity()` from `ui/src/identity.ts`, fed either by the dev
  `X-User`/`X-Role` switcher (only when `/api/me` reports `dev-headers`) or by the OIDC session via
  `useAuth()`; `api.ts` sends `credentials: 'include'` and the `X-XSRF-TOKEN` CSRF header.
- **Authentication (control-plane `identity/`).** `SecurityConfig` owns all authentication:
  enterprise OIDC login, service client-credentials JWTs (scopes `pdlc.build`/`pdlc.board.read`/
  `pdlc.webhook`), shared tokens, and the dev header shim (`PDLC_IDENTITY_DEV_HEADERS=true`, off by
  default, on in the local k8s manifest). Controllers only call `IdentityResolver.resolve(...)`;
  never read `X-User`/tokens directly. See docs/phase-1-execution-spec.md slice 2.
- **Tools run only through `ToolExecutor`** (agents, docs/phase-2-execution-spec.md slice 2.1). It re-checks the
  pin, content hash, args, effect (WRITE is denied until approvals in slice 2.2), connection and grant *at call time*,
  applies `EgressPolicy` (http(s) only; every resolved address public unless in
  `pdlc.egress.allowed-private-hosts`), never follows redirects, and records every decision. Never add another way
  to make a tool's HTTP call, and never let Spring AI execute tool callbacks.
- **Writes need an approval and an effect intent** (slice 2.2). A WRITE runs only under an `APPROVED`
  `platform_approvals` row matching its tool version and args hash, decided by a workspace `REVIEWER` who did not
  start the run; an `INTENDED` `platform_effects` row keyed `run:turn:callId` is recorded before sending. Never resend
  an effect whose outcome is known; resend an `UNKNOWN` one only for `idempotency: HEADER` tools, otherwise pause for
  an operator. Runs pause and resume through `AgentRunWorkflow` signals, with the conversation in
  `platform_run_messages` - never put prompt/tool content in workflow history.
- **MCP tools** (slice 2.3) are `kind: mcp` tool versions pinning the remote tool, its schema and an `McpFingerprint`;
  `ToolExecutor` refuses a call when the server's current definition differs. Talk to MCP servers only through
  `adapters/.../mcp/McpHttpClient` (egress-guarded, no redirects) and `McpOAuth` - never the MCP SDK transport - and
  keep credential use in agents (discovery runs as `McpDiscoveryWorkflow` on `REASONING`).
- **Sandbox tools** (slice 2.4) are `kind: sandbox` tool versions pinning an enterprise catalog image by digest;
  `ToolExecutor` refuses a call when the entry is retired, re-pinned or its schema changed, or when no isolation
  runtime is configured (`pdlc.sandbox.provider=none` is the default). Run images only through `SandboxPort`
  (non-root, read-only root, dropped capabilities, no host mounts or runtime socket, fresh workspace per call), give
  them network only through `SandboxEgressProxy` with a per-call credential that is revoked when the call ends or the
  run is cancelled, and never pass input or credentials on a command line or in a Job spec.
- **A2A delegation** (slice 2.5): talk to remote agents only through `adapters/.../a2a/A2aClient`. Never trust the Agent
  Card for more than skills, version and a same-origin endpoint; re-read it per invocation. Record every message's
  intent (`platform_remote_sends`) before sending and the remote task id as soon as it is known; a retry reconciles via
  `GetTask`, and an outcome that cannot be reconciled fails the run instead of resending. Report remote states honestly
  (input-required/auth-required pause the run; a refused cancel is recorded as refused).
- **No linter/formatter configured anywhere** (no ESLint, Prettier, Checkstyle, Spotless,
  `.editorconfig`). Match surrounding code style by hand; TypeScript's only enforced gate is
  `tsc --noEmit` (strict mode) inside `npm run build`.

## Important Files

- `pom.xml` — reactor root: modules `core, adapters, control-plane, agents`; pins Java 21,
  Spring Boot 4.1.0, Embabel 1.5.1, Temporal 1.30.1, JUnit 5.11.3, AssertJ 3.26.3.
- `infra/pdlc.yaml` — the domain-level runtime config: two profiles (`local`, `ado-pilot`), each
  with `board`/`repo`/`notify`/`agents.roles`/`gates` sections. Loaded via
  `PdlcConfig.loadFromFile`, path/profile chosen by `PDLC_CONFIG_PATH`/`PDLC_ACTIVE_PROFILE` env
  vars. **Config-drift is tested**: `control-plane/src/test/java/ai/pdlc/controlplane/config/InfraPdlcYamlTest.java`
  loads this exact file (`../infra/pdlc.yaml`), not a fixture copy.
  If you add a new `agents.roles.<name>` entry, add it to both profiles.
- **Project config is DB-backed.** A project's `board`/`repos`/`gates`/`docs`/`brief` now live in
  the `projects` table (migration `V13__projects.sql`), seeded once from `pdlc.yaml` at startup by
  `ProjectSeeder` and editable afterward only via the `/projects` UI as an Admin. `pdlc.yaml` edits
  after first boot are ignored for existing projects; `pdlc.yaml` still owns deployment-level
  `agents.gateway`/`agents.roles`/`agents.prompts_dir` and `notify` settings.
- `Tiltfile` / `infra/k8s/*.yaml` — Kubernetes manifests + Tilt orchestration for the local stack (9 objects); see Development Commands.
- `control-plane/src/main/resources/db/migration/V*.sql` — Flyway migrations, strictly additive
  (no DOWN scripts); add `V7__*.sql` for new schema, never edit an applied migration.
- `docs/tech-stack-architecture.md` — most-cited doc from Javadoc (`tech-stack §N`); full stack
  table, maker-checker mechanics, comment-anchoring algorithm, Postgres data model, `pdlc.yaml`
  schema reference.
- `docs/agent-playbook.md` — canonical per-agent spec (trigger/reads/does/validates/produces
  handoff/stop-rules) for all 8 agents; cited as `agent-playbook.md §N`.
- `docs/orchestration-decision.md` — why Temporal + Embabel + omp/ACP, runtime topology,
  code-sketch level detail; cited as `orchestration-decision §N`.
- `core/src/main/java/ai/pdlc/core/workflow/TaskQueues.java` — the queue-split rationale
  (read the Javadoc before adding a new activity interface).
- `control-plane/src/main/java/ai/pdlc/controlplane/web/WebhookController.java` — template for
  starting a Temporal workflow idempotently from a REST/webhook entry point.
- `agents/src/main/java/ai/pdlc/agents/release/ReleaseAgent.java` — canonical `promptRunner()`
  pattern to copy for any new LLM agent.
- `scripts/e2e-demo.sh` / `-phase3.sh` / `-phase4.sh` — exact REST endpoints, headers
  (`X-User`/`X-Role`), and webhook payload shapes for the whole pipeline; best reference for
  manual API testing.

## Runtime/Tooling Preferences

- **Java 21**, built with **Maven** (no Gradle anywhere). Use the reactor `pom.xml` at root;
  don't invoke `javac`/`java` directly.
- **UI and build-worker use Node** (not Bun) — `package-lock.json` present in both, install with
  `npm ci`/`npm install`. `ui/Dockerfile` builds with `node:22-alpine`.
- **Tilt on Colima's k3s** is the standard way to run the full stack locally; `kubectl -n pdlc
  exec -it deploy/postgres -- psql -U pdlc -d pdlc` is the standard way to inspect the app
  database directly.
- The `local` profile's `LocalGitRepoAdapter` and the `agents`/`control-plane` pods all need the
  **same absolute host path** to `target-repos/orders-service` mounted — see the `hostPath`
  volumes in `infra/k8s/control-plane.yaml`/`infra/k8s/agents.yaml`.
- `infra/.env` (gitignored; copy `infra/.env.example`) may set `PDLC_LLM_BASE_URL` (overrides
  `agents.gateway`) and `PDLC_LLM_API_KEY` (blank for endpoints without authentication);
  `agents.gateway` is any OpenAI-compatible base URL used verbatim. `BUILD_AGENT_TOKEN` is
  similarly optional/blank-default.

## Testing & QA

- **Frameworks:** JUnit 5.11.3 + AssertJ (pinned once in root `pom.xml`, inherited everywhere).
  Mockito arrives transitively via `spring-boot-starter-test` (control-plane only — plain
  `Mockito.mock(...)`, no `@Mock`/`@InjectMocks` annotations observed). `io.temporal:temporal-testing`
  (`TestWorkflowEnvironment`) for in-process workflow tests in `core`/`agents`. Testcontainers
  1.21.4 (Postgres) for real-DB integration tests in `control-plane`/`adapters`. WireMock 3.13.2
  for adapter HTTP contract tests in `adapters`. **No JaCoCo/coverage plugin anywhere.**
- **Three test patterns, pick the right one:**
  1. **Temporal workflow tests** — `TestWorkflowEnvironment` + hand-written in-process
     `Fake<Interface>` activity classes (`FakeAgentActivities`, `FakeBoardSideEffects`,
     `FakeBuildActivities`, package-private, colocated with the test). No Mockito at this layer.
     Reference: `core/src/test/java/ai/pdlc/core/workflow/FeatureWorkflowImplTest.java`.
  2. **Persistence/integration tests** — `@Testcontainers` + real `postgres:16-alpine` container
     + explicit `Flyway.configure()...migrate()` in `@BeforeAll` + a minimal
     `@SpringBootTest(classes = TestApp.class)` inner-class context (Spring Boot 4 dropped
     `@DataJdbcTest`). **Requires a live Docker daemon.** Reference:
     `control-plane/src/test/java/ai/pdlc/controlplane/persistence/PersistenceIntegrationTest.java`,
     `.../temporal/BoardSideEffectsImplTest.java`, `adapters/.../localmetrics/LocalMetricsAdapterTest.java`.
     `.../temporal/BuildTaskLeaseTest.java` follows the same Testcontainers+Flyway setup but skips
     the Spring context entirely (plain `JdbcTemplate` over the container's datasource).
  3. **Plain unit tests** — bare JUnit5 + AssertJ (`assertThat`/`assertThatThrownBy`) for
     validators, parsers, config loaders, exception mappers. Reference:
     `control-plane/src/test/java/ai/pdlc/controlplane/review/AgentMentionsTest.java`.
- **Docker-unavailable environments:** exclude the Testcontainers-backed classes:
  ```bash
  mvn -q -o test -pl core,control-plane,agents -Dtest='!BoardSideEffectsImplTest,!PersistenceIntegrationTest,!BuildTaskLeaseTest,!PlatformRegistryIntegrationTest,!JdbcProjectDirectoryTest,!DemoReadOnlyTest,!DemoInitializerIntegrationTest,!LocalBoardAdapterIntegrationTest'
  ```
  (this is an informal, comment-documented convention — see
  `agents/src/test/java/ai/pdlc/agents/AgentSpringWiringTest.java:34-38` — not a pom-level
  exclusion; `adapters`' `LocalMetricsAdapterTest` needs the same treatment if running that
  module standalone.)
- **Real-network adapter tests** (`adapters/.../ado/AdoBoardAdapterContractTest.java`,
  `.../github/GitHubRepoAdapterWireTest.java`) are gated by
  `@EnabledIfEnvironmentVariable(named = "ADO_ORG"/"ADO_PROJECT"/"ADO_PAT", ...)` — they silently
  skip (not fail) unless those env vars are set. Likewise `adapters/.../sandbox/KubernetesJobSandboxClusterTest`
  needs `PDLC_K8S_API`/`PDLC_K8S_TOKEN`/`PDLC_K8S_CA` (set by `scripts/sandbox-colima.sh`), and `DockerSandboxTest`
  skips itself unless a Docker engine with the `runsc` runtime and `busybox:1.36` is available.
- **Config-drift test:** `InfraPdlcYamlTest` loads the real `infra/pdlc.yaml`; when adding a new
  `agents.roles` entry, extend its `containsKeys(...)` assertion to keep it meaningful.
- **`agents/src/test/java/ai/pdlc/agents/AgentSpringWiringTest.java`** proves Spring can
  construct `PromptTemplates` + the LLM agents via real constructor injection (catches the
  two-constructor `PromptTemplates` ambiguity a plain `mvn compile` can't). It hand-wires only
  the beans it needs (no live Temporal/Postgres) — extend it for a new agent, but it's fine to
  skip if awkward (not every agent has been added here historically).
- **UI has zero automated test coverage** — no vitest/jest, no `*.test.tsx` files, no `test`
  script in `ui/package.json`. The only build-time gate is `tsc --noEmit` (strict mode) inside
  `npm run build`. For UI changes, verify manually via `npm run dev` + browser, or against the
  running Tilt/Kubernetes stack.
- **build-worker** uses Node's built-in test runner: `node --test --test-reporter=tap dist/*.test.js`
  (must `npm run build` first — tests run against compiled `dist/`, not `src/` directly).
