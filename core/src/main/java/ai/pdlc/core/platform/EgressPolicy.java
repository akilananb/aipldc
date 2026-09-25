package ai.pdlc.core.platform;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Outbound destination policy (configurable-agent-platform.md §3; docs/phase-2-execution-spec.md).
 * A destination is allowed only if it is {@code http(s)} and every address its host resolves to is
 * publicly routable - loopback, link-local (incl. the 169.254.169.254 metadata service), private,
 * CGNAT, multicast, unspecified, IPv6 ULA/link-local and IPv4-mapped forms of those are rejected -
 * unless the host is explicitly allowlisted by the enterprise ({@code pdlc.egress.allowed-private-hosts}).
 * Checking every resolved address stops a name with one public and one internal record.
 */
public final class EgressPolicy {

    /** Outcome; {@code addresses} are the checked addresses (empty when denied). */
    public record Decision(boolean allowed, String reason, List<InetAddress> addresses) {
        static Decision deny(String reason) {
            return new Decision(false, reason, List.of());
        }
    }

    private final Set<String> allowedPrivateHosts;
    private final Function<String, InetAddress[]> resolver;

    public EgressPolicy(Set<String> allowedPrivateHosts) {
        this(allowedPrivateHosts, host -> {
            try {
                return InetAddress.getAllByName(host);
            } catch (UnknownHostException e) {
                return new InetAddress[0];
            }
        });
    }

    public EgressPolicy(Set<String> allowedPrivateHosts, Function<String, InetAddress[]> resolver) {
        this.allowedPrivateHosts = allowedPrivateHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        this.resolver = resolver;
    }

    public Decision check(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Decision.deny("scheme " + scheme + " is not allowed");
        }
        if (uri.getUserInfo() != null) {
            return Decision.deny("credentials in the URL are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Decision.deny("no host");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        InetAddress[] addresses = resolver.apply(host);
        if (addresses.length == 0) {
            return Decision.deny("host " + host + " does not resolve");
        }
        if (allowedPrivateHosts.contains(normalized)) {
            return new Decision(true, "allowlisted host", List.of(addresses));
        }
        for (InetAddress a : addresses) {
            String why = blockedReason(a);
            if (why != null) {
                return Decision.deny("host " + host + " resolves to " + a.getHostAddress() + " (" + why + ")");
            }
        }
        return new Decision(true, "public destination", Arrays.asList(addresses));
    }

    static String blockedReason(InetAddress a) {
        if (a instanceof Inet6Address v6) {
            byte[] b = v6.getAddress();
            if (embedsIpv4(b)) {
                try {
                    return blockedReason(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
                } catch (UnknownHostException e) {
                    return "unparseable address";
                }
            }
            if ((b[0] & 0xfe) == 0xfc) {
                return "IPv6 unique-local";
            }
        }
        if (a.isAnyLocalAddress()) {
            return "unspecified address";
        }
        if (a.isLoopbackAddress()) {
            return "loopback";
        }
        if (a.isLinkLocalAddress()) {
            return "link-local / metadata";
        }
        if (a.isSiteLocalAddress()) {
            return "private network";
        }
        if (a.isMulticastAddress()) {
            return "multicast";
        }
        if (a instanceof Inet4Address) {
            byte[] b = a.getAddress();
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return "carrier-grade NAT";
            }
            if (b0 == 0) {
                return "this-network address";
            }
            if (b0 >= 240) {
                return "reserved address";
            }
        }
        return null;
    }

    /** IPv4-mapped (::ffff:a.b.c.d), IPv4-compatible (::a.b.c.d) and NAT64 (64:ff9b::a.b.c.d) forms. */
    private static boolean embedsIpv4(byte[] b) {
        boolean zeroPrefix = true;
        for (int i = 0; i < 10; i++) {
            zeroPrefix &= b[i] == 0;
        }
        boolean mapped = zeroPrefix && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
        boolean compatible = zeroPrefix && b[10] == 0 && b[11] == 0
                && (b[12] != 0 || b[13] != 0 || b[14] != 0 || (b[15] & 0xff) > 1);
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
        for (int i = 4; nat64 && i < 12; i++) {
            nat64 = b[i] == 0;
        }
        return mapped || compatible || nat64;
    }
}
