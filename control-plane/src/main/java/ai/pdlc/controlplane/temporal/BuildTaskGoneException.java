package ai.pdlc.controlplane.temporal;

/**
 * Thrown by {@link BuildTaskService} when a build-agent operation (heartbeat/complete/fail)
 * targets a {@code build_tasks} row that is no longer the live attempt for its Temporal
 * activity — the row's lease expired, it was superseded by a retry, or Temporal itself reports
 * the activity no longer exists ({@code ActivityNotExistsException}/{@code
 * ActivityCanceledException}). Mapped to HTTP 410 by {@link
 * ai.pdlc.controlplane.web.ApiExceptionHandler}: the agent should stop working the task, no
 * further retries are meaningful for this specific row.
 */
public class BuildTaskGoneException extends RuntimeException {
    public BuildTaskGoneException(String message) {
        super(message);
    }

    public BuildTaskGoneException(String message, Throwable cause) {
        super(message, cause);
    }
}
