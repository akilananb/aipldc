package ai.pdlc.core.domain;

import java.util.List;
import java.util.Map;

/**
 * PO agent → gate 1 (then plan agent) handoff — {@code docs/agent-playbook.md} §2 "Produces".
 *
 * @param envelope   the base handoff envelope
 * @param parent     feature work item id
 * @param change     openspec change folder, e.g. {@code openspec/changes/export-orders-csv}
 * @param scenarios  scenario names, in story order
 * @param nfr        NFR key → threshold value
 * @param areas      code areas touched, from {@code openspec/config.yaml} path map
 * @param invest     INVEST letter (I,N,V,E,S,T) → {@code pass|fail}
 * @param dorUnmet   Definition-of-Ready checks the agent could not satisfy; non-empty is still produced
 * @param approvals  role → {@link Approval}; absent key means not yet approved
 */
public record PoHandoff(
        Handoff envelope,
        String parent,
        String change,
        List<String> scenarios,
        Map<String, Object> nfr,
        List<String> areas,
        Map<String, String> invest,
        List<String> dorUnmet,
        Map<String, Approval> approvals) {

    public PoHandoff {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        nfr = nfr == null ? Map.of() : Map.copyOf(nfr);
        areas = areas == null ? List.of() : List.copyOf(areas);
        invest = invest == null ? Map.of() : Map.copyOf(invest);
        dorUnmet = dorUnmet == null ? List.of() : List.copyOf(dorUnmet);
        approvals = approvals == null ? Map.of() : Map.copyOf(approvals);
    }

    public boolean investAllPass() {
        return invest.values().stream().allMatch("pass"::equalsIgnoreCase);
    }
}
