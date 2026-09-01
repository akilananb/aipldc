-- build-order phase 5: MetricsPort samples, shared between control-plane (the e2e demo's ingest
-- endpoint writes here) and the agents process (the monitor agent's query reads here) - the two
-- run as separate JVMs/containers (tech-stack §6 deployment shape) that share only this database,
-- so an in-process store would be invisible across that boundary.
CREATE TABLE metric_samples (
    id      BIGSERIAL PRIMARY KEY,
    signal  TEXT NOT NULL,
    at      TIMESTAMPTZ NOT NULL,
    value   DOUBLE PRECISION NOT NULL
);

CREATE INDEX idx_metric_samples_signal_at ON metric_samples (signal, at);
