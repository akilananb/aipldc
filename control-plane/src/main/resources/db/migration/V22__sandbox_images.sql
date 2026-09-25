-- Configurable agent platform, phase 2 slice 2.4 (docs/phase-2-execution-spec.md): the enterprise
-- catalog of container images approved to run as sandbox tools. Each entry pins an image by digest
-- and declares its input/output schemas, the hosts it may reach through the egress proxy and its
-- resource limits. A tool of kind "sandbox" names an entry and the exact image_ref it was reviewed
-- with; changing image_ref here makes those tools refuse to run until they are re-reviewed.
CREATE TABLE sandbox_images (
    id                 TEXT PRIMARY KEY,
    image_ref          TEXT NOT NULL,
    description        TEXT NOT NULL,
    input_schema_json  TEXT NOT NULL,
    output_schema_json TEXT,
    egress_hosts_json  TEXT NOT NULL DEFAULT '[]',
    cpu_millis         INT NOT NULL,
    memory_mb          INT NOT NULL,
    timeout_seconds    INT NOT NULL,
    status             TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RETIRED')),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         TEXT NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         TEXT NOT NULL,
    retired_at         TIMESTAMPTZ,
    retired_by         TEXT
);
