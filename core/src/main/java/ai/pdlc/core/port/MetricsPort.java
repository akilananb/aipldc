package ai.pdlc.core.port;

import ai.pdlc.core.domain.MetricSeries;

import java.time.Duration;

/** Metrics port — tech-stack §4, read by the monitor agent (playbook §8). */
public interface MetricsPort {

    MetricSeries query(String signal, Duration window);
}
