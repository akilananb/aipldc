package ai.pdlc.controlplane.web.dto;

/**
 * TLS material of a {@code GRPC_AGENT} connection (slice 2.6b), as {@code kv://} references only.
 * {@code caRef} pins the CA that must have signed the service's certificate (instead of the JVM trust
 * store); {@code clientCertRef}/{@code clientKeyRef} (both or neither) present a client certificate
 * for mTLS. Each resolves to PEM in the agents process that makes the call.
 */
public record ConnectionTls(String caRef, String clientCertRef, String clientKeyRef) {

    public boolean empty() {
        return blank(caRef) && blank(clientCertRef) && blank(clientKeyRef);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
