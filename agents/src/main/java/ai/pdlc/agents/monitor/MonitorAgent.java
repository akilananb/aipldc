package ai.pdlc.agents.monitor;

import ai.pdlc.core.domain.CanonicalState;
import ai.pdlc.core.domain.Handoff;
import ai.pdlc.core.domain.MetricSeries;
import ai.pdlc.core.domain.MonitorHandoff;
import ai.pdlc.core.domain.MonitorRule;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.MetricsPort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitor agent (playbook §8) as a plain Spring service — deterministic, no LLM call: a threshold
 * evaluation is not a reasoning task. Evaluates each {@link MonitorRule} against {@link
 * MetricsPort} for the rule's own window (parsed from its threshold text, e.g. {@code "> 2% over
 * 15m"}), and on a trip, gathers evidence for one filed card. This is the pilot's one evaluation
 * pass right after deploy — see {@code FeatureWorkflowImpl#run} step 12 for why a true multi-day
 * watch window is out of scope.
 */
@Component
public class MonitorAgent {

    /** Matches thresholds of the shape {@code "> N% over Mm"} / {@code ">= N for Mh"} etc - the
     * two forms the playbook's own worked examples use (lines 449, 454, 458). */
    private static final Pattern THRESHOLD = Pattern.compile(
            "(>=?|<=?)\\s*([0-9.]+)%?(?:\\s*(?:over|for)\\s*(\\d+)\\s*([mh]))?", Pattern.CASE_INSENSITIVE);

    public MonitorHandoff evaluate(WorkItemRef story, List<MonitorRule> rules, MetricsPort metrics) {
        List<MonitorHandoff.Trip> trips = new ArrayList<>();
        for (MonitorRule rule : rules) {
            Threshold threshold = parseThreshold(rule.threshold());
            MetricSeries series = metrics.query(rule.signal(), threshold.window());
            double observed = series.average();
            if (tripped(observed, threshold)) {
                trips.add(new MonitorHandoff.Trip(rule.id(), evidence(rule, series, observed, threshold), "bug", rule.owner()));
            }
        }
        Handoff envelope = new Handoff("monitor-agent", "grill-agent", story.boardId(), CanonicalState.DONE,
                List.of("rules:" + rules.size()), 0.9, List.of(), List.of());
        return new MonitorHandoff(envelope, trips);
    }

    private static boolean tripped(double observed, Threshold t) {
        return switch (t.operator()) {
            case ">" -> observed > t.value();
            case ">=" -> observed >= t.value();
            case "<" -> observed < t.value();
            case "<=" -> observed <= t.value();
            default -> false;
        };
    }

    private static String evidence(MonitorRule rule, MetricSeries series, double observed, Threshold threshold) {
        return "signal=" + rule.signal() + ", window=" + threshold.window() + ", samples=" + series.samples().size()
                + ", observed=" + observed + ", threshold=" + rule.threshold();
    }

    record Threshold(String operator, double value, Duration window) {
    }

    static Threshold parseThreshold(String text) {
        Matcher m = THRESHOLD.matcher(text);
        if (!m.find()) {
            return new Threshold(">", Double.MAX_VALUE, Duration.ofDays(7)); // never trips on unparseable text
        }
        String operator = m.group(1);
        double value = Double.parseDouble(m.group(2));
        Duration window = Duration.ofDays(7);
        if (m.group(3) != null) {
            long amount = Long.parseLong(m.group(3));
            window = "h".equalsIgnoreCase(m.group(4)) ? Duration.ofHours(amount) : Duration.ofMinutes(amount);
        }
        return new Threshold(operator, value, window);
    }
}
