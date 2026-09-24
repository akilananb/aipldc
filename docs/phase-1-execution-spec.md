# Phase 1 execution spec: Secure Agent Studio

This spec turns Phase 1 of [configurable-agent-platform.md](configurable-agent-platform.md) into
slices that can be built one at a time. Each slice ships something usable end to end and has its
own exit evidence. **Slices 1–3 are implemented**; slices 4–6 are specified here and are not
yet built.

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

## Slice 2 — Enterprise sessions and service identities (implemented)

**Modes.** Each is switched on by configuration only; they can be combined.

| Mode | Enabled by | Who uses it |
|---|---|---|
| Enterprise OIDC login | Spring's `spring.security.oauth2.client.registration.pdlc.*` / `provider.pdlc.issuer-uri` | People, through the UI. Server-side session, HttpOnly SameSite=Lax cookie, SPA CSRF (`XSRF-TOKEN` cookie echoed as `X-XSRF-TOKEN`). |
| Service JWTs | `spring.security.oauth2.resourceserver.jwt.issuer-uri` (or `jwk-set-uri`) | build-worker and agents, via client-credentials tokens with scope `pdlc.build`, `pdlc.board.read` or `pdlc.webhook`. |
| Shared tokens | `BUILD_AGENT_TOKEN` (`X-Agent-Token`), `PDLC_SERVICE_TOKEN` (`X-Service-Token`), `PDLC_WEBHOOK_TOKEN` (`X-Webhook-Token`) | Fallbacks and board webhooks. Compared in constant time; each grants exactly one scope. |
| Dev headers | `PDLC_IDENTITY_DEV_HEADERS=true` (**off by default**; set in `infra/k8s/control-plane.yaml` for the local stack) | Local stack, e2e scripts, demo. A WARN is logged if enabled on any profile other than `local`. |

**Authorization in the security chain (`identity/SecurityConfig`).**

- **`/api/**` requires a human user** (`ROLE_USER`). Only an OIDC login whose groups map to a PDLC role, or a dev-header user, has that authority.
  - A service credential on a user endpoint gets 403.
  - A user whose groups don't map gets 403, and `/api/me` explains why.
- **Service paths:**
  - `/api/build-tasks/**` requires `pdlc.build`.
  - `/api/board/**` requires `pdlc.board.read`, or a user.
  - `/webhooks/**` requires `pdlc.webhook` once `PDLC_WEBHOOK_TOKEN` is set. Startup warns when it isn't set outside dev mode.
- **Unauthenticated API calls get a JSON 401**, never a redirect. `GET /api/me` is always reachable and reports `mode`, the caller and `loginUrl`.
- **Dev-headers mode keeps the pre-slice-2 behaviour exactly:**
  - reads are open;
  - mutations still need `X-User`/`X-Role` through `IdentityResolver`;
  - build-tasks, board and webhooks stay open until their tokens are set;
  - there is no CSRF, because no cookie login exists unless OIDC is also configured.

**Identity mapping (`pdlc.identity.*`).**

- `user-claim` (default `email`; falls back to `sub`) becomes `Identity.user`. Workspace memberships are keyed on this value, so choose a claim that is stable and never reassigned in your IdP.
- `role-claim` (default `groups`) is read together with `role-mapping.<group>: <PO|SquadLead|FSDeveloper|QA|Admin>`. When several groups map, the highest wins: Admin > SquadLead > PO > QA > FSDeveloper.

**Callers.**

- **build-worker:** `PDLC_OAUTH_TOKEN_URL`/`_CLIENT_ID`/`_CLIENT_SECRET`/`_SCOPE` fetch and cache a bearer token (`src/serviceToken.ts`). `BUILD_AGENT_TOKEN` stays the fallback.
- **agents:** `PDLC_SERVICE_OAUTH_TOKEN_URL`/`_CLIENT_ID`/`_CLIENT_SECRET`, or `PDLC_SERVICE_TOKEN`. Both feed `RemoteBoardPort` through `adapters/serviceauth`.
- **UI:** reads `/api/me` and shows one of three things:
  - the demo identity switcher, only in dev-headers mode;
  - the signed-in user with Log out;
  - a Sign in with SSO page.

  All requests use `credentials: 'include'`, and unsafe ones send the CSRF header.

**Exit evidence:**

| Test | What it proves |
|---|---|
| `SecurityConfigOidcTest` (MockMvc with real OIDC client registration and a real RSA-signed JWT decoder) | With dev headers off, `X-User` alone gets 401. A mapped OIDC user reads and, with CSRF, mutates. An unmapped user gets 403. A `pdlc.build` JWT claims build tasks but gets 403 on `/api/items`, `/api/workspaces` and the board. A forged JWT gets 401. Agent and webhook tokens are enforced. CORS allows credentials only for allowed origins. Login redirects to the IdP. Logout returns 204. |
| `SecurityConfigDevHeadersTest` | Local-stack behaviour is unchanged. |
| `IdentityResolverTest` | Claim mapping, role precedence, and that a service can never act as a user. |
| `ClientCredentialsTokenSourceTest`, `RemoteBoardPortTest`, `build-worker/src/serviceToken.test.ts` | Token requests, caching, failure handling, and headers on the wire. |

**Follow-ups:**

- The e2e scripts still use dev headers on the local stack. Move them to a test IdP identity once one is part of the Tilt stack.
- Replicated control-plane needs Spring Session JDBC (see [Scalability and service boundaries](configurable-agent-platform.md#scalability-and-service-boundaries)).
- IdP-initiated (RP) logout.

## Slice 3 — Model catalog and connection references (implemented)

This slice adds a `connections` module (`control-plane/.../connections`), following the module
rules in [Scalability and service boundaries](configurable-agent-platform.md#scalability-and-service-boundaries).

**Schema.** `V16__connections_model_catalog.sql` adds three tables:

- **`connections`:**
  - Scope is `ENTERPRISE` or `WORKSPACE`; `WORKSPACE` scope must name a workspace.
  - `kind`: only `MODEL_PROVIDER` for now.
  - `auth_type`: `API_KEY` or `NONE`.
  - `secret_ref`, `base_url`, `status` (`ACTIVE` or `REVOKED`), `expires_at`, and who changed it and when.
- **`models`:**
  - The catalog id is what an `AgentSpec` binds, e.g. `sonnet` or `anthropic/claude-sonnet`.
  - Each row also has `connection_id`, `provider_model` (the name sent to the provider), `display_name` and `enabled`.
- **`platform_imports`:** one row per one-time import from deployment config.

**Credentials.**

- A connection holds only a `kv://name` secret reference. A literal secret is rejected with a 400.
- No DTO ever returns a secret value.
- Control-plane does not resolve the reference. The execution adapter that calls the provider does (slice 4, in agents).
- Rotation replaces the reference. Revocation is terminal: to restore access, create a new connection.

**Availability.** A model is available when three things hold:

- it is enabled;
- its connection is `ACTIVE`;
- the connection has not expired.

`ModelCatalog` reads the database on every call, so changes take effect without a restart:

- `authorizedModels()` decides what can be published.
- `resolve(binding)` tries the bound model first, then only the fallbacks the agent declared. If none is available it fails with a 409 that names the reason for every candidate. There is no silent default.

`AgentRegistryService.resolveForRun` now returns `ResolvedAgentDto`: the pinned version plus `model`, `providerModel`, `connectionId` and `fallback`.

**Seed.** `ModelCatalogSeeder` runs once, recorded in `platform_imports`:

- It creates connection `default-gateway` for `agents.gateway`, with `kv://pdlc-llm-api-key`, i.e. `PDLC_LLM_API_KEY`.
- It adds one model per distinct `agents.roles.*.model`.
- Later `pdlc.yaml` edits never overwrite catalog edits.

**REST (`/api/platform`).**

| Method and path | Who |
|---|---|
| `GET /connections` | enterprise Admin |
| `POST /connections` | enterprise Admin |
| `PUT /connections/{id}` (rotate `secretRef`, `baseUrl`, `expiresAt`) | enterprise Admin |
| `POST /connections/{id}/revoke` | enterprise Admin |
| `GET /models` | any signed-in user |
| `POST /models` | enterprise Admin |
| `PUT /models/{*id}` (ids may contain `/`) | enterprise Admin |

**Exit evidence:**

| Test | What it proves |
|---|---|
| `ModelCatalogTest` | Availability rules (disabled, revoked, expired); fallback order; the explicit error. |
| `ConnectionServiceTest` | Admin-only writes; secret values rejected; revocation is terminal; models need an active connection. |
| `ModelCatalogSeederTest` | The import runs once and preserves admin edits. |
| `AgentRegistryServiceTest` (new cases) | A model added to the catalog is publishable without a restart; a revoked connection fails the next resolution with its reason; a disabled model uses a declared fallback. |
| `PlatformRegistryIntegrationTest` | The JDBC catalog and one-time imports against real Postgres. |

**Not in this slice:**

- workspace-level model allowlists (every workspace sees the whole enterprise catalog);
- connection health checks against the provider;
- the agents runtime reading the catalog. That comes with the run in slice 4, where the model is resolved at invocation time.

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
