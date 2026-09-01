package ai.pdlc.core.config;

import java.util.List;

/** {@code pdlc.yaml} §4 {@code gates.<Gn>.*} — {@code roles} + {@code sod} consumed by the pilot. */
public record GateConfig(List<String> roles, boolean sod) {
}
