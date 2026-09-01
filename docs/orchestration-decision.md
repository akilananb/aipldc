# Orchestration decision — omp, Embabel, goose, and who runs the loop

Decision in one paragraph: **omp is the coding agent** (ACP mode inside a sandbox, driven by a build worker). **Embabel is the runtime for every reasoning agent** — grill, PO, plan, review, release, monitor — because those agents must emit typed, validated artifacts and Embabel's deterministic planner plus Spring server is built for exactly that. **goose is not the orchestrator**; keep it in two scoped roles — an ACP-addressable general executor for MCP/browser-heavy side tasks, and the FS Developer's desktop agent with omp as its ACP provider. **The orchestration server is a durable workflow engine (Temporal)**, one workflow per work item, with agents as stateless workers on task queues. Per-agent schedulers that poll the board for state changes are rejected except as a single reconciler fallback for boards without webhooks.

---

## 1. The question underneath: server vs per-agent watchers

| Option | How it works | Where it breaks |
|---|---|---|
| **A. Per-agent loop with its own scheduler** (each agent polls the board, picks items in "its" state) | 8 pollers, each `while(true){ query board; act; sleep }` | Races: two workers pick the same item. Gate semantics smeared across 8 codebases ("wait for two approvals on this version" lives nowhere). N× board API load. No backpressure. Restarts lose in-flight context. Audit = reconstructing from logs. Every new agent adds a poller. |
| **B. One in-memory orchestration server** (e.g. goose serve, or a hand-rolled state machine) | Holds item state and gate waits in process | Restart loses waits that can last days. Horizontal scale needs sticky sessions or external state anyway. You end up writing a workflow engine. |
| **C. Durable workflow server + stateless workers** (chosen) | Event ingress → `FeatureWorkflow(item)` in Temporal → activities dispatched to role-specific task queues → agents run, return typed results → workflow waits on signals for gates | Operational cost of running Temporal (or a managed cloud). Team must learn workflow/activity separation. |

C is the scalable answer because the three hard things — *waiting for humans across days*, *exactly-once-ish progression of an item*, and *auditability* — are the engine's native features, not code you maintain. Agents become pure functions: input envelope → output envelope.

**Where a watcher is acceptable:** one reconciler loop for a board that has no reliable webhooks (or as a safety net alongside webhooks). It emits idempotent events `item.changed{id, rev}`; the workflow ignores events it has already seen. One loop, not eight, and it never decides anything.

---

## 2. Choosing between Embabel and goose

They are not the same kind of thing, which is why "choose one for orchestration" is the wrong frame.

| | Embabel | goose |
|---|---|---|
| What it is | Agent *framework*: typed domain model, `@Agent/@Goal/@Action/@Condition`, deterministic GOAP planner that replans after every action, Spring Boot | Agent *harness/product*: general-purpose agent with MCP extensions, recipes, subagents, desktop + CLI, `goose acp` / `goose serve` |
| Runs as a server | Natively (Spring Boot); exposes agents as MCP server; Temporal Java SDK available | `goose serve` = ACP over HTTP/WS, session-oriented; `goose schedule` = cron for recipes |
| Typed, validated outputs | Core strength — a `Story` object with INVEST checks as `@Condition`s; the planner won't reach the goal until conditions hold | Free-text/JSON by prompt; validation is on you |
| Multi-day gate waits | No (not its job) | No (not its job) |
| Speaks ACP | No (MCP yes) | Yes — as agent (`goose acp`, `goose serve`) *and* as client ("ACP providers": goose delegating to an external ACP agent) |
| Coding | Not its focus | Yes, but omp is better at it (LSP/DAP/hashline) and is already chosen |
| Where it shines here | Grill, PO, plan, review, release, monitor: reasoning that must produce a contract for the next agent | Executing MCP/browser/shell-heavy one-off tasks; being the human's desktop agent; wrapping other ACP agents |
| Licence / governance | Apache-2.0, Embabel org (Rod Johnson) | Apache-2.0, AAIF / Linux Foundation |

**Why Embabel for the reasoning agents.** Every handoff in the playbook is a typed contract (`handoff:` envelope, INVEST results, coverage/DAG results, manifest statuses). In Embabel those are domain objects; the checks are `@Condition`s; "revise only the commented lines" is a replan from a new world state (the comments), not a new prompt. Deterministic plans are explainable to the checker — "it ran these three actions because these two conditions were false" is exactly what a PO wants to see in `review.md`. And it is a Spring Boot service: metrics, health, OIDC, Temporal Java SDK, all standard.

**Why goose is not the orchestrator.** `goose serve` is a session server for clients (desktop, mobile, bots), and `goose schedule` runs recipes on a cron — using them to drive the lifecycle means encoding the state machine in recipes and schedules, which is option A above with better branding. goose also has no typed-output contract and no separation-of-duties concept.

**Why still keep goose.** Two real roles: (1) **ACP executor** — an embabel agent (or the workflow) hands a bounded task to `goose serve` over ACP when the task is "use these five MCP servers and a browser and report back" (e.g. QA e2e exploration, docs sync, ADO/Jira housekeeping). (2) **Desktop for the FS Developer** — goose desktop with omp configured as its ACP provider gives one UI over the same `tasks.md`, same branch, same LiteLLM gateway, so the human's loop and the headless loop are the same loop. If neither role is needed in the pilot, drop goose; nothing else depends on it.

**If your team is not JVM:** the same architecture holds with LangGraph (Python/TS) in Embabel's slot. Don't run both Embabel and LangGraph for the same roles.

---

## 3. Division of labour: who loops where

Three loops, three owners:

| Loop | Owner | Scope | Persistence |
|---|---|---|---|
| **Lifecycle loop** (item → gates → release → monitor) | Temporal `FeatureWorkflow` | Across agents, across days, across restarts | Workflow history |
| **Planning loop inside one agent** (plan → act → observe world → replan until goal) | Embabel GOAP, inside one Temporal activity | Seconds to minutes; bounded by activity timeout + token budget | Embabel process state; trace in Langfuse |
| **Coding loop inside one task** (reason → act → observe → verify → stop) | omp session, driven over ACP by the build worker inside one long-running activity with heartbeats | One task, one sandbox; bounded by iteration/token/wall-clock budget | Activity heartbeat details + omp JSONL session; trace |

Rule: an inner loop never touches board state or gates; it returns a typed result and the outer loop decides.

---

## 4. Runtime topology

```
                     webhooks / one reconciler
 Jira · ADO · GitHub ───────────────► Event ingress (Spring, idempotent by item+rev)
                                              │ start / signal
                                              ▼
                              ┌─────────────────────────────┐
                              │ Temporal (orchestration      │
                              │ server, HA cluster)          │
                              │ FeatureWorkflow per item     │
                              │ TaskWorkflow per build task  │
                              └──┬──────────┬───────────┬───┘
            task queues:  reasoning   │   build   │   executor
                              ▼          ▼           ▼
   ┌──────────────────────────────┐ ┌──────────────────┐ ┌─────────────────────┐
   │ Embabel agent service        │ │ Build workers    │ │ goose serve pool    │
   │ (Spring Boot, N replicas)    │ │ (N; each owns a  │ │ (optional, M)       │
   │ grill · po · plan · review · │ │ sandbox slot)    │ │ ACP executor for    │
   │ release · monitor agents     │ │ spawn container: │ │ MCP/browser tasks   │
   │ MCP clients: board, repo,    │ │  omp --mode acp  │ │                     │
   │ CI, metrics                  │ │ worker = ACP     │ │                     │
   │ exposes agents as MCP server │ │ client; enforces │ │                     │
   │ (for goose / omp to call)    │ │ budget; heartbeat│ │                     │
   └──────────────┬───────────────┘ └────────┬─────────┘ └──────────┬──────────┘
                  │                          │                      │
                  └──────────────┬───────────┴──────────────────────┘
                                 ▼
                 LiteLLM gateway (per-role routing, per-task budgets) → models
                 Langfuse traces · Postgres events · git (OpenSpec + review.md)

 Human desktop (FS Developer): goose desktop ──ACP provider──► omp   (same branch, same tasks.md, same gateway)
                               or plain omp TUI
```

**Who is ACP client and who is ACP agent**

| Pair | Client | Agent | Transport |
|---|---|---|---|
| Build worker → omp | worker (Java/TS ACP client lib) | `omp --mode acp` in sandbox | stdio over container exec |
| Embabel/workflow → goose | executor activity | `goose serve` | ACP over HTTP/WS |
| goose desktop → omp | goose (ACP provider) | omp | stdio |
| Zed/JetBrains → omp or goose | editor | omp / goose | stdio |

Embabel agents are reached over MCP (or plain HTTP), not ACP; ACP is for driving a *session-based* agent, which the reasoning agents are not.

---

## 5. Scaling design

- **Queues per role**: `reasoning`, `build`, `executor`, `monitor`. Scale each independently. A burst of build tasks cannot starve story approvals.
- **Build capacity = sandbox slots**: each build worker registers `maxConcurrentActivities = slots`. Temporal backpressures automatically; no queue overflow logic to write.
- **Long activities with heartbeats**: the omp session activity heartbeats every iteration with `{iter, tokens, failingTests}`; on worker death Temporal retries on another worker from the last committed WIP branch, not from scratch.
- **Idempotency**: every board/repo write carries an idempotency key `item:rev:action`; adapters dedupe. Event ingress dedupes by `item+rev`.
- **Budgets in two places**: hard cap at LiteLLM per task key; soft cap in the workflow (stops the loop before the gateway refuses, so the escalation note is written cleanly).
- **Multi-squad**: one Temporal namespace per profile if isolation matters; otherwise one namespace, `profile` in search attributes.
- **Determinism**: workflow code contains no LLM calls and no clock reads outside Temporal APIs; all nondeterminism lives in activities. This is what makes replay/audit work.
- **Failure modes covered**: board down → activities retry with backoff, workflow unaffected; model provider down → gateway fallback chain; sandbox crash → activity retry; human never answers → workflow timer escalates at 5 days, never completes silently.

---

## 6. What each piece looks like in code (sketches)

**Embabel PO agent — conditions are the INVEST/DoR checks**

```kotlin
@Agent(description = "Turns an answered grill into an approved-ready story and spec delta")
class PoAgent(private val board: BoardPort, private val repo: RepoPort) {

  @Action fun draftStory(grill: GrillResult, ctx: OperationContext): StoryDraft =
    ctx.ai().withDefaultLlm().createObject("Write the story…", StoryDraft::class.java)

  @Action fun writeSpecDelta(draft: StoryDraft, current: DomainSpec): SpecDelta = /* ADDED/MODIFIED/REMOVED */
  @Action fun revise(draft: StoryDraft, comments: List<ReviewComment>, ctx: OperationContext): StoryDraft =
    ctx.ai().withDefaultLlm().createObject("Revise only the lines these comments target…", StoryDraft::class.java)

  @Condition fun invest(draft: StoryDraft) = draft.investReport().allPass()
  @Condition fun dorMet(draft: StoryDraft, delta: SpecDelta) = draft.grillCoverage() && delta.validates()

  @AchievesGoal(description = "Story ready for gate 1", pre = ["invest", "dorMet"])
  @Action fun publish(draft: StoryDraft, delta: SpecDelta): Handoff =
    Handoff(to = "plan-agent", state = "awaiting-G1", story = board.createStory(draft), change = repo.write(delta))
}
```
The planner will keep choosing `revise`/`writeSpecDelta` until `invest` and `dorMet` are true, then `publish`. Comments from checkers arrive as new world state and trigger a replan — no new prompt engineering.

**Temporal workflow — gates as signals, agents as activities**

```java
public class FeatureWorkflowImpl implements FeatureWorkflow {
  private int version = 1; private final Map<String, Approval> approvals = new HashMap<>();
  private final List<Comment> comments = new ArrayList<>();
  private final AgentActivities agents = Workflow.newActivityStub(AgentActivities.class, reasoningOpts);
  private final BuildActivities build = Workflow.newActivityStub(BuildActivities.class, longRunningWithHeartbeat);

  @Override public void run(WorkItemRef item) {
    var grill = agents.grill(item);                       // Embabel, queue=reasoning
    var story = agents.poDraft(item, grill);
    Workflow.await(() -> gate1Satisfied());               // waits days if needed
    var plan = agents.plan(item, story);
    for (var wave : plan.waves())
      Async.function(() -> wave.parallelStream().map(t -> Workflow.newChildWorkflowStub(TaskWorkflow.class).run(t)));
    Workflow.await(this::gate2Satisfied);
    var pack = agents.releasePrepare(item);
    Workflow.await(() -> pack.allSigned());
    build.deploy(item, pack);                              // CiPort
    agents.monitor(item, pack.rules());                    // long-running, files cards via BoardPort
  }
  @Override public void comment(Comment c) { comments.add(c); if (c.blocking()) approvals.clear(); }
  @Override public void approve(Approval a) { if (a.version()==version && sod.allows(a)) approvals.put(a.role(), a); }
  @Override public void requestChanges() { version++; approvals.clear(); agents.poRevise(item, openComments()); }
}
```

**Build worker — omp over ACP inside a sandbox**

```ts
// TaskWorkflow activity (TypeScript worker, queue=build)
export async function runOmpTask(task: Task): Promise<TaskResult> {
  const sb = await sandbox.start({ repo: task.repo, branch: task.branch, egress: ['litellm.internal'] });
  const acp = await AcpClient.connect(sb.exec(['omp', '--mode', 'acp', '--no-user-config']));
  const session = await acp.newSession({ cwd: '/work', mcpServers: [] });
  let iter = 0;
  for await (const update of acp.prompt(session, taskPrompt(task))) {      // pinned task block + scenarios
    if (update.kind === 'tool_call') enforceScope(update, task.touches);   // reject edits outside touches
    if (update.kind === 'turn_end') { iter++; heartbeat({ iter, tokens: gateway.usage(task.id) }); }
    if (iter >= task.budget.iters) { await acp.cancel(session); return escalate('budget', sb); }
  }
  const verdict = await ci.runVerify(task.branch);                          // read-only verifier
  return verdict.green ? openPr(task, sb) : escalate('red', sb, verdict);
}
```

---

## 7. What changes in the earlier documents

- `tech-stack-architecture.md`: agent runtime row becomes **Embabel (JVM)** with LangGraph as the non-JVM alternative; goose moves to "ACP executor + developer desktop (optional)"; build harness is **omp in ACP mode** (OpenHands no longer needed).
- `agent-playbook.md`: unchanged in substance — the typed handoff envelopes map 1:1 onto Embabel domain objects; INVEST/DoR/coverage checks become `@Condition`s.
- Control-plane language: Java/Kotlin (Spring) for the API and adapters; the build worker can be TypeScript to reuse omp's Node SDK and ACP libraries, or Java with an ACP client — both are fine because they only meet at Temporal task queues.

## 8. Decision record

| Decision | Choice | Rejected | Reason |
|---|---|---|---|
| Coding agent | omp (ACP mode, sandboxed) | OpenHands, goose | LSP/DAP/hashline; ACP + RPC + SDK surfaces; subagents in worktrees |
| Reasoning agent runtime | Embabel | goose recipes, LangGraph (if JVM) | typed contracts as conditions, deterministic replanning, Spring server |
| Orchestration server | Temporal | goose serve, per-agent pollers | durable multi-day gate waits, heartbeats for long omp runs, audit by history |
| goose | ACP executor + developer desktop, optional | goose as orchestrator | it is an agent, not a workflow engine |
| State watching | webhooks + one idempotent reconciler | scheduler per agent | races, N× API load, no gate semantics |
