package com.vyom.service;

import com.vyom.exception.SSRFProtectionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class SSRFShieldServiceTest {

    private SSRFShieldService ssrfShield;

    @BeforeEach
    void setUp() {
        ssrfShield = new SSRFShieldService();
    }

    @Test
    @DisplayName("Accept valid public routable IPv4 destinations")
    void testValidPublicIpv4() throws UnknownHostException {
        InetAddress googleDns = InetAddress.getByName("8.8.8.8");
        InetAddress cloudflareDns = InetAddress.getByName("1.1.1.1");
        InetAddress quad9 = InetAddress.getByName("9.9.9.9");

        assertThatCode(() -> ssrfShield.validateIpAddress(googleDns)).doesNotThrowAnyException();
        assertThatCode(() -> ssrfShield.validateIpAddress(cloudflareDns)).doesNotThrowAnyException();
        assertThatCode(() -> ssrfShield.validateIpAddress(quad9)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Accept valid public routable IPv6 destinations in 2000::/3")
    void testValidPublicIpv6() throws UnknownHostException {
        InetAddress cloudflareV6 = InetAddress.getByName("2606:4700:4700::1111");
        InetAddress googleV6 = InetAddress.getByName("2001:4860:4860::8888");

        assertThatCode(() -> ssrfShield.validateIpAddress(cloudflareV6)).doesNotThrowAnyException();
        assertThatCode(() -> ssrfShield.validateIpAddress(googleV6)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "0.255.255.255",                   // This host on this network (RFC 1122 0.0.0.0/8)
            "10.0.0.1", "10.254.254.254",                 // Private RFC 1918 (10.0.0.0/8)
            "100.64.0.1", "100.127.255.254",              // Shared CGNAT (RFC 6598 100.64.0.0/10)
            "127.0.0.1", "127.0.0.2", "127.255.255.254", // Loopback (RFC 1122 127.0.0.0/8)
            "169.254.0.1", "169.254.169.254",             // Link-Local & Cloud Metadata (RFC 3927 169.254.0.0/16)
            "172.16.0.1", "172.31.255.254",               // Private RFC 1918 (172.16.0.0/12)
            "192.0.0.1", "192.0.0.170",                   // IETF Protocol Assignments (RFC 6890 192.0.0.0/24)
            "192.0.2.1", "192.0.2.254",                   // Documentation TEST-NET-1 (RFC 5737 192.0.2.0/24)
            "192.88.99.1",                                // 6to4 Relay Anycast (RFC 3068 192.88.99.0/24)
            "192.168.0.1", "192.168.255.254",             // Private RFC 1918 (192.168.0.0/16)
            "198.18.0.1", "198.19.255.254",               // Benchmark network (RFC 2544 198.18.0.0/15)
            "198.51.100.1", "198.51.100.254",             // Documentation TEST-NET-2 (RFC 5737 198.51.100.0/24)
            "203.0.113.1", "203.0.113.254",               // Documentation TEST-NET-3 (RFC 5737 203.0.113.0/24)
            "224.0.0.1", "239.255.255.250",               // Multicast (RFC 1112 224.0.0.0/4)
            "240.0.0.1", "254.255.255.254",               // Reserved for future use (RFC 1112 240.0.0.0/4)
            "255.255.255.255"                             // Limited Broadcast (RFC 8190)
    })
    @DisplayName("Block all IANA IPv4 special-purpose and reserved address spaces")
    void testProhibitedIpv4Destinations(String ipStr) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(ipStr);
        assertThatThrownBy(() -> ssrfShield.validateIpAddress(address))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageMatching(".*(Prohibited|reserved|non-public).*");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "::",                                         // Unspecified (RFC 4291 ::/128)
            "::1",                                        // Loopback (RFC 4291 ::1/128)
            "::ffff:0:0",                                 // IPv4-mapped prefix (RFC 4291 ::ffff:0:0/96)
            "::ffff:0:1",                                 // IPv4-translated (RFC 2765 ::ffff:0:0:0/96)
            "64:ff9b::1",                                 // NAT64 Well-Known Prefix (RFC 6052 64:ff9b::/96)
            "64:ff9b:1::1",                               // Local-Use IPv4/IPv6 Translation (RFC 8215 64:ff9b:1::/48)
            "100::1",                                     // Discard-Only Prefix (RFC 6666 100::/64)
            "2001:2::1",                                  // Benchmarking (RFC 5180 2001:2::/48)
            "2001:20::1",                                 // ORCHIDv2 (RFC 7343 2001:20::/28)
            "2001:db8::1", "2001:db8:ffff::1",            // Documentation (RFC 3849 2001:db8::/32)
            "2002:7f00:1::1", "2002:a00:1::1",            // 6to4 (RFC 3056 2002::/16)
            "2620:4f:8000::1",                            // Direct Delegation AS112 (RFC 7534 2620:4f:8000::/48)
            "fc00::1", "fd12:3456:789a::1",               // Unique Local Address ULA (RFC 4193 fc00::/7)
            "fe80::1", "febf::1",                         // Link-Local Unicast (RFC 4291 fe80::/10)
            "fec0::1",                                    // Site-Local Unicast (deprecated RFC 3879 fec0::/10)
            "ff02::1", "ff05::2"                          // Multicast (RFC 4291 ff00::/8)
    })
    @DisplayName("Block all IANA IPv6 special-purpose, transition, translation, ULA, and multicast destinations")
    void testProhibitedIpv6Destinations(String ipStr) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(ipStr);
        assertThatThrownBy(() -> ssrfShield.validateIpAddress(address))
                .isInstanceOf(SSRFProtectionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4000::1",                                    // Outside 2000::/3 (RFC 4291)
            "8000::1",                                    // Outside 2000::/3
            "c000::1",                                    // Outside 2000::/3
            "e000::1"                                     // Outside 2000::/3
    })
    @DisplayName("Block IPv6 addresses outside Global Unicast space 2000::/3")
    void testNonGlobalUnicastIpv6Blocked(String ipStr) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(ipStr);
        assertThatThrownBy(() -> ssrfShield.validateIpAddress(address))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageContaining("not within global unicast address space");
    }

    @Test
    @DisplayName("Unwrap and block IPv4-mapped IPv6 loopback, private, and metadata destinations")
    void testIpv4MappedIpv6UnwrappedAndBlocked() throws UnknownHostException {
        InetAddress mappedLoopback = InetAddress.getByName("::ffff:127.0.0.1");
        InetAddress mappedMetadata = InetAddress.getByName("::ffff:169.254.169.254");
        InetAddress mappedPrivate = InetAddress.getByName("::ffff:10.0.0.1");

        assertThatThrownBy(() -> ssrfShield.validateIpAddress(mappedLoopback))
                .isInstanceOf(SSRFProtectionException.class);
        assertThatThrownBy(() -> ssrfShield.validateIpAddress(mappedMetadata))
                .isInstanceOf(SSRFProtectionException.class);
        assertThatThrownBy(() -> ssrfShield.validateIpAddress(mappedPrivate))
                .isInstanceOf(SSRFProtectionException.class);
    }

    @Test
    @DisplayName("Unwrap and block IPv4-compatible IPv6 destinations")
    void testIpv4CompatibleIpv6UnwrappedAndBlocked() throws UnknownHostException {
        InetAddress compatibleLoopback = InetAddress.getByName("::127.0.0.1");
        assertThatThrownBy(() -> ssrfShield.validateIpAddress(compatibleLoopback))
                .isInstanceOf(SSRFProtectionException.class);
    }

    @Test
    @DisplayName("Reject scoped IPv6 addresses with non-zero scopeId")
    void testScopedIpv6Rejected() throws UnknownHostException {
        byte[] rawIpv6 = new byte[16];
        rawIpv6[0] = 0x20;
        rawIpv6[1] = 0x01; // 2001::
        Inet6Address scopedAddr = Inet6Address.getByAddress("scoped.test", rawIpv6, 2);

        assertThatThrownBy(() -> ssrfShield.validateIpAddress(scopedAddr))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageContaining("Scoped IPv6 addresses are strictly prohibited");
    }

    @Test
    @DisplayName("Validate URL accepts valid public HTTP and HTTPS URLs")
    void testValidPublicUrls() {
        URI uri1 = ssrfShield.validateUrl("https://api.github.com/webhook");
        assertThat(uri1.getHost()).isEqualTo("api.github.com");

        URI uri2 = ssrfShield.validateUrl("https://cloudflare.com:8443/events");
        assertThat(uri2.getPort()).isEqualTo(8443);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/webhook",
            "file:///etc/passwd",
            "gopher://example.com/",
            "javascript:alert(1)",
            "data:text/plain;base64,SGVsbG8="
    })
    @DisplayName("Reject non-HTTP/HTTPS URL schemes")
    void testRejectInvalidSchemes(String url) {
        assertThatThrownBy(() -> ssrfShield.validateUrl(url))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageContaining("Prohibited URL scheme");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:password@example.com/webhook",
            "https://admin@example.com/webhook",
            "https://attacker.com@victim.com/webhook"
    })
    @DisplayName("Reject URLs with userinfo or credentials")
    void testRejectUserinfo(String url) {
        assertThatThrownBy(() -> ssrfShield.validateUrl(url))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageContaining("userinfo");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/webhook#section",
            "https://example.com/#fragment"
    })
    @DisplayName("Reject URLs with fragments")
    void testRejectFragments(String url) {
        assertThatThrownBy(() -> ssrfShield.validateUrl(url))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageContaining("fragments");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com\\evil/webhook",
            "https://example.com/path\\something"
    })
    @DisplayName("Reject URLs with backslashes")
    void testRejectBackslashes(String url) {
        assertThatThrownBy(() -> ssrfShield.validateUrl(url))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageContaining("backslash");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com:0/webhook",
            "https://example.com:65536/webhook",
            "https://example.com:-1/webhook"
    })
    @DisplayName("Reject URLs with out-of-range port numbers")
    void testRejectInvalidPorts(String url) {
        assertThatThrownBy(() -> ssrfShield.validateUrl(url))
                .isInstanceOf(SSRFProtectionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://0177.0.0.1/webhook",                   // Octal notation for 127.0.0.1
            "http://0177.0000.0000.0001/webhook",          // Multi-octet octal
            "http://0x7f000001/webhook",                   // Hex notation for 127.0.0.1
            "http://2130706433/webhook",                   // Integer/dword notation for 127.0.0.1
            "http://127.1/webhook",                        // 2-part shorthand
            "http://10.1/webhook"                          // 2-part shorthand
    })
    @DisplayName("Reject ambiguous or non-standard IPv4 literals (octal, hex, dword, shorthand)")
    void testRejectAmbiguousIpLiterals(String url) {
        assertThatThrownBy(() -> ssrfShield.validateUrl(url))
                .isInstanceOf(SSRFProtectionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://[fe80::1%eth0]/webhook",
            "https://[fe80::1%25eth0]/webhook",
            "https://[2001:db8::1%1]/webhook"
    })
    @DisplayName("Reject IPv6 zone identifiers in URL host")
    void testRejectIpv6ZoneIdentifiers(String url) {
        assertThatThrownBy(() -> ssrfShield.validateUrl(url))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageContaining("zone identifier");
    }

    @Test
    @DisplayName("Reject IDN hostnames with zero-width or directional override characters")
    void testRejectIdnSpoofing() {
        // Zero-width space (\u200B)
        assertThatThrownBy(() -> ssrfShield.validateUrl("https://exam\u200Bple.com/webhook"))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageContaining("zero-width");

        // Right-to-Left Override (\u202E)
        assertThatThrownBy(() -> ssrfShield.validateUrl("https://exam\u202Eple.com/webhook"))
                .isInstanceOf(SSRFProtectionException.class)
                .hasMessageContaining("directional override");
    }

    @Test
    @DisplayName("Reject host if ANY resolved IP address is prohibited (anti-multi-homing)")
    void testMultiHomedResolutionRejectsIfOneIpIsProhibited() {
        // If a host resolves to 127.0.0.1 (even alongside a public IP), it must be rejected
        assertThatThrownBy(() -> ssrfShield.validateUrl("http://localhost/webhook"))
                .isInstanceOf(SSRFProtectionException.class);
    }
}
