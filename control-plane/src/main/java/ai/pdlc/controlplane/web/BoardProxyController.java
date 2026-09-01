package ai.pdlc.controlplane.web;

import ai.pdlc.core.domain.Comment;
import ai.pdlc.core.domain.WorkItem;
import ai.pdlc.core.domain.WorkItemRef;
import ai.pdlc.core.port.BoardPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Thin REST proxy over control-plane's own {@link BoardPort} instance - build-order phase 4 fix.
 * Agents (a separate JVM, orchestration-decision §6) has no board state of its own for the
 * in-memory provider; before this existed, {@code AgentContext.readWorkItem} always saw an empty
 * board. {@code GET /api/items/{id}} isn't a substitute: it's backed by the {@code work_items}
 * Postgres row, which {@code ensureWorkItem} doesn't create until {@code postGrillQuestions} runs
 * - one activity *after* {@code grillEvaluate}, the very first read this exists to serve. This
 * proxy reads straight from the board (the system of record for title/description), independent
 * of whether that DB row exists yet.
 */
@RestController
@RequestMapping("/api/board")
public class BoardProxyController {

    private final BoardPort board;

    public BoardProxyController(BoardPort board) {
        this.board = board;
    }

    @GetMapping("/{profile}/{boardId}")
    public WorkItem getItem(@PathVariable String profile, @PathVariable String boardId) {
        return board.getItem(new WorkItemRef(profile, boardId));
    }

    @GetMapping("/{profile}/{boardId}/comments")
    public List<Comment> listComments(@PathVariable String profile, @PathVariable String boardId) {
        return board.listComments(new WorkItemRef(profile, boardId));
    }
}
