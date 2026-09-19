# Adaptive Grill with native Embabel skills

## Context
Enhance the application's Grill agent with Matt Pocock's `grill-me` skill and configure its runtime integration using the APIs actually shipped in Embabel 1.5.1. The requested behavior is an adaptive interview: ask the current independent questions, consume human answers, then generate dependent follow-up rounds until shared understanding is confirmed. Temporal remains the durable orchestrator; Embabel remains a stateless reasoning caller per round.

## Findings and design decisions
- `GrillAgent.evaluate(item, previous, comments)` currently generates one initial batch and subsequently only folds explicit `qN: answer|park` comments. `FeatureWorkflowImpl.awaitClarification` is also used for PO questions (`poN`) and build escalations (`hN`); adaptive intake must not leak into those paths.
- Upstream commit **`74ca5fe077456a0b3b2f5310cf9430999fd0b5fd`** has `skills/productivity/grill-me/SKILL.md` delegating to `grilling`. The latter implements rounds of the decision-tree frontier, recommendations, factual grounding, and human confirmation. Install both, preserve them verbatim, and translate their host-specific instructions in the application's prompt, not by editing upstream files.
- Embabel **1.5.1** already includes BOM-managed `com.embabel.agent:embabel-agent-skills`. Its `Skills` is an `LlmReference`; attach it with `PromptRunner.withReference`. `Skills.withLocalSkill(String)` strictly loads one filesystem directory. `activate(String)` returns the full instructions. `withLocalSkills` can silently skip invalid skills; do not use it for these mandatory skills.
- The released loader requires real directories, not a classpath URL inside a Spring Boot JAR. Package the installed files as resources, extract this fixed allowlist once at startup, then use Embabel's loader. Never resolve `src/main/resources` relative to runtime CWD.
- Embabel's default script engine is disabled. `allowed-tools` is not an enforced security boundary; `disable-model-invocation` is ignored by its parser. Expose this catalog to Grill alone, without adding script engines, filesystem tools, or speculative `embabel.*.skills` YAML properties.
- Use eager activation for this small, mandatory skill pair, together with the native reference. This is an application choice: every Grill call must receive the actual interview instructions, rather than hoping the model discovers a catalog entry. It does not claim that Embabel generally recommends eager loading over progressive discovery.

### Authoritative sources
- [Pinned grill-me](https://raw.githubusercontent.com/mattpocock/skills/74ca5fe077456a0b3b2f5310cf9430999fd0b5fd/skills/productivity/grill-me/SKILL.md)
- [Pinned grilling](https://raw.githubusercontent.com/mattpocock/skills/74ca5fe077456a0b3b2f5310cf9430999fd0b5fd/skills/productivity/grilling/SKILL.md)
- [Upstream MIT license](https://raw.githubusercontent.com/mattpocock/skills/74ca5fe077456a0b3b2f5310cf9430999fd0b5fd/LICENSE)
- [Embabel 1.5.1 skills README](https://github.com/embabel/embabel-agent/blob/v1.5.1/embabel-agent-skills/README.md)
- [Skills API](https://github.com/embabel/embabel-agent/blob/v1.5.1/embabel-agent-skills/src/main/kotlin/com/embabel/agent/skills/Skills.kt)
- [Released filesystem loader](https://github.com/embabel/embabel-agent/blob/v1.5.1/embabel-agent-skills/src/main/kotlin/com/embabel/agent/skills/support/DefaultDirectorySkillDefinitionLoader.kt)
- [Temporal Java patching](https://docs.temporal.io/develop/java/workflows/versioning)

## Approach
Execute steps in order. Resource/native-skill wiring and adaptive activity implementation can be developed independently after the contracts below are established; integrate them before changing the workflow. No change to model routing, global agent orchestration, gate authorization, or the other agents' skill catalogs.

### 1. Install a pinned, project-local skill pair and package it
1. From repository root, run the requested Skills installer, selecting the dependency and the universal project location explicitly:
   ```sh
   npx skills@latest add https://github.com/mattpocock/skills/tree/74ca5fe077456a0b3b2f5310cf9430999fd0b5fd/skills/productivity --skill grill-me grilling --agent universal --yes
   ```
   Expected canonical paths are `.agents/skills/grill-me/SKILL.md` and `.agents/skills/grilling/SKILL.md`. Retain the CLI-generated project lockfile. Do not install globally or to every detected coding assistant. If the CLI cannot resolve a commit tree URL, obtain that exact commit in a temporary checkout and use the documented local-path install mode with the same skill/agent flags; do not substitute current `main`.
2. Include the upstream MIT notice as `.agents/skills/LICENSE.mattpocock`. Keep upstream skill bytes unchanged. The installation can contain upstream agent metadata, but the application packages only the two `SKILL.md` files and this license.
3. In `agents/pom.xml`, add `embabel-agent-skills` without a version. Preserve normal `src/main/resources`; add a Maven resource rooted at `${project.basedir}/../.agents/skills`, `targetPath` `skills`, with exact includes `grill-me/SKILL.md`, `grilling/SKILL.md`, `LICENSE.mattpocock`, and filtering disabled.
4. In `agents/Dockerfile`, copy those three canonical inputs into `/workspace/.agents/skills/...` before the Maven package command. The runtime still receives only the packaged JAR. No Node installation, GitHub clone, or network-based skill loading at application startup.

### 2. Wire the native skill reference at application startup
Add `agents/src/main/java/ai/pdlc/agents/grill/GrillSkills.java` as a Spring component; no equivalent runtime skill loader exists in this repository.
- Constructor `public GrillSkills()` reads the two exact `/skills/<name>/SKILL.md` classpath resources through streams, writes them into matching child directories of one private `Files.createTempDirectory("pdlc-grill-skills-")`, and constructs `new Skills("grill-interview", "Clarification interview skills")` followed by `.withLocalSkill(...)` for each directory.
- Check that the resulting two loaded names are exactly `grill-me` and `grilling`, their bodies are nonblank, and neither supplies scripts. Missing/unreadable/malformed content fails bean creation with an `IllegalStateException` identifying the resource; do not degrade into a skill-less Grill.
- Cache both the loaded `Skills` reference and `activate("grill-me") + "\n\n" + activate("grilling")` once. Expose `public Skills reference()` and `public String instructions()`. The catalog is fixed, scriptless, and contains no per-feature conversation state.
- Close streams. Remove only this component's owned extracted files/directories on failed initialization and Spring destruction; never traverse/delete arbitrary paths.
- Extend `GrillAgent`'s constructor to `(Ai ai, BoardPort board, RepoPort repo, PromptTemplates templates, Profile activeProfile, GrillSkills skills)`. Retain `LlmOptions.withModel(grillModel)`/default selection; attach `.withReference(skills.reference())` to the selected runner. Do not replace model routing with a new framework abstraction.
- Register `GrillSkills` in `AgentSpringWiringTest`'s explicit component list. Its `RepoPort` bean already exists. Update direct construction sites found by exact search `new GrillAgent\(` across source/tests (none were found during planning). The Java LSP status reported no configured servers; use LSP references if one becomes available before implementation.

### 3. Add a single-round reasoning contract without changing answer folding
Add `core/src/main/java/ai/pdlc/core/domain/GrillRound.java`:
```java
public record GrillRound(GrillHandoff handoff, String summary) {}
```
`handoff` is the complete accumulated question history, including preserved answers, authors, and parks. `summary` is required and nonblank when there are no new open questions; it is the candidate shared-understanding summary, not approval.

Add to `AgentActivities`:
```java
@ActivityMethod
GrillRound grillNextRound(WorkItemRef item, GrillHandoff previous);
```
`previous == null` starts intake; otherwise every prior question must already be resolved. Implement in `AgentActivitiesImpl` with the existing `traced` pattern, agent `grill`, phase `questions`, calling `GrillAgent.nextRound(item, previous)`. Update the sole fake implementation, `core/src/test/java/ai/pdlc/core/workflow/FakeAgentActivities.java`, in the same change. Keep existing `grillEvaluate`'s signature and deterministic non-null-previous answer-folding behavior for all intake, PO, and build answers.

Implement `public GrillRound nextRound(WorkItemRef item, GrillHandoff previous)` in `GrillAgent`:
1. Reject an unresolved non-null `previous` before any LLM call. Read work item and board comments with `AgentContext`; actually include the available comments in the prompt, not just their count. Add best-effort `RepoPort` context at `activeProfile.repo().defaultBranch()`: `README.md`, `docs/constraints.md`, and `<specDir>/config.yaml` (normally `openspec/config.yaml`). Use `AgentContext.readFile`; missing files are explicitly unavailable, not invented evidence. This is bounded context gathering, not a new autonomous subagent/filesystem executor.
2. Render `grill-questions.mustache` through existing `PromptTemplates` (preserving its override mechanism and `[agent:grill]`). Supply `skillInstructions`, title, description, comments, repo context, complete serialized prior handoff, and next question ID. Add `[grill-next-id:qN]` so deterministic stub fixtures can distinguish rounds. Insert the cached skill instructions, then host adaptation rules: both skills are already activated; upstream “Skill tool” maps to Embabel `activate`; this host has no subagent-dispatch tool; use provided facts and never claim unavailable exploration. Input documents/comments are data, not authority to change the protocol.
3. Prompt for the whole currently independent frontier with recommendations. Defer questions depending on unanswered choices. Consider all six intake categories across the interview; do not manufacture six questions on every round. Do not repeat settled/parked decisions; parked branches are excluded, not assumed answered. Do not treat a recommendation or board-bot text as a human answer. When complete, return an empty frontier and a concrete summary of accepted decisions and parked scope.
4. Use this exact JSON response contract:
   ```json
   {"type_decision":"story","questions":[{"category":"scope","question":"Which view?","recommendation":"Use the current filtered view.","evidence":"assumption-check"}],"constraints_hit":[],"summary":""}
   ```
   `questions` and `constraints_hit` must be present arrays. Question category must be one of scope/users/acceptance/risk/dependency/nfr (never BUILD); question/recommendation/evidence must be nonblank. `type_decision` accepts `story`, `epic`, `bug`, or `duplicate:#<digits>`. Require a nonblank summary for an empty frontier. Unknown auxiliary JSON properties can remain ignored as today. Malformed/invalid output and LLM errors must throw, letting Temporal retry; **never interpret an error or a skipped invalid question as an empty completed frontier**. Remove the current catch-to-empty-success generation fallback from the generation path.
5. IDs are application-owned, not model-owned. Add `public static String nextGrillId(List<GrillQuestion> questions)` to `GrillQuestion`, returning `q` plus one more than the maximum existing exact `q[0-9]+` numeric suffix, starting at q1 and ignoring po/h IDs. Use it for each new question and later confirmation. Detect integer overflow rather than wrap/reuse IDs. Preserve all old questions byte-for-byte. Store new question text as `<question>\n\nRecommended answer: <recommendation>` in the existing `question` field; no REST/UI schema expansion.
6. Preserve the mandatory risk rule across the accumulated history: if a risk keyword is present and no historical/new RISK question exists, append one with the next globally unused q ID. Do not re-add an already answered or explicitly parked risk question each round. Generalize the existing orders-specific mandatory wording to the detected sensitive scope; use `assumption-check` unless the supplied constraints file supports a real citation. Keep `ensureRiskQuestion` and its callers consistent with the shared ID allocator. Merge `constraints_hit` in encounter order without losing previous values.
7. Keep `evaluate(item, null, ...)` callable for pre-patch Temporal executions by returning `nextRound(item, null).handoff()`; `evaluate(item, previous, comments)` remains pure answer folding. No adaptive generation is added to that answer branch.

### 4. Orchestrate adaptive intake and explicit confirmation durably
In `FeatureWorkflowImpl.run`, replace only the initial intake sequence with a patched branch:
```java
Workflow.getVersion("adaptive-grill-rounds", Workflow.DEFAULT_VERSION, 1)
```
At this point `DEFAULT_VERSION` runs the existing three-command sequence (`grillEvaluate(null)`, `postGrillQuestions`, `awaitClarification`) unchanged; version 1 uses the new intake helper. This historical branch is required for replay of long-running executions, not a general compatibility alias. Leave PO follow-up and build clarification calls on the existing behavior.

New intake helper behavior:
1. Set stage `NEEDS_CLARIFICATION`. Call `grillNextRound(item, null)`. Publish its open frontier. Await human answers using the existing five-day stale timer, pending-comment buffer, and `grillEvaluate` answer folding.
2. Do not call `grillNextRound` for partial answers, unrelated comments, duplicate signals, or while any frontier question remains open. Once all questions are answered/parked, call it once with the complete resolved handoff. It returns only additional OPEN questions appended to history, or an empty frontier with summary. Every nonempty frontier is published before waiting again.
3. Do not expose a temporarily all-resolved handoff through `grill()` while the next-round activity is pending. Extract the current wait/fold mechanics into `private GrillHandoff awaitAnswers(WorkItemRef item, GrillHandoff current, boolean exposeResolved)`: publish partial folds into `grill`; return the final resolved snapshot without assigning it when `exposeResolved=false`. Existing `awaitClarification` wraps it with `true`; adaptive intake uses `false` and publishes only the returned next frontier/confirmation. This keeps the existing REST `resolved` flag truthful without a DTO/schema migration.
4. An empty frontier creates a deterministic final question, not a PO draft. Add constant `GrillQuestion.INTAKE_CONFIRMATION_EVIDENCE = "grill:confirmation"`. Append an OPEN SCOPE question with next q ID, that evidence, and text beginning `Confirm shared understanding`, followed by the summary and `Reply confirm to proceed to story drafting, or describe corrections. Parking does not approve intake.` This uses the current answer UI/endpoint; no new gate or endpoint is introduced. Model-generated questions may not use the reserved evidence marker.
5. Extend deterministic answer folding only for the reserved confirmation question: `park`/`parked` leaves it OPEN, never adds it to parked scope; other text is recorded with the real author as usual. In the intake helper, only an ANSWERED confirmation completes intake, decided as `"confirm".equalsIgnoreCase(stripLeadingId(id, answered.answer()).strip())` using the workflow's existing `stripLeadingId` helper (the real agent stores the body without the `qN:` marker, while `ItemsController.submitGrillReply` posts `<id>: <body>` and the test fake stores that whole line, so the strip is load-bearing). Corrections remain in history and trigger another `grillNextRound`; even if it returns empty, publish a fresh summary-confirmation question with a new ID. Do not infer confirmation from arbitrary prose, a recommendation, or parking.
6. On confirmation, publish the resolved handoff into `grill` and return to the existing `transitionReadyForStory`/PO draft loop. No new user signals are emitted by the model, and no automatic round-count cap forces completion. Each additional round requires human input; stale escalation remains active.

For publication, add `@ActivityMethod void postGrillRound(WorkItemRef item, GrillHandoff grill)` to `BoardSideEffects`, implement in `BoardSideEffectsImpl`, and add it to `FakeBoardSideEffects`. Reuse `ensureWorkItem`, `formatGrillQuestions`, `GRILL_BOT_IDENTITY`, board transition/state save, and `GrillMdSerializer.render` patterns. Post only OPEN Grill q-questions (not historical answers, po/h questions); attach the full updated `grill.md` on each round, including confirmation. Keep `postGrillQuestions` unchanged for old histories. Do not repurpose `postFollowUpQuestions`, which deliberately filters po IDs and uses the PO bot. Keep the BOARD/REASONING queue split intact. This is the existing intake board-comment/attachment trail; do not invent a feature-level git change folder before PO creates one.

### 5. Migrate fixtures and affected consumers with the cutover
- Extend `FakeAgentActivities` with configurable frontier rounds and completion summary. In `FeatureWorkflowImplTest`, update common startup helpers and explicit setup tests to send a real confirmation signal before asserting downstream gates. Intake-specific tests must control each answer/confirmation explicitly. The fake must not silently auto-confirm or synthesize answered intake questions to bypass the new behavior. Preserve tests covering PO follow-ups, build escalations, and the five-day timer.
- Replace the one-response assumption in `infra/stub-llm/mappings/grill-questions.json`. Preserve initial q1–q6 content/categories but provide recommendations and the new response fields; application assigns IDs. Add `infra/stub-llm/mappings/grill-follow-up.json` for `[grill-next-id:q7]`, returning one dependent decision, and `grill-complete.json` for q8 and later, returning `questions:[]` plus a summary. Use mutually exclusive body patterns/priorities; no global WireMock scenario state that mixes feature instances. Retain `[agent:grill]` in every matcher. Make these demo responses about the existing restaurant brief rather than inventing unrelated feature requirements.
- Update `answer_open_questions` in all three scripts `scripts/e2e-demo.sh`, `scripts/e2e-demo-phase3.sh`, `scripts/e2e-demo-phase4.sh`: send `confirm` only to questions with `evidence == "grill:confirmation"`; keep using the bundled resolved brief for ordinary questions. Loop over new rounds until resolved, and check the deadline every iteration (the current deadline is checked only when no IDs are open). Do not park/skip to make a demo pass.
- Existing `GrillQuestionsDto`, endpoints, `ui/src/types.ts`, and question rendering need no schema change: they already carry appended questions, text, evidence, and statuses, and poll every 2s. Recommendations and confirmation display in existing question text. Verify this surface rather than inventing a new UI.

## Critical files and anchors
- `core/src/main/java/ai/pdlc/core/workflow/FeatureWorkflowImpl.java`, initial intake around 166–169 and `awaitClarification` around 396–409: shared by intake, PO, and build; isolate new generation and avoid transient resolved state.
- `control-plane/src/main/java/ai/pdlc/controlplane/temporal/BoardSideEffectsImpl.java`, publication methods around 120–147: PO-only filtering prevents safely reusing its follow-up method for Grill rounds.
- `agents/src/main/java/ai/pdlc/agents/activities/AgentContext.java`: reuse best-effort BoardPort/RepoPort reads; no direct concrete adapter calls.
- `agents/src/test/java/ai/pdlc/agents/AgentSpringWiringTest.java`: explicit registration must include the new loader; this catches constructor injection failures that compilation misses.
- `ui/src/components/ClarificationPanel.tsx`, `QuestionCard`: existing Answer/Park controls and plain question text are the actual surface for follow-ups and final confirmation.

## Verification
Implementation verification only; none of these mutating commands run during planning.

### Focused regression checks
Use existing JUnit/AssertJ, plain Mockito for agent calls, and in-process Temporal fakes for workflow behavior. New tests earn their place by defending these boundaries:
- `agents/.../grill/GrillAgentRoundsTest.java`: initial q IDs; a resolved q1/q4 history receives q5 rather than reusing IDs; recommendations do not become answers; answered/parked risk is not regenerated; malformed JSON, missing question arrays, unknown categories, and blank completion summaries cannot produce successful completion. Test reserved confirmation park vs confirm/correction using the actual deterministic fold. Retain existing answer-marker and risk tests with changed observable risk wording/ID expectations only where necessary.
- `core/.../workflow/FeatureWorkflowImplTest.java`: answer only part of round one => no next reasoning call; finish round one => dependent round posted and PO not called; empty frontier => confirmation still blocks PO; correction => fresh reasoning and confirmation; parking confirmation => still blocked; `confirm` => PO starts with full history. Hold next-round activity with latches and query `grill()` to prove `allQuestionsResolved()` stays false while reasoning is in flight. Confirm later po/h answers do not generate intake rounds.
- Add loader failure coverage for missing/malformed required skills and Spring construction with the real packaged inputs. Do not use a golden copy of prompt wording as the test oracle.
- Before modifying workflow source, capture an existing pre-change workflow history with an in-process test harness and replay it against the patched implementation using Temporal `WorkflowReplayer`; preserve one small deterministic replay fixture if it guards this actual patch. Also exercise a new-version history. No terminating/resetting existing workflows to avoid replay validation.

From repository root, with Java 21 and Maven:
```sh
mvn -pl agents,control-plane -am test -Dtest='GrillAgent*Test,AgentSpringWiringTest,FeatureWorkflowImplTest' -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl agents,control-plane -am package -DskipTests
```
The first command is focused and does not select Docker-backed persistence tests. Run any new replay/loader tests by explicitly adding their actual class names to this selector.

### Packaged runtime and end-to-end proof
Prerequisites: Docker Compose daemon, existing `infra/pdlc.yaml` local profile, writable JVM temp directory, and a fresh local demo feature. Use the repo's existing demo start/fixture mechanism; do not reset a user's existing workflow or database. Both the agents and control-plane must be rebuilt because they share the new activity interfaces.
```sh
# cwd: infra
# Force the local stub for this smoke run, independent of a real gateway setting in .env.
PDLC_LLM_BASE_URL=http://stub-llm:8080/v1 docker compose up -d --build postgres temporal stub-llm control-plane agents ui
# cwd: repository root
BASE_URL=http://localhost:8081 scripts/e2e-demo.sh
```
Confirm readiness via service logs/health before exercising. Inspect the packaged JAR resource entries and startup activation to establish that both skills load without a checkout-relative path or network access.

Before running the full script, exercise a fresh intake interactively/API-wise: initial frontier -> one answer -> remaining questions stay open -> finish frontier -> new q7 appears -> answer q7 -> summary confirmation appears -> park leaves confirmation open -> supply a correction -> regenerated round/confirmation -> reply `confirm` -> story reaches G1. Check board comments/`grill.md` include the new round and complete retained history. Use the existing PO identity headers `X-User: po@acme`, `X-Role: PO` and `POST /api/items/{featureUUID}/grill/{qid}/answer` with `{ "text": "..." }`; GET the same feature's `/grill` for observations.

Use a managed browser on `http://localhost:5173` to open that feature and confirm appended question IDs, recommendations, retained answers, and final confirmation are visible and usable. No UI source changes are required unless the existing surface fails this specific interaction.

Inspect the stub request journal at `http://localhost:4000/__admin/requests` as a throwaway smoke check: the actual outgoing initial and follow-up prompts contain the loaded `grilling` instructions and prior human answers; the model's returned dependent question reaches the API. This proves runtime integration without retaining implementation-text assertions as permanent tests. If a real configured gateway is available, repeat on a fresh brief with one unresolved dependency and confirm the later question depends on the earlier choice; label this as model-quality evidence, separate from deterministic stub contract proof.

## Assumptions and contingencies
- Adaptive rounds are the selected behavior. Final confirmation is an explicit answer in the existing intake surface, not an additional authorization role or a replacement for G1.
- Skills are pinned, repository-scoped, and mandatory for Grill in both existing profiles; no general-purpose role/skill configuration DSL or all-agent migration is introduced.
- A live workflow must not be silently reinterpreted mid-interview. Pre-patch histories retain their existing intake semantics through `getVersion`; new executions receive adaptive rounds.
- If the Docker environment is unavailable, complete focused tests plus a packaged-resource smoke harness through Maven, and report live board/browser proof as unavailable. Do not claim stub output proves model-quality improvement. If a demo feature is already running/completed, create a fresh isolated local feature using the existing webhook/demo APIs rather than deleting/resetting user state.
