package ai.pdlc.core.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code pdlc.yaml} (tech-stack §4) into typed {@link Profile}s. Parses to a generic
 * {@code Map<String,Object>} first (SnakeYAML has no first-class Java-record binding), then reads
 * only the keys this pilot consumes; every other key in the document is ignored (forward-compat with
 * later build-order phases, e.g. {@code ci}, {@code release_pack}).
 *
 * <p>Fails fast at startup, naming the missing key, when a requested profile does not exist or a
 * profile has no {@code gates.G1} entry.
 */
public final class PdlcConfig {

    private final Map<String, Profile> profiles;

    private PdlcConfig(Map<String, Profile> profiles) {
        this.profiles = profiles;
    }

    public static PdlcConfig load(InputStream yaml) {
        Yaml snakeYaml = new Yaml();
        Object root = snakeYaml.load(yaml);
        return fromParsed(asMap(root));
    }

    public static PdlcConfig loadFromFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read pdlc.yaml at " + path, e);
        }
    }

    private static PdlcConfig fromParsed(Map<String, Object> root) {
        Map<String, Object> rawProfiles = asMap(root.get("profiles"));
        Map<String, Profile> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawProfiles.entrySet()) {
            profiles.put(entry.getKey(), parseProfile(entry.getKey(), asMap(entry.getValue())));
        }
        return new PdlcConfig(profiles);
    }

    /** Returns the named profile, or throws {@link PdlcConfigException} naming the missing key. */
    public Profile profile(String name) {
        Profile profile = profiles.get(name);
        if (profile == null) {
            throw new PdlcConfigException("Missing profile: " + name);
        }
        profile.gate("G1"); // fail fast: every pilot profile must define gates.G1
        return profile;
    }

    /** The DB-persisted half of the named profile (project meta + board + repos + gates). */
    public ProjectDocument document(String name) {
        Profile p = profile(name);
        return new ProjectDocument(p.project(), p.board(), p.repos(), p.gates());
    }

    public Map<String, Profile> profiles() {
        return Map.copyOf(profiles);
    }

    private static Profile parseProfile(String name, Map<String, Object> raw) {
        BoardConfig board = parseBoard(asMap(raw.get("board")));
        List<RepoConfig> repos = parseRepos(name, raw);
        ProjectMeta project = parseProjectMeta(name, asMap(raw.get("project")));
        NotifyConfig notify = parseNotify(asMap(raw.get("notify")));
        AgentsConfig agents = parseAgents(asMap(raw.get("agents")));
        Map<String, GateConfig> gates = parseGates(asMap(raw.get("gates")));
        return new Profile(name, project, board, repos, notify, agents, gates);
    }

    private static BoardConfig parseBoard(Map<String, Object> raw) {
        Map<String, Object> rawAuth = asMap(raw.get("auth"));
        BoardConfig.AuthConfig auth = new BoardConfig.AuthConfig(
                asString(rawAuth.get("kind")), asString(rawAuth.get("secret_ref")));
        return new BoardConfig(
                asString(raw.get("provider")),
                asString(raw.get("org")),
                asString(raw.get("project")),
                asStringMap(raw.get("types")),
                asStringMap(raw.get("states")),
                auth);
    }

    @SuppressWarnings("unchecked")
    private static List<RepoConfig> parseRepos(String name, Map<String, Object> raw) {
        Object rawRepos = raw.get("repos");
        Object rawRepo = raw.get("repo");
        if (rawRepos != null && rawRepo != null) {
            throw new PdlcConfigException("profile " + name + ": use either repo or repos");
        }
        if (rawRepos != null) {
            List<Map<String, Object>> entries = (List<Map<String, Object>>) rawRepos;
            List<RepoConfig> repos = new java.util.ArrayList<>();
            boolean anyPrimary = false;
            for (Map<String, Object> entry : entries) {
                boolean primary = Boolean.TRUE.equals(entry.get("primary"));
                anyPrimary = anyPrimary || primary;
                repos.add(new RepoConfig(
                        asString(entry.get("id")),
                        asString(entry.get("provider")),
                        asString(entry.get("url")),
                        asString(entry.get("default_branch")),
                        asString(entry.get("spec_dir")),
                        (List<String>) entry.get("areas"),
                        primary));
            }
            if (!anyPrimary && !repos.isEmpty()) {
                RepoConfig first = repos.get(0);
                repos.set(0, new RepoConfig(first.id(), first.provider(), first.url(),
                        first.defaultBranch(), first.specDir(), first.areas(), true));
            }
            return repos;
        }
        Map<String, Object> legacy = asMap(rawRepo);
        return List.of(new RepoConfig(
                "main",
                asString(legacy.get("provider")),
                asString(legacy.get("url")),
                asString(legacy.get("default_branch")),
                asString(legacy.get("spec_dir")),
                List.of(),
                true));
    }

    private static ProjectMeta parseProjectMeta(String name, Map<String, Object> raw) {
        String metaName = raw.get("name") == null ? name : asString(raw.get("name"));
        List<ProjectMeta.DocLink> docs = parseDocs(raw.get("docs"));
        Map<String, Object> rawBuild = asMap(raw.get("build"));
        ProjectMeta.BuildDefaults build = new ProjectMeta.BuildDefaults(asString(rawBuild.get("acp_agent")));
        return new ProjectMeta(
                name,
                metaName,
                asString(raw.get("confluence_url")),
                docs,
                asString(raw.get("brief")),
                build);
    }

    @SuppressWarnings("unchecked")
    private static List<ProjectMeta.DocLink> parseDocs(Object value) {
        if (value == null) {
            return List.of();
        }
        List<Map<String, Object>> raw = (List<Map<String, Object>>) value;
        List<ProjectMeta.DocLink> docs = new java.util.ArrayList<>();
        for (Map<String, Object> entry : raw) {
            docs.add(new ProjectMeta.DocLink(asString(entry.get("title")), asString(entry.get("url"))));
        }
        return docs;
    }

    private static NotifyConfig parseNotify(Map<String, Object> raw) {
        return new NotifyConfig(asString(raw.get("provider")), asString(raw.get("channel")));
    }

    private static AgentsConfig parseAgents(Map<String, Object> raw) {
        Map<String, Object> rawRoles = asMap(raw.get("roles"));
        Map<String, AgentsConfig.RoleConfig> roles = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawRoles.entrySet()) {
            Map<String, Object> rawRole = asMap(entry.getValue());
            Object budget = rawRole.get("budget_tokens");
            roles.put(entry.getKey(), new AgentsConfig.RoleConfig(
                    asString(rawRole.get("model")),
                    budget == null ? null : ((Number) budget).longValue()));
        }
        return new AgentsConfig(asString(raw.get("gateway")), asString(raw.get("prompts_dir")), roles);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, GateConfig> parseGates(Map<String, Object> raw) {
        Map<String, GateConfig> gates = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Map<String, Object> rawGate = asMap(entry.getValue());
            List<String> roles = rawGate.get("roles") == null
                    ? List.of()
                    : List.copyOf((List<String>) rawGate.get("roles"));
            boolean sod = Boolean.TRUE.equals(rawGate.get("sod"));
            gates.put(entry.getKey(), new GateConfig(roles, sod));
        }
        return gates;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> asStringMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> raw = (Map<String, Object>) value;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(entry.getKey(), asString(entry.getValue()));
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
