package ai.pdlc.agents.activities;

import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import ai.pdlc.core.port.RepoPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Best-effort context gathering (playbook "Reads" + plan step 6). Every read is best-effort -
 * catch and continue with whatever context is available - but the failure is logged at WARN so a
 * silently-empty context (e.g. a misconfigured board endpoint) is visible instead of masquerading
 * as "no context provided" in the agent's own output.
 */
public final class AgentContext {

    private static final Logger log = LoggerFactory.getLogger(AgentContext.class);

    private AgentContext() {
    }

    public static WorkItem readWorkItem(BoardPort board, WorkItemRef item) {
        try {
            return board.getItem(item);
        } catch (RuntimeException e) {
            log.warn("[context] board.getItem({}) failed; continuing with no item context: {}", item, e.toString());
            return null;
        }
    }

    public static List<Comment> readComments(BoardPort board, WorkItemRef item) {
        try {
            return board.listComments(item);
        } catch (RuntimeException e) {
            log.warn("[context] board.listComments({}) failed; continuing with no comments: {}", item, e.toString());
            return List.of();
        }
    }

    public static String readFile(RepoPort repo, String ref, String path) {
        try {
            return repo.readFile(ref, path);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
