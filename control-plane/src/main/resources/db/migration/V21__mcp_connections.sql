-- Configurable agent platform, phase 2 slice 2.3 (docs/phase-2-execution-spec.md): remote MCP
-- servers are connections of kind MCP_SERVER. With auth type OAUTH_CLIENT_CREDENTIALS the
-- connection holds the OAuth client id here and the client secret as its kv:// secret reference;
-- the agents worker obtains short-lived tokens itself and never stores them.
ALTER TABLE connections ADD COLUMN oauth_client_id TEXT;
