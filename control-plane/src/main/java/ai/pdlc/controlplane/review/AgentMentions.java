package ai.pdlc.controlplane.review;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the first @agent mention in a comment. Supported agents are a fixed pilot set. */
public final class AgentMentions {
    public static final Set<String> AGENTS = Set.of("analyst", "architect", "qa", "dev");
    private static final Pattern MENTION = Pattern.compile("(?i)(?:^|[^\\w@])@(analyst|architect|qa|dev)\\b");

    private AgentMentions() {
    }

    /** First supported mention, lower-cased, or empty. */
    public static Optional<String> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = MENTION.matcher(text);
        return m.find() ? Optional.of(m.group(1).toLowerCase(Locale.ROOT)) : Optional.empty();
    }
}
