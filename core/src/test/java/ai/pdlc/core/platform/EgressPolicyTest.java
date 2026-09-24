package ai.pdlc.core.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EgressPolicyTest {

    private static InetAddress ip(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** A fake DNS so tests never touch the network. */
    private static EgressPolicy policy(Set<String> allow, Map<String, String[]> dns) {
        return new EgressPolicy(allow, host -> {
            String[] records = dns.get(host.toLowerCase());
            if (records == null) {
                return new InetAddress[0];
            }
            InetAddress[] out = new InetAddress[records.length];
            for (int i = 0; i < records.length; i++) {
                out[i] = ip(records[i]);
            }
            return out;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
            "224.0.0.1", "240.0.0.1", "::1", "::", "fe80::1", "fd00::1", "fc00::1", "ff02::1",
            "::ffff:169.254.169.254", "::ffff:10.0.0.1", "64:ff9b::a9fe:a9fe", "::7f00:1"})
    void rejectsNonPublicAddresses(String address) {
        assertThat(EgressPolicy.blockedReason(ip(address))).as(address).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "100.128.0.1", "2606:4700:4700::1111", "::ffff:8.8.8.8"})
    void allowsPublicAddresses(String address) {
        assertThat(EgressPolicy.blockedReason(ip(address))).as(address).isNull();
    }

    @Test
    void theMetadataServiceIsBlockedByLiteralAndByName() {
        EgressPolicy p = policy(Set.of(), Map.of("metadata.internal", new String[]{"169.254.169.254"}, "169.254.169.254",
                new String[]{"169.254.169.254"}));

        assertThat(p.check(URI.create("http://169.254.169.254/latest/meta-data/")).allowed()).isFalse();
        assertThat(p.check(URI.create("http://metadata.internal/")).reason()).contains("link-local");
    }

    @Test
    void aNameWithOnePublicAndOnePrivateRecordIsDenied() {
        EgressPolicy p = policy(Set.of(), Map.of("mixed.example", new String[]{"93.184.216.34", "10.0.0.5"}));

        EgressPolicy.Decision d = p.check(URI.create("https://mixed.example/x"));

        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("10.0.0.5").contains("private network");
    }

    @Test
    void allowlistedInternalHostsArePermitted() {
        EgressPolicy p = policy(Set.of("Orders.Internal"), Map.of("orders.internal", new String[]{"10.0.0.7"}));

        EgressPolicy.Decision d = p.check(URI.create("https://orders.internal/api"));

        assertThat(d.allowed()).isTrue();
        assertThat(d.addresses()).extracting(InetAddress::getHostAddress).containsExactly("10.0.0.7");
    }

    @Test
    void rejectsOtherSchemesUserinfoAndUnresolvableHosts() {
        EgressPolicy p = policy(Set.of(), Map.of("api.example", new String[]{"93.184.216.34"}));

        assertThat(p.check(URI.create("file:///etc/passwd")).allowed()).isFalse();
        assertThat(p.check(URI.create("ftp://api.example/x")).allowed()).isFalse();
        assertThat(p.check(URI.create("https://user:pw@api.example/x")).allowed()).isFalse();
        assertThat(p.check(URI.create("https://nowhere.example/x")).reason()).contains("does not resolve");
        assertThat(p.check(URI.create("https://api.example/x")).allowed()).isTrue();
    }
}
