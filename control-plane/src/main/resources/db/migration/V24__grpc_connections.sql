-- Phase 2 slice 2.6b: GRPC_AGENT connections. TLS material is referenced, never stored: an optional
-- pinned CA (replaces the JVM trust store for this connection) and an optional mTLS client
-- certificate and key, each a kv:// reference resolved only by the agents process that calls.
ALTER TABLE connections
    ADD COLUMN tls_ca_ref          TEXT,
    ADD COLUMN tls_client_cert_ref TEXT,
    ADD COLUMN tls_client_key_ref  TEXT,
    ADD CONSTRAINT connections_tls_client_pair
        CHECK ((tls_client_cert_ref IS NULL) = (tls_client_key_ref IS NULL));

-- A gRPC call is cancelled on the wire (RST_STREAM); the protocol returns no acknowledgement, so the
-- platform records that it signalled the cancel rather than claiming the service accepted it.
ALTER TABLE platform_remote_tasks DROP CONSTRAINT platform_remote_tasks_cancel_check;
ALTER TABLE platform_remote_tasks ADD CONSTRAINT platform_remote_tasks_cancel_check
    CHECK (cancel IN ('REQUESTED', 'ACKNOWLEDGED', 'REFUSED', 'UNSUPPORTED', 'NOT_FOUND', 'FAILED', 'SIGNALLED'));
