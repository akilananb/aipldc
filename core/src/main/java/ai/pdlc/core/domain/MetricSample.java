package ai.pdlc.core.domain;

import java.time.Instant;

/** One point in a {@link MetricSeries} — tech-stack §4 {@code MetricsPort}. */
public record MetricSample(Instant at, double value) {
}
