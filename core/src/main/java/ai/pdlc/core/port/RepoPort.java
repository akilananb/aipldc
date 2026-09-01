package ai.pdlc.core.port;

import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.CommitRef;
import ai.pdlc.core.domain.Diff;
import ai.pdlc.core.domain.PRRef;
import ai.pdlc.core.domain.PRStatus;

import java.util.Map;

/** Repo port — tech-stack §4. {@code openPR/commentOnPR/getDiff/getPRStatus} are in the port but
 * unused in the pilot (phase 3+); implemented thin per adapter notes. */
public interface RepoPort {

    String readFile(String ref, String path);

    /** Single commit; author = bot identity, human name folded into the commit message. */
    CommitRef writeFiles(String branch, Map<String, String> files, String message, String authorName);

    void createBranch(String from, String name);

    PRRef openPR(String branch, String target, String title, String body);

    void commentOnPR(String prId, String body, Integer line);

    Diff getDiff(String prId);

    PRStatus getPRStatus(String prId);

    CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent);
}
