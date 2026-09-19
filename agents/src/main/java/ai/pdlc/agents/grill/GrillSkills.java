package ai.pdlc.agents.grill;

import com.embabel.agent.skills.Skills;
import com.embabel.agent.skills.support.LoadedSkill;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the pinned {@code grill-me}/{@code grilling} interview skills (ADAPTIVE_GRILL_PLAN.md
 * "Wire the native skill reference at application startup") from the two classpath resources
 * packaged by {@code agents/pom.xml}'s {@code .agents/skills} Maven resource, into a private
 * temp directory, and constructs the native Embabel {@link Skills} reference plus its eagerly
 * activated instructions once at application startup.
 *
 * <p>This catalog is fixed, scriptless, and mandatory for the Grill agent alone — every call must
 * receive the actual interview instructions rather than hoping the model discovers a catalog
 * entry. Missing/unreadable/malformed content fails bean creation rather than degrading into a
 * skill-less Grill.
 */
@Component
public class GrillSkills implements DisposableBean {

    /** Fixed, ordered allowlist — the only skills this component ever loads or activates. */
    private static final List<String> REQUIRED_SKILLS = List.of("grill-me", "grilling");

    private final Path extractedRoot;
    private final Skills skills;
    private final String instructions;

    public GrillSkills() {
        Path root;
        try {
            root = Files.createTempDirectory("pdlc-grill-skills-");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create temp directory for grill skills", e);
        }
        this.extractedRoot = root;
        try {
            Skills loaded = new Skills("grill-interview", "Clarification interview skills");
            for (String name : REQUIRED_SKILLS) {
                loaded = loaded.withLocalSkill(extractSkillDirectory(root, name).toString());
            }
            validate(loaded);
            this.skills = loaded;
            this.instructions = loaded.activate("grill-me") + "\n\n" + loaded.activate("grilling");
        } catch (RuntimeException e) {
            cleanup();
            throw e;
        }
    }

    /** The native Embabel reference to attach to the Grill agent's {@code PromptRunner} via
     * {@code withReference}. */
    public Skills reference() {
        return skills;
    }

    /** Both skills' full activation text, concatenated once — grill-me then grilling. */
    public String instructions() {
        return instructions;
    }

    private static Path extractSkillDirectory(Path root, String skillName) {
        String resourcePath = "/skills/" + skillName + "/SKILL.md";
        try (InputStream in = GrillSkills.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing required grill skill classpath resource: " + resourcePath);
            }
            Path dir = root.resolve(skillName);
            Files.createDirectories(dir);
            Files.copy(in, dir.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading required grill skill classpath resource: " + resourcePath, e);
        }
    }

    /** Fails fast on anything short of exactly the two required, scriptless, non-blank skills. */
    private static void validate(Skills loaded) {
        Set<String> names = new LinkedHashSet<>();
        for (LoadedSkill skill : loaded.getSkills()) {
            names.add(skill.getName());
            if (skill.getInstructions() == null || skill.getInstructions().isBlank()) {
                throw new IllegalStateException("Grill skill '" + skill.getName() + "' has blank instructions");
            }
            if (skill.hasScripts()) {
                throw new IllegalStateException("Grill skill '" + skill.getName() + "' unexpectedly supplies scripts");
            }
        }
        if (!names.equals(new LinkedHashSet<>(REQUIRED_SKILLS))) {
            throw new IllegalStateException("Expected grill skills " + REQUIRED_SKILLS + " but loaded " + names);
        }
    }

    @Override
    public void destroy() {
        cleanup();
    }

    /** Removes only this component's own extracted temp directory — never traverses/deletes any
     * other path. */
    private void cleanup() {
        if (extractedRoot == null || !Files.exists(extractedRoot)) {
            return;
        }
        try (var walk = Files.walk(extractedRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of our own temp directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of our own temp directory
        }
    }
}
