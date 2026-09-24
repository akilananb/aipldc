package ai.pdlc.controlplane.platform;

import ai.pdlc.controlplane.connections.ConnectionService;
import ai.pdlc.controlplane.connections.InMemoryConnectionStore;
import ai.pdlc.controlplane.connections.ModelCatalog;
import ai.pdlc.controlplane.sandbox.InMemorySandboxImageStore;
import ai.pdlc.controlplane.sandbox.SandboxImageService;
import ai.pdlc.core.platform.EgressPolicy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;

/**
 * Wires the registry services over in-memory stores (public: connections and runs tests reuse it).
 * DNS is faked so no test touches the network: {@code api.example} is public, {@code internal.example}
 * resolves to a private address, literal IPs resolve to themselves.
 */
public final class TestRegistries {

    public static final Map<String, String> DNS = Map.of("api.example", "93.184.216.34", "internal.example", "10.0.0.8");

    public static EgressPolicy egress(Set<String> allowedPrivateHosts) {
        return new EgressPolicy(allowedPrivateHosts, host -> {
            try {
                String ip = DNS.get(host);
                if (ip != null) {
                    return new InetAddress[]{InetAddress.getByName(ip)};
                }
                return Character.isDigit(host.charAt(0)) || host.contains(":")
                        ? new InetAddress[]{InetAddress.getByName(host)} : new InetAddress[0];
            } catch (UnknownHostException e) {
                return new InetAddress[0];
            }
        });
    }

    public record Registries(ConnectionService connections, ToolRegistryService tools, AgentRegistryService agents,
                             SandboxImageService sandboxImages) {
    }

    public static Registries over(WorkspaceService workspaces, InMemoryConnectionStore connectionStore) {
        ConnectionService connections = new ConnectionService(connectionStore, new ModelCatalog(connectionStore), workspaces,
                egress(Set.of()));
        SandboxImageService sandboxImages = new SandboxImageService(new InMemorySandboxImageStore());
        ToolRegistryService tools = new ToolRegistryService(new InMemoryDefinitionStore(), workspaces, connections, sandboxImages);
        AgentRegistryService agents = new AgentRegistryService(new InMemoryAgentRegistryStore(), workspaces,
                new ModelCatalog(connectionStore), tools);
        return new Registries(connections, tools, agents, sandboxImages);
    }

    private TestRegistries() {
    }
}
