package com.vyom.service;

import com.vyom.exception.SSRFProtectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SSRF Protection Shield Service.
 *
 * Implements a conservative public-unicast destination policy based on:
 * 1. IANA IPv4 Special-Purpose Address Registry (RFC 6890, updated 2023-04-14).
 * 2. IANA IPv6 Special-Purpose Address Registry (RFC 6890, RFC 8215, RFC 7335, RFC 8190,
 *    RFC 6052, RFC 4291, RFC 3849, RFC 4193, RFC 6666, updated 2023-04-14).
 *
 * Enforces:
 * - Rejection of all private, loopback, link-local, cloud metadata, multicast, and reserved ranges.
 * - IPv6 Global Unicast validation (RFC 4291 2000::/3) with special-purpose exclusion.
 * - Rejection of IPv6 transition and translation mechanisms (6to4, NAT64, local-use translation).
 * - IPv4-mapped (::ffff:0:0/96) and IPv4-compatible (::/96) address unwrapping and strict evaluation.
 * - Rejection of scoped IPv6 addresses and IPv6 zone identifiers.
 * - Rejection of userinfo, fragments, backslashes, ambiguous IP literals (octal/hex/dword), and IDN spoofing.
 * - Comprehensive multi-homed destination validation (one invalid IP rejects the entire host).
 */
@Service
public class SSRFShieldService {

    private static final Logger log = LoggerFactory.getLogger(SSRFShieldService.class);

    // RFC 1035 / RFC 1123 DNS hostname pattern
    private static final Pattern HOSTNAME_LABEL_PATTERN = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");
    private static final Pattern STRICT_IPV4_PATTERN = Pattern.compile("^([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})$");

    private final List<IpRange> blockedIpv4Ranges = new ArrayList<>();
    private final List<IpRange> blockedIpv6Ranges = new ArrayList<>();
    private IpRange ipv6GlobalUnicastRange;

    public SSRFShieldService() {
        initRegistryRanges();
    }

    private void initRegistryRanges() {
        // --- IANA IPv4 Special-Purpose Address Registry (RFC 6890) ---
        addIpv4Range("0.0.0.0", 8);         // "This host on this network" (RFC 1122)
        addIpv4Range("10.0.0.0", 8);        // Private-Use (RFC 1918)
        addIpv4Range("100.64.0.0", 10);     // Shared Address Space / CGNAT (RFC 6598)
        addIpv4Range("127.0.0.0", 8);       // Loopback (RFC 1122)
        addIpv4Range("169.254.0.0", 16);    // Link-Local & Cloud Metadata 169.254.169.254 (RFC 3927)
        addIpv4Range("172.16.0.0", 12);     // Private-Use (RFC 1918)
        addIpv4Range("192.0.0.0", 24);      // IETF Protocol Assignments (RFC 6890)
        addIpv4Range("192.0.2.0", 24);      // Documentation TEST-NET-1 (RFC 5737)
        addIpv4Range("192.88.99.0", 24);    // 6to4 Relay Anycast (RFC 3068, deprecated RFC 7526)
        addIpv4Range("192.168.0.0", 16);    // Private-Use (RFC 1918)
        addIpv4Range("198.18.0.0", 15);     // Benchmarking (RFC 2544, RFC 5180)
        addIpv4Range("198.51.100.0", 24);   // Documentation TEST-NET-2 (RFC 5737)
        addIpv4Range("203.0.113.0", 24);    // Documentation TEST-NET-3 (RFC 5737)
        addIpv4Range("224.0.0.0", 4);       // Multicast (RFC 1112)
        addIpv4Range("240.0.0.0", 4);       // Reserved for Future Use (RFC 1112)
        addIpv4Range("255.255.255.255", 32);// Limited Broadcast (RFC 8190, RFC 919)

        // --- IANA IPv6 Special-Purpose Address Registry (RFC 6890) ---
        addIpv6Range("::", 128);            // Unspecified Address (RFC 4291)
        addIpv6Range("::1", 128);           // Loopback Address (RFC 4291)
        addIpv6Range("::", 96);             // IPv4-Compatible (RFC 4291, deprecated)
        addIpv6Range("::ffff:0:0", 96);      // IPv4-Mapped (RFC 4291)
        addIpv6Range("::ffff:0:0:0", 96);    // IPv4-Translated (RFC 2765, RFC 6145)
        addIpv6Range("64:ff9b::", 96);       // IPv4/IPv6 Translation NAT64 (RFC 6052)
        addIpv6Range("64:ff9b:1::", 48);     // Local-Use IPv4/IPv6 Translation (RFC 8215)
        addIpv6Range("100::", 64);          // Discard-Only Prefix (RFC 6666)
        addIpv6Range("2001::", 23);         // IETF Protocol Assignments (RFC 2928) - includes Teredo 2001::/32
        addIpv6Range("2001:2::", 48);       // Benchmarking (RFC 5180)
        addIpv6Range("2001:20::", 28);      // ORCHIDv2 (RFC 7343)
        addIpv6Range("2001:db8::", 32);     // Documentation (RFC 3849)
        addIpv6Range("2002::", 16);         // 6to4 (RFC 3056)
        addIpv6Range("2620:4f:8000::", 48); // Direct Delegation AS112 (RFC 7534)
        addIpv6Range("fc00::", 7);          // Unique Local Address ULA (RFC 4193)
        addIpv6Range("fe80::", 10);         // Link-Local Unicast (RFC 4291)
        addIpv6Range("fec0::", 10);         // Site-Local Unicast (RFC 3879, deprecated)
        addIpv6Range("ff00::", 8);          // Multicast (RFC 4291)

        // RFC 4291 Section 2.4: IPv6 Global Unicast space is 2000::/3
        this.ipv6GlobalUnicastRange = createRange("2000::", 3, 16);
    }

    private void addIpv4Range(String ipStr, int prefixLength) {
        blockedIpv4Ranges.add(createRange(ipStr, prefixLength, 4));
    }

    private void addIpv6Range(String ipStr, int prefixLength) {
        blockedIpv6Ranges.add(createRange(ipStr, prefixLength, 16));
    }

    private IpRange createRange(String ipStr, int prefixLength, int expectedByteLength) {
        try {
            InetAddress address = InetAddress.getByName(ipStr);
            byte[] raw = address.getAddress();
            if (raw.length == 4 && expectedByteLength == 16) {
                // Java parses IPv4-mapped IPv6 literals (e.g. ::ffff:0:0) into an Inet4Address; expand to 16 bytes
                byte[] v6 = new byte[16];
                v6[10] = (byte) 0xff;
                v6[11] = (byte) 0xff;
                System.arraycopy(raw, 0, v6, 12, 4);
                raw = v6;
            }
            if (raw.length != expectedByteLength) {
                throw new IllegalStateException("Unexpected byte length for " + ipStr + ": " + raw.length);
            }
            int bitLength = raw.length * 8;
            BigInteger ipNum = new BigInteger(1, raw);

            BigInteger mask = BigInteger.ONE.shiftLeft(bitLength - prefixLength).subtract(BigInteger.ONE);
            BigInteger start = ipNum.and(mask.not());
            BigInteger end = start.or(mask);

            return new IpRange(start, end, raw.length, ipStr + "/" + prefixLength);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Failed to initialize CIDR range: " + ipStr + "/" + prefixLength, e);
        }
    }

    /**
     * Validates a destination Webhook URL against SSRF and destination policies.
     * Hardens URL parsing against malformed authorities, userinfo, fragments,
     * invalid ports, IPv6 zone identifiers, ambiguous IP literals, and IDN edge cases.
     *
     * @param urlString The raw URL string to validate.
     * @return The parsed and validated URI.
     * @throws SSRFProtectionException If any validation check fails.
     */
    public URI validateUrl(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new SSRFProtectionException("Webhook URL cannot be null or blank");
        }

        String raw = urlString.trim();

        // Check for ASCII control characters or whitespace
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F || Character.isWhitespace(c)) {
                throw new SSRFProtectionException("URL contains invalid control characters or whitespace");
            }
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' ||
                (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')) {
                throw new SSRFProtectionException("URL contains prohibited zero-width or directional override characters");
            }
        }

        // Prohibit backslashes (prevent URL parser ambiguity)
        if (raw.contains("\\")) {
            throw new SSRFProtectionException("URL contains backslash characters which are prohibited");
        }

        // Prohibit fragments (webhooks deliver to resource paths, fragments are client-only)
        if (raw.contains("#")) {
            throw new SSRFProtectionException("URL fragments ('#') are not permitted for webhook endpoints");
        }

        // Prohibit credentials / userinfo
        if (raw.contains("@")) {
            throw new SSRFProtectionException("URLs with userinfo (credentials '@') are prohibited for security");
        }

        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new SSRFProtectionException("Invalid Webhook URL syntax: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new SSRFProtectionException("Prohibited URL scheme '" + scheme + "'. Only HTTP and HTTPS are permitted.");
        }

        if (uri.getUserInfo() != null) {
            throw new SSRFProtectionException("URLs with userinfo are prohibited.");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SSRFProtectionException("URL must contain a valid host name");
        }

        // Validate port
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new SSRFProtectionException("Invalid port number: " + port);
        }

        // Validate host syntax (IPv6 zone identifiers, ambiguous IP literals, IDN)
        validateHostSyntax(host);

        // Resolve and validate all IP addresses for this host
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses == null || addresses.length == 0) {
                throw new SSRFProtectionException("Host could not be resolved: " + host);
            }

            for (InetAddress address : addresses) {
                validateIpAddress(address);
            }
        } catch (UnknownHostException e) {
            throw new SSRFProtectionException("DNS resolution failed for host: " + host);
        }

        return uri;
    }

    /**
     * Hardens host syntax against ambiguous IP literals, IPv6 zone identifiers, and IDN issues.
     */
    private void validateHostSyntax(String host) {
        // Reject IPv6 scoped zone identifiers (e.g. fe80::1%eth0 or %25)
        if (host.contains("%")) {
            throw new SSRFProtectionException("IPv6 zone identifiers are prohibited in host: " + host);
        }

        // Strip IPv6 brackets if present for evaluation
        String strippedHost = host;
        if (host.startsWith("[") && host.endsWith("]")) {
            strippedHost = host.substring(1, host.length() - 1);
            if (strippedHost.contains("%")) {
                throw new SSRFProtectionException("IPv6 zone identifiers are prohibited in host");
            }
        }

        // Ambiguous IPv4 literals check (octal leading zeros, hex, dword, shorthand)
        if (looksLikeNumericIp(strippedHost)) {
            validateStrictIpv4Literal(strippedHost);
        }

        // IDN validation and normalization
        validateIdn(strippedHost);
    }

    /**
     * Checks if a string looks like a numeric IP representation (digits, dots, or hex prefix).
     */
    private boolean looksLikeNumericIp(String host) {
        if (host.startsWith("0x") || host.startsWith("0X")) {
            return true;
        }
        boolean hasDot = false;
        boolean allDigitsOrDots = true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                hasDot = true;
            } else if (!Character.isDigit(c)) {
                allDigitsOrDots = false;
                break;
            }
        }
        return (allDigitsOrDots && hasDot) || (allDigitsOrDots && host.length() > 0 && !host.contains(":"));
    }

    /**
     * Validates that an IPv4 literal is in strict decimal dotted-quad format (no octal, hex, or dword).
     */
    private void validateStrictIpv4Literal(String host) {
        var matcher = STRICT_IPV4_PATTERN.matcher(host);
        if (!matcher.matches()) {
            throw new SSRFProtectionException("Ambiguous or invalid numeric IP literal: " + host);
        }

        for (int i = 1; i <= 4; i++) {
            String octetStr = matcher.group(i);
            // Octal check: leading zero on multi-digit octet
            if (octetStr.length() > 1 && octetStr.startsWith("0")) {
                throw new SSRFProtectionException("Ambiguous octal IP literal notation is prohibited: " + host);
            }
            int val = Integer.parseInt(octetStr);
            if (val > 255) {
                throw new SSRFProtectionException("IPv4 octet exceeds 255: " + val);
            }
        }
    }

    /**
     * Validates Internationalized Domain Names (IDN) against spoofing and invisible characters.
     */
    private void validateIdn(String host) {
        // If it's an IPv6 address, skip DNS hostname syntax check
        if (host.contains(":")) {
            return;
        }

        // Check for zero-width, formatting, or bidirectional override characters
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' ||
                (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')) {
                throw new SSRFProtectionException("Hostname contains prohibited zero-width or directional override characters");
            }
        }

        String asciiHost;
        try {
            asciiHost = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException e) {
            throw new SSRFProtectionException("Hostname violates IDN standard rules: " + e.getMessage());
        }

        if (asciiHost.length() > 253) {
            throw new SSRFProtectionException("Hostname length exceeds 253 characters");
        }

        // Validate each label against RFC 1035
        String[] labels = asciiHost.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty()) {
                throw new SSRFProtectionException("Hostname contains empty label");
            }
            if (label.length() > 63) {
                throw new SSRFProtectionException("Hostname label exceeds 63 characters: " + label);
            }
            if (!HOSTNAME_LABEL_PATTERN.matcher(label).matches()) {
                throw new SSRFProtectionException("Hostname label contains invalid characters: " + label);
            }
        }
    }

    /**
     * Validates an IP address against IANA special-purpose and reserved address registries.
     * Enforces public unicast destinations only.
     *
     * @param address The resolved InetAddress to validate.
     * @throws SSRFProtectionException If the address is non-global, private, reserved, or special-purpose.
     */
    public void validateIpAddress(InetAddress address) {
        if (address == null) {
            throw new SSRFProtectionException("Destination IP address cannot be null");
        }

        // 1. Scoped IPv6 rejection (scopeId != 0 or scopedInterface != null)
        if (address instanceof Inet6Address ip6) {
            if (ip6.getScopeId() != 0 || ip6.getScopedInterface() != null) {
                throw new SSRFProtectionException("Scoped IPv6 addresses are strictly prohibited: " + address.getHostAddress());
            }
        }

        // 2. Normalize and unwrap IPv4-mapped (::ffff:0:0/96) and IPv4-compatible (::/96)
        InetAddress normalized = normalizeIpAddress(address);

        // 3. Test hook: Allows test harness to exercise local sockets without production-accessible bypass
        if (isTestAddressAllowed(normalized)) {
            return;
        }

        // 4. Standard Java categorization checks
        if (normalized.isAnyLocalAddress() ||
            normalized.isLoopbackAddress() ||
            normalized.isLinkLocalAddress() ||
            normalized.isSiteLocalAddress() ||
            normalized.isMulticastAddress()) {
            throw new SSRFProtectionException("Prohibited non-public IP destination: " + normalized.getHostAddress());
        }

        byte[] raw = normalized.getAddress();
        BigInteger targetNum = new BigInteger(1, raw);

        // 5. IPv4 Evaluation
        if (raw.length == 4) {
            for (IpRange range : blockedIpv4Ranges) {
                if (range.contains(targetNum)) {
                    log.warn("SSRFShield blocked IPv4 {} matching range {}", normalized.getHostAddress(), range.description);
                    throw new SSRFProtectionException("Prohibited non-public IPv4 destination: " + normalized.getHostAddress());
                }
            }
            return;
        }

        // 6. IPv6 Evaluation
        if (raw.length == 16) {
            // Check explicit blocked special-purpose ranges
            for (IpRange range : blockedIpv6Ranges) {
                if (range.contains(targetNum)) {
                    log.warn("SSRFShield blocked IPv6 {} matching range {}", normalized.getHostAddress(), range.description);
                    throw new SSRFProtectionException("Prohibited non-public IPv6 destination: " + normalized.getHostAddress());
                }
            }

            // Must be within IPv6 Global Unicast space (RFC 4291 2000::/3)
            if (!ipv6GlobalUnicastRange.contains(targetNum)) {
                log.warn("SSRFShield blocked non-global IPv6 destination: {}", normalized.getHostAddress());
                throw new SSRFProtectionException("IPv6 destination is not within global unicast address space: " + normalized.getHostAddress());
            }
        }
    }

    /**
     * Unwraps IPv4-mapped IPv6 addresses (::ffff:x.x.x.x) and IPv4-compatible (::x.x.x.x) into native IPv4 addresses.
     */
    public InetAddress normalizeIpAddress(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length == 16) {
            // Check IPv4-mapped: 10 zero bytes + 2 0xFF bytes + 4 bytes IPv4
            boolean isV4Mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    isV4Mapped = false;
                    break;
                }
            }
            if (isV4Mapped && raw[10] == (byte) 0xff && raw[11] == (byte) 0xff) {
                try {
                    return InetAddress.getByAddress(Arrays.copyOfRange(raw, 12, 16));
                } catch (UnknownHostException ignored) {
                }
            }

            // Check IPv4-compatible: 12 zero bytes + 4 bytes IPv4 (excluding ::0 and ::1)
            boolean isV4Compatible = true;
            for (int i = 0; i < 12; i++) {
                if (raw[i] != 0) {
                    isV4Compatible = false;
                    break;
                }
            }
            if (isV4Compatible && (raw[12] != 0 || raw[13] != 0 || raw[14] != 0 || (raw[15] != 0 && raw[15] != 1))) {
                try {
                    return InetAddress.getByAddress(Arrays.copyOfRange(raw, 12, 16));
                } catch (UnknownHostException ignored) {
                }
            }
        }
        return address;
    }

    /**
     * Test-only extension hook. Defaults to false in production code.
     * Cannot be enabled via configuration or public methods. Only test subclasses can override.
     */
    protected boolean isTestAddressAllowed(InetAddress address) {
        return false;
    }

    private static class IpRange {
        final BigInteger start;
        final BigInteger end;
        final int byteLength;
        final String description;

        IpRange(BigInteger start, BigInteger end, int byteLength, String description) {
            this.start = start;
            this.end = end;
            this.byteLength = byteLength;
            this.description = description;
        }

        boolean contains(BigInteger target) {
            return target.compareTo(start) >= 0 && target.compareTo(end) <= 0;
        }
    }
}
