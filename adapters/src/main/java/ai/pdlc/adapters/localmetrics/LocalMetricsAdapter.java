package ai.pdlc.adapters.localmetrics;

import ai.pdlc.core.domain.MetricSample;
import ai.pdlc.core.domain.MetricSeries;
import ai.pdlc.core.port.MetricsPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link MetricsPort} backed by the shared Postgres {@code metric_samples} table - build-order
 * phase 5's {@code local} provider. Persisted, not in-process: control-plane (whose REST endpoint
 * is how a real metrics pipeline, or in the pilot the e2e demo script, pushes samples in) and
 * agents (where the monitor agent's {@link #query} call actually runs) are two separate
 * JVMs/containers that share only the database (tech-stack §6 deployment shape); an in-memory
 * store here would be invisible across that process boundary. {@link #query} returns only samples
 * within the trailing {@code window} of {@link Instant#now()}, matching what a real time-series
 * backend's range query would do.
 */
public final class LocalMetricsAdapter implements MetricsPort {

    private final DataSource dataSource;

    public LocalMetricsAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void record(String signal, Instant at, double value) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO metric_samples (signal, at, value) VALUES (?, ?, ?)")) {
            ps.setString(1, signal);
            ps.setTimestamp(2, Timestamp.from(at));
            ps.setDouble(3, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LocalMetricsAdapterException("Could not record sample for signal " + signal, e);
        }
    }

    @Override
    public MetricSeries query(String signal, Duration window) {
        Instant cutoff = Instant.now().minus(window);
        List<MetricSample> samples = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT at, value FROM metric_samples WHERE signal = ? AND at >= ? ORDER BY at")) {
            ps.setString(1, signal);
            ps.setTimestamp(2, Timestamp.from(cutoff));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    samples.add(new MetricSample(rs.getTimestamp("at").toInstant(), rs.getDouble("value")));
                }
            }
        } catch (SQLException e) {
            throw new LocalMetricsAdapterException("Could not query signal " + signal, e);
        }
        return new MetricSeries(signal, samples);
    }
}
