# AI PDLC platform — technology stack and architecture

Scope: the control plane that runs the eight agents, the maker-checker surfaces, the release pack, and the adapters that make the board (Jira / Azure DevOps / GitHub Issues), the CI (Azure Pipelines / GitHub Actions / GitLab CI / Jenkins) and the repo host configurable.

Two honest notes up front. There is no single library called "maker-checker"; it is a pattern you compose from a durable workflow engine (holds the waiting state), an authorization layer (separation of duties), an append-only audit log, and a UI with line-level comments. And library versions move quickly — treat the versions and licences below as "as I know them", and re-check on the day you pin them.

---

## 1. Stack by layer

| Layer | Primary choice | Why | Alternatives |
|---|---|---|---|
| **Durable workflow (maker-checker state, gates, retries)** | **Temporal** (MIT) — one workflow per work item; approvals are *signals*, gates are `await condition(...)` that can wait days; every transition is in workflow history | Waiting for humans is the whole problem; Temporal makes "wait for two signals from two different people" a few lines and durable across restarts | Dapr Workflows (Apache), Conductor (Apache), Inngest (self-hosted), LangGraph checkpoints alone for small teams |
| **Reasoning agent runtime** | **Embabel** (Apache-2.0, JVM/Spring) for grill, PO, plan, review, release, monitor — typed domain objects, INVEST/DoR/coverage checks as `@Condition`s, deterministic GOAP replanning; each agent run is a Temporal activity | Handoff envelopes are domain objects; "revise only the commented lines" is a replan, not a new prompt; native Spring Boot server | LangGraph (MIT) if the team is not JVM. See `orchestration-decision.md` |
| **Coding agent (build loop)** | **omp** (MIT) in `--mode acp` inside a container per task, driven by the build worker as ACP client; same omp (TUI, or goose desktop with omp as ACP provider) for the FS Developer | LSP/DAP/hashline edits, subagents in worktrees, ACP + RPC + Node SDK surfaces; one agent for headless and human loops | OpenHands (MIT), OpenCode (MIT) |
| **ACP executor (optional)** | **goose** (`goose serve`, ACP over HTTP/WS) for bounded MCP/browser-heavy side tasks and as the developer desktop | Speaks ACP both ways; MCP breadth; not the orchestrator | drop if not needed |
| **Human approval channel SDK** | **HumanLayer** (Apache-2.0) or your own thin "approval request" service on Temporal signals | Gives Slack/email/web approval with the same request/response shape; keep it optional — Temporal signals are the source of truth | plain webhooks + board comments |
| **Model gateway** | **LiteLLM** (MIT) | Keys, routing per agent role, per-task budgets, fallbacks; agents never hold keys | OpenRouter (hosted), Portkey |
| **Tracing + evals** | **Langfuse** (MIT core) + **Promptfoo** (MIT) | Trace link on every work item; failures become eval cases | OpenTelemetry + Phoenix (Elastic licence) |
| **Spec layer** | **OpenSpec** (MIT) — change folders, delta specs, `review.md` lives next to them | Story text is versioned in git; the ADO/Jira story is a mirror | GitHub Spec Kit (MIT) |
| **Markdown parse / render** | **unified: remark-parse + remark-gfm + remark-frontmatter + rehype** (MIT); **react-markdown** (MIT) for the preview; **Shiki** (MIT) for code blocks; **Mermaid** (MIT) for diagrams in specs | remark exposes `node.position` (line/column) — that is what lets a comment anchor to a rendered line | markdown-it (MIT) if not React; marked (MIT) |
| **Markdown source editor with gutter comments** | **CodeMirror 6** (MIT) — `@codemirror/lang-markdown`, gutter markers, `Decoration` for commented ranges, read-only mode for checkers | Line-level anchoring, keyboard accessible, tiny | Monaco (MIT, heavier), Milkdown (MIT, WYSIWYG on ProseMirror) if checkers want rich editing |
| **Diff view (agent revisions, PR diff)** | **diff** (jsdiff, BSD) + **react-diff-viewer-continued** (MIT) or **diff2html** (MIT) | Show v1→v2 of a story or a release document inline | Monaco diff editor |
| **Real-time co-review (optional)** | **Yjs** (MIT) + **y-codemirror.next** | Two checkers commenting at once, presence cursors | Liveblocks (hosted) |
| **Web app** | **Vite + React + TypeScript** (MIT), **TanStack Query/Router** (MIT), **Radix UI** (MIT) | Small, no server-rendering needs; runs inside a Jira/ADO tab as an iframe app if wanted | Next.js (MIT) if you want SSR |
| **API / adapters host** | **NestJS** (MIT) or **FastAPI** (MIT) — pick the language of your agents (TypeScript if LangGraph.js, Python if LangGraph) | Adapters are plain classes behind ports (below) | Hono (MIT) |
| **Authorization / separation of duties** | **OpenFGA** (Apache) or **Casbin** (Apache) for "who can check what"; **OPA** (Apache) for release policy rules (canary %, required signatures) | Maker ≠ checker on the same stage is a policy, not UI logic | keep it in Postgres roles for tiny teams |
| **Data** | **PostgreSQL** (PostgreSQL licence) for events, comments, approvals, manifests; **Redis** (BSD/RSAL — check) for queues/cache; object storage for evidence attachments | Append-only `review_events` table + `review.md` in git: two audit trails that must agree | SQLite for a single-team pilot |
| **Sandboxes** | Docker per task; **gVisor** or Firecracker (Apache) if multi-tenant; egress allow-list | Build agent has no secrets and no network beyond the gateway | Kubernetes Jobs |
| **Identity** | OIDC (Entra ID / Okta / GitHub) | Approvals are tied to a real identity; signatures carry `sub` + timestamp + version hash | — |

Versions to pin on day one: Temporal SDK, LangGraph, CodeMirror 6, react-markdown 9+, remark 15+, Langfuse 3+, LiteLLM. Re-verify licences from each repo's LICENSE file.

---

## 2. Architecture

```
┌──────────────────────────────── People ─────────────────────────────────┐
│  PO · Squad Lead · FS Developer · QA  ─ browser / board tab / Slack      │
└──────────────┬──────────────────────────────────────────┬───────────────┘
               │ preview + comments + approvals            │ board UI (native)
┌──────────────▼──────────────────────┐        ┌──────────▼───────────────┐
│  Review UI (Vite + React)            │        │  Jira / ADO / GitHub      │
│  story preview · PR view ·           │        │  Issues (system of record │
│  release pack · review.md timeline   │        │  for STATE)               │
│  CodeMirror gutter comments,         │        └──────────▲───────────────┘
│  react-markdown, diff viewer         │                   │ REST / webhooks / MCP
└──────────────┬──────────────────────┘                   │
               │ GraphQL/REST                              │
┌──────────────▼───────────────────────────────────────────┴───────────────┐
│  Control plane API (NestJS or FastAPI)                                    │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐ │
│  │ Review service │ │ Approval svc  │ │ Release pack  │ │ Config svc    │ │
│  │ comments,      │ │ SoD check     │ │ manifest,     │ │ pdlc.yaml,    │ │
│  │ anchors,       │ │ (OpenFGA),    │ │ templates,    │ │ profiles,     │ │
│  │ review.md      │ │ signatures    │ │ signatures    │ │ secrets refs  │ │
│  └───────┬───────┘ └───────┬───────┘ └───────┬───────┘ └───────────────┘ │
│          │ events          │ signals         │                             │
│  ┌───────▼─────────────────▼─────────────────▼──────────────────────────┐ │
│  │ PORTS (interfaces)                                                    │ │
│  │ BoardPort · RepoPort · CiPort · NotifyPort · SecretsPort · MetricsPort│ │
│  └───┬───────────────┬───────────────┬──────────────┬───────────────────┘ │
│      │ ADAPTERS      │               │              │                      │
│  ┌───▼───┐ ┌───▼───┐ ┌───▼────┐ ┌────▼────┐ ┌───▼────┐ ┌────▼────┐          │
│  │ Jira  │ │  ADO  │ │ GitHub │ │ GitLab  │ │ Azure  │ │ GH      │ …        │
│  │ board │ │ board │ │ issues │ │ issues  │ │Pipelines│ │Actions │          │
│  └───────┘ └───────┘ └────────┘ └─────────┘ └────────┘ └─────────┘          │
└──────────────┬───────────────────────────────────────────────────────────┘
               │ start / signal / query
┌──────────────▼───────────────────────────────────────────────────────────┐
│  Temporal — one FeatureWorkflow per work item                             │
│   intake → grill(activity) → story(activity) → await G1 signals(2)        │
│   → plan → per-task child workflows (build loop) → await G2 → release     │
│   → await G3 (all docs signed) → deploy(CiPort) → monitor (long-running)  │
│  Activities call agent graphs; signals carry approvals/comments           │
└──────────────┬───────────────────────────────────────────────────────────┘
               │ activities
┌──────────────▼──────────────────┐   ┌─────────────────────────────────────┐
│  Embabel agent service (Spring)  │   │  Build sandboxes (containers)        │
│  grill · po · plan · review ·    │   │  omp --mode acp (ACP client=worker) │
│  release · monitor               │   │  no secrets · egress = gateway only  │
└──────────────┬──────────────────┘   └───────────────┬─────────────────────┘
               │                                       │
┌──────────────▼───────────────────────────────────────▼─────────────────────┐
│  LiteLLM gateway (keys, routing per role, budgets)  →  models (API / local) │
│  Langfuse (traces)  ·  Promptfoo (evals)  ·  Postgres (events)  ·  git      │
└────────────────────────────────────────────────────────────────────────────┘
```

**Principles.**
- The board is the system of record for *state*; git (OpenSpec change folder) is the system of record for *text*; Temporal history is the system of record for *what happened and when*. The control plane keeps the three consistent and refuses to act when they disagree.
- Hexagonal: agents and workflows talk only to ports. Swapping Jira for ADO is a config change plus an adapter, never an agent change.
- Approvals are Temporal signals carrying an identity and a version hash; the UI, Slack, and the board's native "approve" button all end in the same signal.

---

## 3. Maker-checker mechanics

### 3.1 Workflow (Temporal, TypeScript SDK; Python is equivalent)

```ts
export async function FeatureWorkflow(item: WorkItemRef) {
  const state = { version: 1, approvals: new Map<string, Approval>(), comments: [] as Comment[] };

  setHandler(commentSignal, (c) => { state.comments.push(c); if (c.blocking) state.approvals.clear(); });
  setHandler(approveSignal, (a) => {
    if (a.version !== state.version) return;                 // stale approval ignored
    if (!sodAllows(a.who, 'story', item)) return;            // maker ≠ checker, role check
    state.approvals.set(a.role, a);
  });
  setHandler(requestChangesSignal, async () => {
    state.version++; state.approvals.clear();
    await executeActivity('poAgentRevise', { item, comments: openComments(state) });   // maker re-runs
    await executeActivity('appendReviewMd', { item, event: 'revision', version: state.version });
  });

  await executeActivity('grillAgent', { item });
  await executeActivity('poAgentDraft', { item });

  await condition(() => state.approvals.has('PO') && state.approvals.has('SquadLead')
                        && openComments(state).length === 0);                          // gate 1
  await executeActivity('board.transition', { item, to: 'Approved' });
  // … plan → child build workflows → gate 2 → release pack → gate 3 → deploy → monitor
}
```

Why this is enough: the "waiting for two named humans on the same version with no open blocking comments" rule lives in one place, survives restarts, and its history *is* the audit log. Gate 3 uses the same shape with `condition(() => manifest.every(d => d.status === 'signed'))`.

### 3.2 Comment anchoring on markdown

- Parse the story with `remark-parse`; each node carries `position.start.line`. The preview renders through `react-markdown` with a custom component map that wraps every block in `<div data-line="N">`.
- The source view is CodeMirror 6 in read-only mode; a gutter marker per commented line; `Decoration.mark` highlights ranges.
- Anchor stored as `{ line, anchorText: hash(lineText), nodeType, scenario? }`. On a new version, re-anchor by `anchorText` first, then by scenario name, then fall back to the line and mark the comment `drifted` for the checker to confirm.
- Both views share one anchor model, so a comment added on the rendered preview shows in the gutter and vice versa.

### 3.3 Signatures and audit

- An approval = `{ who (OIDC sub), role, stage, version, contentHash, at }`. `contentHash` is the sha256 of the artifact at that version; the UI shows the hash so a checker can prove what they signed.
- Two trails that must agree: `review_events` (Postgres, append-only) and `review.md` (git, committed by the control plane's bot identity with the human's name in the message). A nightly job diffs them.
- Separation of duties enforced in OpenFGA/Casbin: relation `checker` on stage *S* excludes the identity that ran the maker for *S*; `SquadLead` can check story and release but not sign QA-owned documents.

### 3.4 Release pack

- Each document is a markdown file generated from a template (`docs/release/<id>.md`, mustache/handlebars sections) + a `manifest.yaml` entry. Templates for org forms (ASMR, AIG) are plain markdown with `{{field}}` slots; the release agent fills what it can and leaves `needs-human` markers the checker must resolve.
- The pack renders in the same preview component; a document-level "sign" is a signal with `doc_id`. All-signed → gate 3 becomes enabled.

---

## 4. Configurable providers

`pdlc.yaml` is the only place a provider is named. Profiles let one deployment serve several squads.

```yaml
profiles:
  payments-squad:
    board:
      provider: azure-devops           # jira | azure-devops | github-issues | gitlab-issues
      org: https://dev.azure.com/acme
      project: Payments
      area_path: Payments\\Orders
      types: { feature: Feature, story: "User Story", task: Task, bug: Bug, release: "Release" }
      states:                          # map canonical states → provider states
        new: New
        needs-clarification: "Needs Clarification"
        ready-for-story: "Ready for Story"
        awaiting-G1: "Awaiting Approval"
        approved: Approved
        planned: Planned
        in-progress: Active
        awaiting-G2: "In Review"
        awaiting-G3: "Awaiting Release"
        done: Closed
      auth: { kind: oidc-app, secret_ref: kv://ado-pat }
    repo:
      provider: github                 # github | azure-repos | gitlab
      url: https://github.com/acme/orders-service
      default_branch: main
      spec_dir: openspec
    ci:
      provider: github-actions         # azure-pipelines | github-actions | gitlab-ci | jenkins
      verify_workflow: verify.yml      # the read-only verifier job
      deploy_workflow: deploy.yml
      environments: [dev, staging, canary, prod]
    notify:
      provider: slack                  # slack | teams | email
      channel: "#payments-releases"
    agents:
      gateway: https://litellm.internal
      roles:                           # model routing per agent role
        grill:   { model: anthropic/claude-sonnet, budget_tokens: 60000 }
        po:      { model: anthropic/claude-sonnet, budget_tokens: 40000 }
        plan:    { model: anthropic/claude-sonnet, budget_tokens: 80000 }
        build:   { harness: omp, mode: acp, model: anthropic/claude-opus, budget_iters: 6, budget_tokens: 120000 }
        review:  { model: anthropic/claude-sonnet }
        release: { model: anthropic/claude-sonnet }
        monitor: { model: anthropic/claude-haiku, metrics: prometheus }
    gates:
      G1: { roles: [PO, SquadLead], sod: true }
      G2: { roles: [FSDeveloper, QA], sod: true, requires_verifier_green: true }
      G3: { roles: [SquadLead], requires_all_docs_signed: true }
    release_pack:
      documents: [change-notes, rollout-plan, monitor-rules, test-evidence, asmr, aig, risk-signoff, comms]
      templates_dir: docs/release
      checkers: { change-notes: PO, rollout-plan: SquadLead, monitor-rules: QA, test-evidence: QA,
                  asmr: SquadLead, aig: SquadLead, risk-signoff: PO, comms: PO }

  mobile-squad:
    board: { provider: jira, url: https://acme.atlassian.net, project: MOB,
             types: { feature: Epic, story: Story, task: Sub-task, bug: Bug, release: Release },
             states: { new: "To Do", awaiting-G1: "Awaiting Approval", approved: "Ready", in-progress: "In Progress",
                       awaiting-G2: "In Review", awaiting-G3: "Ready to Release", done: Done } }
    repo: { provider: azure-repos, url: https://dev.azure.com/acme/Mobile/_git/app }
    ci:   { provider: azure-pipelines, verify_pipeline: verify, deploy_pipeline: deploy }
    # everything else inherits defaults
```

### Ports (what every adapter must implement)

```ts
interface BoardPort {
  getItem(id): WorkItem; createItem(kind, fields, parent?): WorkItem; updateFields(id, fields): void;
  transition(id, canonicalState): void;                   // adapter maps canonical → provider state
  listComments(id): Comment[]; addComment(id, body, author?): CommentRef;
  link(id, otherId, relation): void; attach(id, file): AttachmentRef;
  onWebhook(event): CanonicalEvent;                       // item.created | item.updated | comment.added
  search(query): WorkItem[];                              // for duplicate detection
}
interface RepoPort {
  readFile(ref, path): string; writeFiles(branch, files, message, author): CommitRef;
  createBranch(from, name): void; openPR(branch, target, title, body): PRRef; commentOnPR(pr, body, line?): void;
  getDiff(pr): Diff; getPRStatus(pr): PRStatus; onWebhook(event): CanonicalEvent;
}
interface CiPort {
  runVerify(branch|pr): RunRef; runDeploy(env, releaseId, params): RunRef;
  getRun(ref): RunStatus; getArtifacts(ref): ArtifactRef[]; onWebhook(event): CanonicalEvent;
}
interface NotifyPort { requestApproval(to, payload): RequestRef; post(channel, message): void; }
interface SecretsPort { resolve(ref): string; }                      // kv:// refs only; agents never call this
interface MetricsPort { query(signal, window): Series; }             // monitor agent
```

Adapter notes:
- **Azure DevOps**: REST (`_apis/wit/workitems`, `_apis/git/pullrequests`, `_apis/pipelines`) or the ADO MCP server; service hooks for webhooks. State transitions need the process's exact state names — hence the map.
- **Jira**: REST v3 + Atlassian MCP; transitions are by transition id, so the adapter caches the workflow. Jira "approvals" (if using JSM) can be mirrored, but the Temporal signal remains the truth.
- **GitHub Issues**: issue types via labels/Projects fields; PR review "Approve" can be mapped to a G2 signal via webhook.
- **CI**: the verifier is always a CI job the agents cannot edit (branch protection / `CODEOWNERS` on `.github/workflows` or the pipeline YAML). `runDeploy` is only ever called by the workflow after gate 3.

---

## 5. Data model (Postgres)

```
work_items(id, profile, board_provider, board_id, kind, parent_id, canonical_state, spec_change_path)
artifacts(id, work_item_id, kind[story|task-list|release-doc|pr], version, content_hash, git_ref, created_by)
comments(id, artifact_id, version, author_sub, role, anchor_json, text, intent, blocking, resolved_in_version, agent_reply)
approvals(id, artifact_id, version, content_hash, author_sub, role, stage, at)      -- append-only
review_events(id, work_item_id, ts, kind, payload_json)                              -- append-only; mirrors review.md
release_packs(id, release_id, work_item_id, pack_version, manifest_json)
runs(id, work_item_id, agent, workflow_run_id, trace_url, tokens, iterations, outcome)
```

---

## 6. Deployment shape

- Four deployables: `control-plane` (Spring API + adapters + UI static files), `embabel-agents` (Temporal workers hosting the reasoning agents), `build-workers` (own sandbox slots; run omp over ACP), optional `goose-executor` pool. Plus Temporal, Postgres, LiteLLM, Langfuse.
- Scale out agent workers per queue (`grill`, `story`, `plan`, `build`, `review`, `release`, `monitor`) so a burst of build tasks does not starve approvals.
- Cost control at the gateway: per-profile and per-task budgets from `pdlc.yaml`; the workflow reads the remaining budget before each activity.
- Pilot size: one squad, one profile, Temporal + Postgres in Docker Compose, board = whatever they already use.

## 7. Build order (suggested)

1. Ports + ADO and Jira board adapters + GitHub repo adapter; canonical state map; webhook ingestion.
2. Temporal `FeatureWorkflow` with gate 1 only; PO agent; story preview with CodeMirror gutter comments and `review.md`.
3. Grill agent; plan agent; build loop as child workflows with OpenHands; verifier as CI job; gate 2.
4. Release pack + manifest + document signing; gate 3; deploy through `CiPort`.
5. Monitor agent + `MetricsPort`; cards filed back through `BoardPort`.
6. Second CI adapter (Azure Pipelines / GitLab), Slack approvals through `NotifyPort`, OpenFGA policies.
