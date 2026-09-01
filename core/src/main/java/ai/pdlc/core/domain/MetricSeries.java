package ai.pdlc.core.domain;

import java.util.List;

/** Result of {@link ai.pdlc.core.port.MetricsPort#query} — one named signal's samples in a window. */
public record MetricSeries(String signal, List<MetricSample> samples) {

    public MetricSeries {
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    public double average() {
        return samples.isEmpty() ? 0.0 : samples.stream().mapToDouble(MetricSample::value).average().orElse(0.0);
    }

    public double max() {
        return samples.stream().mapToDouble(MetricSample::value).max().orElse(0.0);
    }
}
