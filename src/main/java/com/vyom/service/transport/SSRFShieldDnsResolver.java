package com.vyom.service.transport;

import com.vyom.exception.SSRFProtectionException;
import com.vyom.service.SSRFShieldService;
import org.apache.hc.client5.http.DnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Custom DNS Resolver SPI for Apache HttpClient 5.
 *
 * Implements socket-level IP destination pinning and anti-DNS-rebinding protection:
 * 1. Invoked directly by HttpClient connection operator during connection establishment.
 * 2. Resolves all A and AAAA addresses for the target host.
 * 3. Validates EVERY resolved address against the conservative public unicast SSRF policy.
 * 4. Rejects the host if even one resolved address is non-public, reserved, or prohibited (anti-multi-homing).
 * 5. Returns a single pinned InetAddress to the socket connector, preventing a second DNS resolution (TOCTOU).
 * 6. Preserves the logical hostname for TLS SNI, HTTP Host header, and X.509 certificate hostname verification.
 */
public class SSRFShieldDnsResolver implements DnsResolver {

    private static final Logger log = LoggerFactory.getLogger(SSRFShieldDnsResolver.class);

    @FunctionalInterface
    public interface DnsLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final SSRFShieldService ssrfShieldService;
    private final DnsLookup dnsLookup;

    public SSRFShieldDnsResolver(SSRFShieldService ssrfShieldService) {
        this(ssrfShieldService, InetAddress::getAllByName);
    }

    public SSRFShieldDnsResolver(SSRFShieldService ssrfShieldService, DnsLookup dnsLookup) {
        this.ssrfShieldService = ssrfShieldService;
        this.dnsLookup = dnsLookup;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = dnsLookup.lookup(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("No IP addresses found for host: " + host);
        }

        // Validate EVERY address resolved for this host (anti-multi-homing SSRF)
        for (InetAddress address : addresses) {
            try {
                ssrfShieldService.validateIpAddress(address);
            } catch (SSRFProtectionException e) {
                log.warn("SSRFShield blocked address {} for host {}: {}", address.getHostAddress(), host, e.getMessage());
                throw new UnknownHostException("SSRF blocked destination address for host " + host + ": " + address.getHostAddress());
            }
        }

        // Return single pinned address array to bind the TCP socket without a secondary lookup
        log.debug("SSRFShield pinned host {} to socket IP {}", host, addresses[0].getHostAddress());
        return new InetAddress[] { addresses[0] };
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return host;
    }
}
