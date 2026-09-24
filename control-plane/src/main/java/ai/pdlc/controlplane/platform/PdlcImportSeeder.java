package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.ConnectionStore;
import ai.pdlc.core.config.AgentsConfig;
import ai.pdlc.core.config.Profile;
import ai.pdlc.core.config.ProjectDirectory;
import ai.pdlc.core.platform.AgentSpec;
import ai.pdlc.core.platform.AgentSpecValidator;
import ai.pdlc.core.platform.BundledPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 1 slice 6 (docs/phase-1-execution-spec.md): imports the existing PDLC package as platform
 * assets - once, recorded in {@code platform_imports} as {@value #IMPORT_ID}.
 * <ul>
 *   <li>creates workspace {@value #WORKSPACE_ID};</li>
 *   <li>publishes each bundled prompt ({@link BundledPrompts}; a readable {@code prompts_dir}
 *       override wins) as agent {@code <prompt name>} v1, bound to its role's model from
 *       {@code pdlc.yaml} with {@code budget_tokens} as max output tokens, every referenced variable
 *       declared optional - or leaves it a draft, with the reason, if it fails publication;</li>
 *   <li>links every existing project to the workspace ({@code workspace_projects}).</li>
 * </ul>
 * The legacy pipeline is untouched: the PDLC agents keep rendering the prompt files through
 * {@code PromptTemplates}, never the registry, so a Studio edit changes platform runs only.
 *
 * <p>On every startup it also grants {@code WORKSPACE_ADMIN} in {@value #WORKSPACE_ID} to each user
 * in {@code pdlc.platform.pdlc-workspace-admins} (additive - it never removes anyone), because a
 * system-created workspace has no creator to administer it.
 */
@Component
@Order(3)
public class PdlcImportSeeder implements ApplicationRunner {

    static final String IMPORT_ID = "pdlc-package-v1";
    static final String WORKSPACE_ID = "pdlc";
    static final String ACTOR = "system:pdlc-import";
    static final int TIMEOUT_SECONDS = 600;

    private static final Logger log = LoggerFactory.getLogger(PdlcImportSeeder.class);

    private final WorkspaceStore workspaces;
    private final AgentRegistryService registry;
    private final ConnectionStore imports;
    private final ProjectDirectory projects;
    private final Profile deploymentProfile;
    private final List<String> admins;

    public PdlcImportSeeder(WorkspaceStore workspaces, AgentRegistryService registry, ConnectionStore imports,
                            ProjectDirectory projects, Profile deploymentProfile,
                            @Value("${pdlc.platform.pdlc-workspace-admins:}") String admins) {
        this.workspaces = workspaces;
        this.registry = registry;
        this.imports = imports;
        this.projects = projects;
        this.deploymentProfile = deploymentProfile;
        this.admins = Arrays.stream(admins.split(",")).map(String::trim).filter(a -> !a.isEmpty()).toList();
    }

    /** One transaction: the import (and its platform_imports row) commits entirely or not at all. */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        importOnce();
        grantAdmins();
    }

    void importOnce() {
        AgentsConfig agents = deploymentProfile.agents();
        String promptsDir = agents == null ? null : agents.promptsDir();
        if (promptsDir != null && !promptsDir.isBlank() && !Files.isDirectory(Path.of(promptsDir))) {
            log.warn("agents.prompts_dir {} is not readable from control-plane; the PDLC import uses the bundled prompts, "
                    + "which may differ from what the agents process renders", promptsDir);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (!imports.recordImport(IMPORT_ID, "pending")) {
            return;
        }
        workspaces.insert(WORKSPACE_ID, "PDLC", ACTOR);

        List<String> published = new ArrayList<>();
        Map<String, List<String>> draftsOnly = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String name : BundledPrompts.NAMES) {
            BundledPrompts.Prompt prompt = BundledPrompts.load(name, promptsDir).orElse(null);
            if (prompt == null) {
                draftsOnly.put(name, List.of("prompt file not found"));
                continue;
            }
            sources.put(name, prompt.source());
            AgentsConfig.RoleConfig role = agents == null || agents.roles() == null ? null
                    : agents.roles().get(BundledPrompts.role(name));
            AgentSpec spec = new AgentSpec(
                    "Imported from the " + (prompt.source().equals("bundled") ? "bundled" : "overridden")
                            + " PDLC prompt " + name + " (role " + BundledPrompts.role(name) + ")",
                    AgentSpec.RUNTIME_NATIVE,
                    prompt.text(),
                    AgentSpecValidator.referencedVariables(prompt.text()).stream()
                            .map(v -> new AgentSpec.Variable(v, null, false)).toList(),
                    new AgentSpec.ModelBinding(role == null ? null : role.model(), List.of()),
                    new AgentSpec.Limits(maxTokens(role), TIMEOUT_SECONDS),
                    null);
            AgentRegistryService.ImportResult result = registry.importPublished(WORKSPACE_ID, name, displayName(name), spec, ACTOR);
            if (result.version() != null) {
                published.add(name);
            } else {
                draftsOnly.put(name, result.errors());
            }
        }
        List<String> linked = new ArrayList<>(projects.projects().keySet());
        for (String projectId : linked) {
            workspaces.linkProject(WORKSPACE_ID, projectId, ACTOR);
        }
        details.put("published", published);
        details.put("draftsOnly", draftsOnly);
        details.put("sources", sources);
        details.put("linkedProjects", linked);
        imports.updateImport(IMPORT_ID, details.toString());
        log.info("Imported the PDLC package into workspace {}: {} published, {} draft-only {}, projects {}",
                WORKSPACE_ID, published.size(), draftsOnly.size(), draftsOnly, linked);
    }

    void grantAdmins() {
        if (workspaces.find(WORKSPACE_ID).isEmpty()) {
            return;
        }
        if (admins.isEmpty()) {
            if (workspaces.members(WORKSPACE_ID).isEmpty()) {
                log.warn("Workspace {} has no members; set PDLC_WORKSPACE_ADMINS (pdlc.platform.pdlc-workspace-admins) "
                        + "to grant WORKSPACE_ADMIN to the users who manage the imported PDLC agents", WORKSPACE_ID);
            }
            return;
        }
        for (String user : admins) {
            Set<Capability> caps = EnumSet.noneOf(Capability.class);
            caps.addAll(workspaces.capabilities(WORKSPACE_ID, user));
            if (caps.add(Capability.WORKSPACE_ADMIN)) {
                workspaces.setMember(WORKSPACE_ID, user, caps, "config:pdlc-workspace-admins");
            }
        }
    }

    private static Integer maxTokens(AgentsConfig.RoleConfig role) {
        if (role == null || role.budgetTokens() == null) {
            return null;
        }
        return (int) Math.min(role.budgetTokens(), AgentSpecValidator.MAX_OUTPUT_TOKENS);
    }

    static String displayName(String name) {
        String spaced = name.replace('-', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
