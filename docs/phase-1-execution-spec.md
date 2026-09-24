# Phase 1 execution spec: Secure Agent Studio

This spec turns Phase 1 of [configurable-agent-platform.md](configurable-agent-platform.md) into
slices that can be built one at a time. Each slice ships something usable end to end and has its
own exit evidence. **Slice 1 is implemented on this branch**; slices 2–6 are specified here and are
not yet built.

**Phase 1 exit evidence** (from the roadmap):

- An admin publishes a new agent without a code change or a restart.
- An existing run stays pinned after the agent is revised.
- Another workspace cannot discover, read or run the agent.

## Decisions for this phase

| Topic | Decision |
|---|---|
| Identity provider | No enterprise IdP has been named. Slice 2 adds generic Spring Security OIDC configured only by `issuer-uri` + client registration. The `X-User`/`X-Role` header shim survives only under an explicit dev flag that is off by default. |
| Enterprise admin | The existing `Admin` role on the resolved identity. It can create workspaces and list all their names. It is **not** a workspace membership, so it cannot read a workspace's content without a grant. |
| Capabilities | Stored per workspace member: `WORKSPACE_ADMIN`, `AUTHOR`, `OPERATOR`, `REVIEWER`, `CURATOR`. They are separate from the PDLC gate roles (`PO`, `SquadLead`, …), which stay in project gate config. |
| Discovery | A non-member gets **404** for a workspace and for everything in it, the same response as for a missing workspace. A member without the needed capability gets **403**. |
| Storage | Spring JDBC (`JdbcTemplate`) stores behind small interfaces, following `ProjectService`. Composite keys make Spring Data JDBC entities awkward here. JSON is stored as `TEXT`, as elsewhere in the schema. |
| Content identity | `sha256:` over canonical JSON (properties and map keys sorted), computed in `core` so the agents runtime can re-verify it. |
| Module boundaries | The slices follow the modular-monolith rules in [Scalability and service boundaries](configurable-agent-platform.md#scalability-and-service-boundaries). Slice 1 lives under `platform/`; slice 2's login and service identities stay in the `identity/` module. Other modules depend only on `IdentityResolver` and `WorkspaceService`, never on security internals. |
| JSON libraries | HTTP DTOs bind with Spring Boot 4's Jackson 3. Stored/hashed content uses Jackson 2 in `core` (same split as `PdlcConfigBeans`). DTOs therefore use typed records and `Map<String,Object>`, never Jackson 2 `JsonNode`. |

## Slice 1 — Workspaces and a versioned agent registry (implemented)

**Schema.** `V15__workspaces_agent_registry.sql` adds four tables:

- `workspaces`
- `workspace_members`: one row per (workspace, user, capability)
- `agent_definitions`: the mutable draft, with `draft_revision`, `status` and `current_version`
- `agent_definition_versions`: insert-only, keyed by (workspace, agent, version), and carrying `content_hash`

**Domain (`core/.../platform`):**

- `AgentSpec`: description, `native` runtime, Mustache prompt, declared variables, model binding with explicit fallbacks, limits (`timeoutSeconds` required, `maxOutputTokens` optional), optional output schema.
- `AgentSpecValidator`: pure publication checks.
  - Model and every fallback must be in the authorized catalog; there is no silent default.
  - Rejects Mustache partials and delimiter changes.
  - Every top-level variable referenced outside a section must be declared, and sections must balance.
  - Variable names must be well-formed and unique; limits must be in bounds.
- `ContentHash`: canonical JSON, the hash, and reading stored content back.

**Service (`control-plane/.../platform`):**

- `WorkspaceService`:
  - Creates workspaces (enterprise `Admin` only, at least one initial `WORKSPACE_ADMIN`).
  - Manages membership; the last workspace admin cannot be removed.
  - Owns `requireMember`/`require`, the single access check every workspace resource goes through.
- `AgentRegistryService`: the lifecycle, draft → validated → published → retired.
  - A save or publish names the draft revision it was based on; a stale revision returns 409.
  - Publish appends version N+1 and makes it current. Republishing an unchanged draft is rejected.
  - Rollback moves `current_version` back without creating a version.
  - Retire blocks edits and new runs but keeps every version readable.
  - `resolveForRun` returns the current version for an `OPERATOR`, and re-hashes the stored content so a tampered row fails instead of running.
- `ModelCatalog`: in this slice, the `agents.roles.*.model` values from `pdlc.yaml` (the models the agents service already routes to).

**REST.** All endpoints require an identity:

| Method and path | Who |
|---|---|
| `GET/POST /api/workspaces` | member list / enterprise Admin |
| `GET /api/workspaces/{ws}` | member |
| `GET /api/workspaces/{ws}/members` | member |
| `PUT /api/workspaces/{ws}/members/{user}` `{capabilities}` (empty removes) | WORKSPACE_ADMIN |
| `GET/POST /api/workspaces/{ws}/agents` | member / AUTHOR, WORKSPACE_ADMIN |
| `GET /api/workspaces/{ws}/agents/{id}` | member |
| `PUT .../agents/{id}/draft` `{name, spec, revision}` | AUTHOR, WORKSPACE_ADMIN |
| `POST .../agents/{id}/validate` | AUTHOR, WORKSPACE_ADMIN |
| `POST .../agents/{id}/publish` `{revision}` | WORKSPACE_ADMIN |
| `POST .../agents/{id}/rollback` `{version}` | WORKSPACE_ADMIN |
| `POST .../agents/{id}/retire` | WORKSPACE_ADMIN |
| `GET .../agents/{id}/versions[/{n}]` | member |
| `GET .../agents/{id}/resolved` | OPERATOR |

**Exit evidence:**

| Test | What it proves |
|---|---|
| `AgentSpecValidatorTest`, `ContentHashTest` (core) | Validation rules; hashes are stable and survive a round trip |
| `WorkspaceServiceTest`, `AgentRegistryServiceTest` (in-memory stores) | The ALPHA/BETA pinning scenario, cross-workspace 404s, capability gates, stale-revision 409s, the invalid-model block, rollback, retirement |
| `PlatformRegistryIntegrationTest` (Testcontainers, real Flyway) | The same lifecycle on real Postgres, plus tamper detection |

- `PlatformRegistryIntegrationTest` needs Docker. Add it to the Docker-unavailable exclusion list.
- A manual HTTP smoke test covered all of the above against a live control-plane.

**Deliberately not in slice 1:** OIDC, connections, a DB-backed model catalog, runs, UI, and skills. Draft `spec` accepts only the fields above, so tools and knowledge can't be smuggled in before their governance exists.

## Slice 2 — Enterprise sessions and service identities

- **Browser login:** add `spring-boot-starter-security` + `oauth2-client`, with an OIDC login using a backend-managed session, HttpOnly/SameSite cookies and CSRF tokens for mutating requests. Identity = issuer + subject; display name and email come from claims.
- **One resolution point:** `IdentityResolver` stays the only place identity is resolved, so controllers don't change. It reads the Spring Security principal.
- **Header shim:** honoured only when `pdlc.identity.dev-headers=true`, which is off by default. Setting it outside the `local` profile logs a startup warning.
- **Service identities:** build-worker and agents authenticate with client-credentials JWTs (or the existing `BUILD_AGENT_TOKEN`, as a dev fallback only). Service identities can never create a browser session.
- **UI:** the existing `setIdentity` dev switch is shown only when the backend reports dev-headers mode. Otherwise the user logs in and out through the backend.
- **e2e scripts:** migrate them to a token-based test identity. Never keep a header bypass in production.
- **Exit:** with dev headers off, a request carrying only `X-User` is 401. An OIDC-authenticated user sees only their workspaces. A service token can't reach workspace admin endpoints.

## Slice 3 — Model catalog and connection references

- **Model provider connections:** tables `connections` (workspace or enterprise scope, type, status, expiry) and `model_provider_connections`. Only enterprise admins create them.
  - Secrets are stored as references resolved through `SecretsPort`, never as values in a row, prompt or DTO.
- **Model catalog:** `models` rows (provider connection, model id, display name, enabled) replace the YAML-derived `ModelCatalog`. Publication validation calls the same `authorizedModels()` method, so slice 1 code doesn't change.
- **Changes without restart:** disabling a model or revoking a connection affects the next run immediately. A pinned run whose model is revoked fails visibly at invocation and does not fall back to another model.
- **Exit:** an admin adds a model and binds an agent to it without a restart. A revoked connection fails the next invocation with an explicit error.

## Slice 4 — Single-agent durable run

- **Workflow:** a new Temporal workflow type, `AgentRunWorkflow`, in `core/.../workflow`, registered on the `REASONING` worker. It is not `FeatureWorkflow`.
  - Input: workspace, run id, and the resolved bundle (agent id, version, content hash). Never the prompt text.
- **Runner:** an `AgentRunActivities.invoke` activity in `agents`.
  1. Load the pinned version from Postgres (read-only) and re-verify its hash.
  2. Check that required variables are present.
  3. Render with the existing Mustache engine using a data-only map.
  4. Call the bound model through Embabel, enforcing `timeoutSeconds` and `maxOutputTokens` where the provider supports them. Unknown token usage is recorded as unknown.
  5. Validate the output against `outputSchema`.
- **Run records:** a `platform_runs` projection (status, pinned version and hash, timings, usage-or-unknown, error) and a `platform_run_artifacts` table for outputs.
  - Large inputs and outputs go to access-controlled storage, never into workflow history.
- **Working context:** bounded to this run's input plus its own prior turns (none, for single-shot runs). Retrieval and memory arrive in later phases.
- **API:** `POST /api/workspaces/{ws}/agents/{id}/runs` (OPERATOR), with an idempotency key: the same key returns the same run. Also `GET .../runs/{runId}` and `POST .../runs/{runId}/cancel`.
- **Exit:** the ALPHA/BETA scenario end to end. Start a run that waits before invoking the model; publish v2; the run outputs ALPHA and records the v1 hash; the next run outputs BETA. A worker restart mid-run resumes.

## Slice 5 — Agent Studio UI

- **Navigation:** a workspace selector in AppShell. Workspace id goes into every react-query key.
- **Screens:** an Agents list, and an editor with prompt (CodeMirror), variables, model picker from the catalog, limits and output schema.
- **Publishing and history:** a validate panel with the findings, draft revision conflict handling (reload and compare), publish, version history with a side-by-side diff, rollback and retire.
- **Runs:** a test-invocation drawer that creates a real run in slice 4's run API and shows the pinned version and hash.
- **Verification:** `npm run build`, plus manual browser checks of keyboard-only editing, narrow widths, and the empty, loading, conflict and 403/404 states.

## Slice 6 — PDLC seed import

- Create a `pdlc` workspace once at startup, recorded in a `platform_imports` row so it never re-runs.
- Import each bundled/overridden prompt and each `agents.roles` entry as a published AgentDefinition, v1. Existing `projects` rows become that workspace's project config references.
- Existing `FeatureWorkflow` executions and agents keep their current code paths. This slice only makes the assets visible and editable for the Phase 5 cutover.
- **Exit:** a fresh database shows the PDLC agents as v1 with hashes. A second boot imports nothing. Editing a PDLC agent in the Studio doesn't change the running legacy pipeline.

## Verification commands

```bash
mvn -q -pl core,control-plane test -Dtest='!PlatformRegistryIntegrationTest,!PersistenceIntegrationTest,!DemoReadOnlyTest,!BoardSideEffectsImplTest,!BoardSideEffectsImplTitleTest,!BuildTaskLeaseTest,!AgentPresenceServiceTest,!DemoInitializerIntegrationTest,!LocalBoardAdapterIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false   # no Docker
mvn -pl control-plane test -Dtest=PlatformRegistryIntegrationTest   # needs Docker
```
