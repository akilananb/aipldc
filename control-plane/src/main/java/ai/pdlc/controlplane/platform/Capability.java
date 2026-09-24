package ai.pdlc.controlplane.platform;

/**
 * Workspace-scoped capabilities (configurable-agent-platform.md §3). One person may hold several.
 * Enterprise administration is not a capability here: it is the existing {@code Admin} role on
 * the resolved identity, which may create workspaces but does not grant access to their content.
 */
public enum Capability {
    /** Manage membership; publish, roll back and retire assets. */
    WORKSPACE_ADMIN,
    /** Create and edit drafts. */
    AUTHOR,
    /** Start and cancel runs; resolve published definitions for a run. */
    OPERATOR,
    /** Approve designated artifacts. */
    REVIEWER,
    /** Publish knowledge collections and promoted memories. */
    CURATOR
}
