-- Per-claim fencing token + claim counter for the build-worker pool (BuildTaskService#claim /
-- #releaseExpiredLeases). A stale worker's late heartbeat/result must never touch a row another
-- worker has since reclaimed.
ALTER TABLE build_tasks
    ADD COLUMN lease_token UUID,
    ADD COLUMN claim_count INT NOT NULL DEFAULT 0;
-- Pre-pool rows: anything still 'claimed' with a dead lease is a crashed attempt Temporal has
-- already (or will) supersede; expire it so the one-live-claim-per-story index below can build.
UPDATE build_tasks SET state='expired', updated_at=now()
 WHERE state='claimed' AND (lease_expires_at IS NULL OR lease_expires_at < now());
-- Wave tasks share one story branch (FeatureWorkflowImpl#runWave): at most one live claim per story.
CREATE UNIQUE INDEX build_tasks_one_claim_per_story_idx ON build_tasks (profile, story_board_id) WHERE state='claimed';
CREATE INDEX build_tasks_lease_idx ON build_tasks (lease_expires_at) WHERE state='claimed';
