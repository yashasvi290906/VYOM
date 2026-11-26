package com.vyom.service.transport;

import com.vyom.service.SSRFShieldService;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class WebhookTransportSecurityTest {

    private static final File KEYSTORE_DIR = new File("target/test-keystores");
    private static final File MISMATCHED_P12 = new File(KEYSTORE_DIR, "mismatched.p12");
    private static final File MATCHING_P12 = new File(KEYSTORE_DIR, "matching.p12");
    private static final char[] STORE_PASS = "password".toCharArray();

    private HttpServer testHttpServer;
    private int testServerPort;
    private final AtomicInteger acceptedConnections = new AtomicInteger();

    private static class TestSSRFShieldService extends SSRFShieldService {
        @Override
        protected boolean isTestAddressAllowed(InetAddress address) {
            return address.isLoopbackAddress() || "127.0.0.1".equals(address.getHostAddress());
        }
    }

    @BeforeAll
    static void generateKeystoresIfMissing() throws Exception {
        if (!KEYSTORE_DIR.exists()) {
            KEYSTORE_DIR.mkdirs();
        }
        if (!MISMATCHED_P12.exists()) {
            generateKeystore(MISMATCHED_P12, "CN=mismatched.merchant.test", "dns:mismatched.merchant.test");
        }
        if (!MATCHING_P12.exists()) {
            generateKeystore(MATCHING_P12, "CN=correct.merchant.test", "dns:correct.merchant.test");
        }
    }

    private static void generateKeystore(File file, String dname, String san) throws Exception {
        Process process = new ProcessBuilder(
                "keytool",
                "-genkeypair",
                "-alias", "testcert",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-dname", dname,
                "-ext", "SAN=" + san,
                "-validity", "365",
                "-keystore", file.getAbsolutePath(),
                "-storepass", "password",
                "-keypass", "password"
        ).start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        if (!completed || process.exitValue() != 0) {
            throw new IllegalStateException("Failed to generate test keystore: " + file);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        acceptedConnections.set(0);

        testHttpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        testHttpServer.createContext("/webhook", exchange -> {
            acceptedConnections.incrementAndGet();
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        testHttpServer.createContext("/redirect", exchange -> {
            acceptedConnections.incrementAndGet();
            exchange.getResponseHeaders().set("Location", "http://169.254.169.254/latest/meta-data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        testHttpServer.createContext("/huge-chunked", exchange -> {
            acceptedConnections.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 0); // Chunked
            byte[] chunk = new byte[8192];
            for (int i = 0; i < 20; i++) { // ~160 KB
                exchange.getResponseBody().write(chunk);
            }
            exchange.close();
        });
        testHttpServer.createContext("/huge-fixed", exchange -> {
            acceptedConnections.incrementAndGet();
            int size = 100 * 1024; // 100 KB
            byte[] body = new byte[size];
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, size); // Fixed Content-Length
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        testHttpServer.start();
        testServerPort = testHttpServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (testHttpServer != null) {
            testHttpServer.stop(0);
        }
    }

    // =========================================================================
    // 1. ACTUAL LOCAL TLS INTEGRATION TESTS (SNI & HOSTNAME VERIFICATION)
    // =========================================================================

    @Test
    @DisplayName("Actual TLS Handshake: verifies pinned IP, captures SNI, and rejects mismatched certificate")
    void testActualTlsHandshakeVerifiesSniAndRejectsMismatchedCertificate() throws Exception {
        // Server certificate CN is mismatched.merchant.test
        SSLContext serverSslContext = createSslContext(MISMATCHED_P12);
        SSLServerSocketFactory ssf = serverSslContext.getServerSocketFactory();

        AtomicReference<String> serverAcceptedPeerIp = new AtomicReference<>();
        AtomicReference<String> serverCapturedSni = new AtomicReference<>();
        CountDownLatch handshakeAttemptedLatch = new CountDownLatch(1);

        try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int tlsPort = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                    serverAcceptedPeerIp.set(socket.getInetAddress().getHostAddress());
                    socket.startHandshake();
                    SSLSession session = socket.getSession();
                    if (session instanceof ExtendedSSLSession extSession) {
                        List<SNIServerName> names = extSession.getRequestedServerNames();
                        if (names != null && !names.isEmpty()) {
                            for (SNIServerName name : names) {
                                if (name instanceof SNIHostName sniHostName) {
                                    serverCapturedSni.set(sniHostName.getAsciiName());
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Handshake termination expected when client rejects mismatched certificate
                } finally {
                    handshakeAttemptedLatch.countDown();
                }
            });
            serverThread.start();

            // Client trusts test root but uses production DefaultHostnameVerifier
            SSLContext clientTrustContext = createTrustContext(MISMATCHED_P12);
            TestSSRFShieldService shield = new TestSSRFShieldService();
            SSRFShieldDnsResolver.DnsLookup testDns = host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
            SSRFShieldDnsResolver resolver = new SSRFShieldDnsResolver(shield, testDns);
            WebhookHttpClientFactory factory = new WebhookHttpClientFactory(shield, resolver, clientTrustContext);

            try {
                // Client requests target hostname "correct.merchant.test"
                ClassicHttpRequest request = new HttpGet("https://correct.merchant.test:" + tlsPort + "/webhook");

                // Handshake MUST fail because server presents mismatched.merchant.test
                assertThatThrownBy(() -> factory.execute(request))
                        .isInstanceOf(SSLException.class)
                        .hasMessageMatching("(?i).*(Certificate for <correct\\.merchant\\.test> doesn't match|hostname in certificate).*");

                handshakeAttemptedLatch.await(5, TimeUnit.SECONDS);

                // 1. Connection reached the controlled pinned destination IP
                assertThat(serverAcceptedPeerIp.get()).isEqualTo("127.0.0.1");

                // 2. TLS ClientHello contained the original webhook hostname in the SNI extension
                assertThat(serverCapturedSni.get()).isEqualTo("correct.merchant.test");
            } finally {
                factory.shutdown();
                serverThread.join(2000);
            }
        }
    }

    @Test
    @DisplayName("Actual TLS Handshake: succeeds and delivers request when certificate hostname matches target")
    void testActualTlsHandshakeSucceedsWhenHostnameMatches() throws Exception {
        // Server certificate CN is correct.merchant.test
        SSLContext serverSslContext = createSslContext(MATCHING_P12);
        SSLServerSocketFactory ssf = serverSslContext.getServerSocketFactory();

        AtomicReference<String> serverCapturedSni = new AtomicReference<>();
        CountDownLatch requestServedLatch = new CountDownLatch(1);

        try (SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            int tlsPort = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                    socket.startHandshake();
                    SSLSession session = socket.getSession();
                    if (session instanceof ExtendedSSLSession extSession) {
                        List<SNIServerName> names = extSession.getRequestedServerNames();
                        if (names != null && !names.isEmpty()) {
                            serverCapturedSni.set(((SNIHostName) names.get(0)).getAsciiName());
                        }
                    }

                    // Serve simple HTTP 200 response over TLS
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    byte[] buf = new byte[1024];
                    int read = in.read(buf);
                    if (read > 0) {
                    byte[] body = "{\"tls\":\"passed\"}".getBytes(StandardCharsets.UTF_8);
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n" + new String(body, StandardCharsets.UTF_8);
                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    }
                } catch (Exception ignored) {
                } finally {
                    requestServedLatch.countDown();
                }
            });
            serverThread.start();

            SSLContext clientTrustContext = createTrustContext(MATCHING_P12);
            TestSSRFShieldService shield = new TestSSRFShieldService();
            SSRFShieldDnsResolver.DnsLookup testDns = host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
            SSRFShieldDnsResolver resolver = new SSRFShieldDnsResolver(shield, testDns);
            WebhookHttpClientFactory factory = new WebhookHttpClientFactory(shield, resolver, clientTrustContext);

            try {
                ClassicHttpRequest request = new HttpGet("https://correct.merchant.test:" + tlsPort + "/webhook");
                BoundedResponse response = factory.execute(request);

                requestServedLatch.await(5, TimeUnit.SECONDS);

                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.getBodyAsString()).isEqualTo("{\"tls\":\"passed\"}");
                assertThat(serverCapturedSni.get()).isEqualTo("correct.merchant.test");
            } finally {
                factory.shutdown();
                serverThread.join(2000);
            }
        }
    }

    // =========================================================================
    // 2. PRODUCTION EXECUTION PATH RESPONSE-READER INTEGRATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Production execution path: truncates chunked response exceeding 64 KB and terminates consumption")
    void testProductionExecutionPathTruncatesChunkedStreamAt64KB() throws Exception {
        TestSSRFShieldService shield = new TestSSRFShieldService();
        SSRFShieldDnsResolver.DnsLookup testDns = host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
        SSRFShieldDnsResolver resolver = new SSRFShieldDnsResolver(shield, testDns);
        WebhookHttpClientFactory factory = new WebhookHttpClientFactory(shield, resolver);

        try {
            ClassicHttpRequest request = new HttpGet("http://webhook.merchant-destination.test:" + testServerPort + "/huge-chunked");
            BoundedResponse response = factory.execute(request);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.truncated()).isTrue();
            assertThat(response.bytesRead()).isEqualTo(WebhookHttpClientFactory.MAX_RESPONSE_BYTES);
            assertThat(response.body().length).isEqualTo(64 * 1024);
        } finally {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("Production execution path: truncates fixed Content-Length response exceeding 64 KB")
    void testProductionExecutionPathTruncatesFixedContentLengthAt64KB() throws Exception {
        TestSSRFShieldService shield = new TestSSRFShieldService();
        SSRFShieldDnsResolver.DnsLookup testDns = host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
        SSRFShieldDnsResolver resolver = new SSRFShieldDnsResolver(shield, testDns);
        WebhookHttpClientFactory factory = new WebhookHttpClientFactory(shield, resolver);

        try {
            ClassicHttpRequest request = new HttpGet("http://webhook.merchant-destination.test:" + testServerPort + "/huge-fixed");
            BoundedResponse response = factory.execute(request);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.truncated()).isTrue();
            assertThat(response.bytesRead()).isEqualTo(WebhookHttpClientFactory.MAX_RESPONSE_BYTES);
            assertThat(response.body().length).isEqualTo(64 * 1024);
        } finally {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("Production execution path: processes standard payload without truncation and closes cleanly")
    void testProductionExecutionPathProcessesNormalPayloadWithoutTruncation() throws Exception {
        TestSSRFShieldService shield = new TestSSRFShieldService();
        SSRFShieldDnsResolver.DnsLookup testDns = host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
        SSRFShieldDnsResolver resolver = new SSRFShieldDnsResolver(shield, testDns);
        WebhookHttpClientFactory factory = new WebhookHttpClientFactory(shield, resolver);

        try {
            ClassicHttpRequest request = new HttpGet("http://webhook.merchant-destination.test:" + testServerPort + "/webhook");
            BoundedResponse response = factory.execute(request);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.truncated()).isFalse();
            assertThat(response.getBodyAsString()).isEqualTo("{\"status\":\"ok\"}");
        } finally {
            factory.shutdown();
        }
    }

    // =========================================================================
    // 3. SOCKET PINNING, ANTI-REBINDING, REDIRECT, AND CONNECTION REUSE TESTS
    // =========================================================================

    @Test
    @DisplayName("Socket-level pinning: connects to pinned IP returned by resolver and preserves Host header")
    void testSocketLevelDnsPinningToRealListener() throws Exception {
        TestSSRFShieldService shield = new TestSSRFShieldService();
        SSRFShieldDnsResolver.DnsLookup testDns = host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
        SSRFShieldDnsResolver resolver = new SSRFShieldDnsResolver(shield, testDns);
        WebhookHttpClientFactory factory = new WebhookHttpClientFactory(shield, resolver);

        AtomicReference<String> receivedHostHeader = new AtomicReference<>();
        AtomicReference<String> actualPeerIp = new AtomicReference<>();

        try (ServerSocket realSocketListener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            int port = realSocketListener.getLocalPort();

            Thread listenerThread = new Thread(() -> {
                try (Socket socket = realSocketListener.accept()) {
                    actualPeerIp.set(socket.getInetAddress().getHostAddress());
                    InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream();
                    byte[] buffer = new byte[2048];
                    int read = in.read(buffer);
                    if (read > 0) {
                        String httpRequest = new String(buffer, 0, read, StandardCharsets.UTF_8);
                        for (String line : httpRequest.split("\r\n")) {
                            if (line.toLowerCase().startsWith("host:")) {
                                receivedHostHeader.set(line.substring(5).trim());
                            }
                        }
                        String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{\"status\":\"ok\"}";
                        out.write(response.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            });
            listenerThread.start();

            ClassicHttpRequest request = new HttpGet("http://webhook.merchant-destination.test:" + port + "/webhook");
            BoundedResponse response = factory.execute(request);
            listenerThread.join(2000);

            // 1. Connection reached the real TCP socket
            assertThat(actualPeerIp.get()).isEqualTo("127.0.0.1");

            // 2. Logical hostname was preserved for HTTP Host header
            assertThat(receivedHostHeader.get()).isEqualTo("webhook.merchant-destination.test:" + port);

            // 3. Response was processed through BoundedResponseReader
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.getBodyAsString()).isEqualTo("{\"status\":\"ok\"}");
        } finally {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("Anti-DNS-rebinding: connection fails before TCP socket when IP rebinds to prohibited metadata")
    void testAntiDnsRebindingAtConnectionEstablishment() throws Exception {
        SSRFShieldService productionShield = new SSRFShieldService();

        // Simulate DNS rebinding: Preliminary check passed, but connect-time DNS returns cloud metadata
        SSRFShieldDnsResolver.DnsLookup reboundDns = host -> new InetAddress[] { InetAddress.getByName("169.254.169.254") };

        SSRFShieldDnsResolver resolver = new SSRFShieldDnsResolver(productionShield, reboundDns);
        WebhookHttpClientFactory factory = new WebhookHttpClientFactory(productionShield, resolver);

        try {
            ClassicHttpRequest request = new HttpGet("http://attacker-controlled-rebinding.com:" + testServerPort + "/webhook");

            // Connection MUST fail at connect time without opening socket
            assertThatThrownBy(() -> factory.execute(request))
                    .isInstanceOf(UnknownHostException.class)
                    .hasMessageContaining("SSRF blocked destination address");

            // HTTP test server should have received ZERO connections
            assertThat(acceptedConnections.get()).isEqualTo(0);
        } finally {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("Connection reuse disabled: consecutive requests open fresh TCP connections")
    void testNoConnectionReuseEnforcesPerRequestValidation() throws Exception {
        TestSSRFShieldService shield = new TestSSRFShieldService();
        SSRFShieldDnsResolver.DnsLookup testDns = host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
        SSRFShieldDnsResolver resolver = new SSRFShieldDnsResolver(shield, testDns);
        WebhookHttpClientFactory factory = new WebhookHttpClientFactory(shield, resolver);

        try {
            ClassicHttpRequest req1 = new HttpGet("http://merchant1.test:" + testServerPort + "/webhook");
            BoundedResponse resp1 = factory.execute(req1);
            assertThat(resp1.statusCode()).isEqualTo(200);
            assertThat(acceptedConnections.get()).isEqualTo(1);

            ClassicHttpRequest req2 = new HttpGet("http://merchant1.test:" + testServerPort + "/webhook");
            BoundedResponse resp2 = factory.execute(req2);
            assertThat(resp2.statusCode()).isEqualTo(200);

            // Proves a second distinct TCP connection was established
            assertThat(acceptedConnections.get()).isEqualTo(2);
        } finally {
            factory.shutdown();
        }
    }

    @Test
    @DisplayName("HttpClient strictly disables redirects to prevent 302 SSRF bounce attacks")
    void testRedirectDisabled() throws Exception {
        TestSSRFShieldService shield = new TestSSRFShieldService();
        SSRFShieldDnsResolver.DnsLookup testDns = host -> new InetAddress[] { InetAddress.getByName("127.0.0.1") };
        SSRFShieldDnsResolver resolver = new SSRFShieldDnsResolver(shield, testDns);
        WebhookHttpClientFactory factory = new WebhookHttpClientFactory(shield, resolver);

        try {
            ClassicHttpRequest request = new HttpGet("http://redirect.test:" + testServerPort + "/redirect");
            BoundedResponse response = factory.execute(request);

            // Must return 302 directly with Location header preserved; never follow redirect
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers()).containsEntry("Location", "http://169.254.169.254/latest/meta-data");
        } finally {
            factory.shutdown();
        }
    }

    // =========================================================================
    // HELPER METHODS FOR TLS CONTEXTS
    // =========================================================================

    private static SSLContext createSslContext(File p12File) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(p12File)) {
            ks.load(in, STORE_PASS);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, STORE_PASS);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private static SSLContext createTrustContext(File p12File) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(p12File)) {
            ks.load(in, STORE_PASS);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }
}
