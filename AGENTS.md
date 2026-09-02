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
| `control-plane/src/main/resources/db/migration/` | Flyway `V1__schema.sql` … `V6__agent_mention_columns.sql` |
| `agents/src/main/java/ai/pdlc/agents/{grill,po,plan,review,release,monitor,mention}/` | Per-domain LLM agent components |
| `agents/src/main/java/ai/pdlc/agents/activities/` | `AgentActivitiesImpl`, `AgentContext` (best-effort reads), `RunRecorder` |
| `agents/src/main/java/ai/pdlc/agents/templates/` | `PromptTemplates` (Mustache renderer) |
| `agents/src/main/resources/prompts/*.mustache` | LLM prompt templates, one file per agent-call |
| `build-worker/src/` | Standalone Node ACP build agent (`worker.ts`, `poller.ts`, `acp.ts`, `buildTask.ts`, `repo.ts`, `verifier.ts`) |
| `ui/src/routes/` | `ReviewPage.tsx`, `TaskDetailPage.tsx`, item list |
| `ui/src/review/` | `CommentPanel.tsx`, `PreviewTab`/`SourceTab`/`DiffTab`/`ReviewMdTab`, `GateBadge.tsx` |
| `infra/` | `docker-compose.yml`, `pdlc.yaml` (runtime config), `stub-llm/` (WireMock) |
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
mvn -q -o test -pl core,control-plane,agents -Dtest='!BoardSideEffectsImplTest,!PersistenceIntegrationTest'  # skip Docker-dependent tests (see Testing & QA)
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

**Full stack (Docker Compose, from `infra/`):**
```bash
cd infra && docker compose up -d --build control-plane agents ui   # rebuild + redeploy just these 3
docker compose exec -T postgres psql -U pdlc -d pdlc -c "\d comments"   # inspect schema / verify Flyway applied
```
Services: `postgres` (5432), `temporal` (7233) + `temporal-ui` (8080), `stub-llm` (WireMock,
4000), `control-plane` (8081), `agents` (8082), `ui` (5173→nginx:80). `build-worker` is **not**
in docker-compose — run it as a standalone host process per `scripts/e2e-demo-phase3.sh`.

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
  AgentMentionWorkflowImpl.class)`.
- **Naming:** `*Controller` (REST), `*Entity`+`*Repository` (Spring Data JDBC pair), `*Dto`
  (wire records under `web/dto/`), `*Impl` (interface implementation), `*Config` (Spring
  `@Configuration`), `*Agent` (LLM caller under `agents/`), `Fake*` (in-process Temporal test
  doubles, package-private, colocated with the test).
- **UI mutations:** `@tanstack/react-query` `useMutation` per action, `onSuccess` invalidates
  `['artifact', id]` and/or `['item', id]`; artifact/item queries poll every 2s
  (`refetchInterval: 2000`) so async workflow state transitions (e.g. `running`→`pending`)
  surface without extra wiring. Identity is `useIdentity()`/`setIdentity()` from `ui/src/identity.ts`
  (dev-only `X-User`/`X-Role` header shim, no real auth).
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
- `infra/docker-compose.yml` — 6 services; see Development Commands.
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
- **Docker Compose** is the standard way to run the full stack locally; `docker compose exec -T
  postgres psql -U pdlc -d pdlc` is the standard way to inspect the app database directly.
- The `local` profile's `LocalGitRepoAdapter` and the `agents`/`control-plane` containers all
  need the **same absolute host path** to `target-repos/orders-service` mounted — see the
  volume mounts in `infra/docker-compose.yml`.
- `OPENROUTER_API_KEY` (real LLM calls for the `local` profile against OpenRouter) is expected
  via `infra/.env` (gitignored, not present in a fresh checkout — create it before running
  `agents` against real models). `BUILD_AGENT_TOKEN` is similarly optional/blank-default.

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
  3. **Plain unit tests** — bare JUnit5 + AssertJ (`assertThat`/`assertThatThrownBy`) for
     validators, parsers, config loaders, exception mappers. Reference:
     `control-plane/src/test/java/ai/pdlc/controlplane/review/AgentMentionsTest.java`.
- **Docker-unavailable environments:** exclude the Testcontainers-backed classes:
  ```bash
  mvn -q -o test -pl core,control-plane,agents -Dtest='!BoardSideEffectsImplTest,!PersistenceIntegrationTest'
  ```
  (this is an informal, comment-documented convention — see
  `agents/src/test/java/ai/pdlc/agents/AgentSpringWiringTest.java:34-38` — not a pom-level
  exclusion; `adapters`' `LocalMetricsAdapterTest` needs the same treatment if running that
  module standalone.)
- **Real-network adapter tests** (`adapters/.../ado/AdoBoardAdapterContractTest.java`,
  `.../github/GitHubRepoAdapterWireTest.java`) are gated by
  `@EnabledIfEnvironmentVariable(named = "ADO_ORG"/"ADO_PROJECT"/"ADO_PAT", ...)` — they silently
  skip (not fail) unless those env vars are set.
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
  running `docker compose` stack.
- **build-worker** uses Node's built-in test runner: `node --test --test-reporter=tap dist/*.test.js`
  (must `npm run build` first — tests run against compiled `dist/`, not `src/` directly).
