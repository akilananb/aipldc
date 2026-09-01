package ai.pdlc.core.port;

import java.util.Map;

public interface NotifyPort {

    String requestApproval(String to, Map<String, Object> payload);

    void post(String channel, String message);
}
