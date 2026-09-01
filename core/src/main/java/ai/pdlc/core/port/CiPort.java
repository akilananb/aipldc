package ai.pdlc.core.port;

import ai.pdlc.core.domain.ArtifactRef;
import ai.pdlc.core.domain.CanonicalEvent;
import ai.pdlc.core.domain.RunRef;
import ai.pdlc.core.domain.RunStatus;

import java.util.List;
import java.util.Map;

/** CI port — tech-stack §4. {@code runVerify} is always a CI job the agents cannot edit (branch
 * protection); {@code runDeploy} is only ever called by the workflow after gate 3. */
public interface CiPort {

    RunRef runVerify(String branchOrPr);

    RunRef runDeploy(String env, String releaseId, Map<String, Object> params);

    RunStatus getRun(String ref);

    List<ArtifactRef> getArtifacts(String ref);

    CanonicalEvent onWebhook(String profile, Map<String, Object> rawEvent);
}
