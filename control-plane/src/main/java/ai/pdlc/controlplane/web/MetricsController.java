package ai.pdlc.controlplane.web;

import ai.pdlc.adapters.localmetrics.LocalMetricsAdapter;
import ai.pdlc.controlplane.web.dto.MetricSampleRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Thin ingest endpoint for {@link LocalMetricsAdapter} - there is no real observability stack in
 * the pilot (tech-stack §1 defers Langfuse/Promptfoo wiring), so a real metrics pipeline (or, in
 * the pilot, the e2e demo script) pushes samples in here for the monitor agent to later read back
 * through {@code MetricsPort#query}.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final LocalMetricsAdapter metrics;

    public MetricsController(LocalMetricsAdapter metrics) {
        this.metrics = metrics;
    }

    @PostMapping
    public ResponseEntity<Void> record(@RequestBody MetricSampleRequest request) {
        metrics.record(request.signal(), Instant.now(), request.value());
        return ResponseEntity.ok().build();
    }
}
