-- Phase 1 slice 6 (docs/phase-1-execution-spec.md): which PDLC projects a workspace's assets
-- serve. PdlcImportSeeder links every existing project to the imported `pdlc` workspace once.
-- A project deleted by an Admin simply drops out of the link.
CREATE TABLE workspace_projects (
    workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    project_id   TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    linked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    linked_by    TEXT NOT NULL,
    PRIMARY KEY (workspace_id, project_id)
);
