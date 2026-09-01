package ai.pdlc.adapters.localmetrics;

import ai.pdlc.core.domain.MetricSeries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LocalMetricsAdapterTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE metric_samples (
                        id      BIGSERIAL PRIMARY KEY,
                        signal  TEXT NOT NULL,
                        at      TIMESTAMPTZ NOT NULL,
                        value   DOUBLE PRECISION NOT NULL
                    )
                    """);
        }
    }

    @Test
    void queryReturnsOnlySamplesWithinTheWindow() {
        LocalMetricsAdapter metrics = new LocalMetricsAdapter(dataSource);
        metrics.record("http_5xx_rate", Instant.now().minus(Duration.ofHours(2)), 0.5);
        metrics.record("http_5xx_rate", Instant.now().minus(Duration.ofMinutes(5)), 3.4);
        metrics.record("http_5xx_rate", Instant.now(), 3.6);

        MetricSeries series = metrics.query("http_5xx_rate", Duration.ofMinutes(15));

        assertThat(series.samples()).hasSize(2);
        assertThat(series.average()).isCloseTo(3.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(series.max()).isEqualTo(3.6);
    }

    @Test
    void queryForUnknownSignalReturnsEmptySeries() {
        LocalMetricsAdapter metrics = new LocalMetricsAdapter(dataSource);

        MetricSeries series = metrics.query("nonexistent", Duration.ofHours(1));

        assertThat(series.samples()).isEmpty();
        assertThat(series.average()).isZero();
        assertThat(series.max()).isZero();
    }

    @Test
    void recordedSamplesArePersistedAcrossSeparateAdapterInstances() {
        // Simulates the real deployment: control-plane's ingest endpoint and the agents process's
        // monitor agent are two separate JVMs, each with their own LocalMetricsAdapter instance,
        // sharing only the database.
        new LocalMetricsAdapter(dataSource).record("cross-process-signal", Instant.now(), 42.0);

        MetricSeries series = new LocalMetricsAdapter(dataSource).query("cross-process-signal", Duration.ofMinutes(15));

        assertThat(series.samples()).hasSize(1);
        assertThat(series.average()).isEqualTo(42.0);
    }
}
