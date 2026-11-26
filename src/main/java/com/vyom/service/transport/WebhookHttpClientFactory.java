package com.vyom.service.transport;

import com.vyom.service.SSRFShieldService;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating and managing SSRF-hardened Apache HttpClient 5 instances.
 *
 * Enforces:
 * - Direct invocation of SSRFShieldDnsResolver during connection establishment.
 * - Standard TLS certificate hostname verification (never disabled, uses DefaultHostnameVerifier).
 * - Preservation of logical hostname for TLS SNI and HTTP Host header.
 * - Conservative connection-pooling policy with NoConnectionReuseStrategy to ensure
 *   every outbound delivery validates destination at connect time and cannot bypass checks via socket reuse.
 * - Strictly disabled HTTP redirects (prevents 301/302 SSRF bounce attacks).
 * - Disallowed system proxies (prevents proxy-based destination bypass).
 * - Strict connect (3s) and response (10s) timeouts.
 * - Production streaming response reading bounded to 64 KB via BoundedResponseReader.
 */
@Component
public class WebhookHttpClientFactory {

    public static final int MAX_RESPONSE_BYTES = 64 * 1024; // 64 KB limit
    public static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(3);
    public static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(10);

    private final CloseableHttpClient httpClient;
    private final PoolingHttpClientConnectionManager connectionManager;
    private final SSRFShieldDnsResolver dnsResolver;

    @Autowired
    public WebhookHttpClientFactory(SSRFShieldService ssrfShieldService) {
        this(ssrfShieldService, new SSRFShieldDnsResolver(ssrfShieldService));
    }

    public WebhookHttpClientFactory(SSRFShieldService ssrfShieldService, SSRFShieldDnsResolver dnsResolver) {
        this(ssrfShieldService, dnsResolver, null);
    }

    public WebhookHttpClientFactory(SSRFShieldService ssrfShieldService, SSRFShieldDnsResolver dnsResolver, SSLContext customSslContext) {
        this.dnsResolver = dnsResolver;

        // Strictly enforce DefaultHostnameVerifier; never allow permissive or no-op verifiers
        SSLConnectionSocketFactoryBuilder sslBuilder = SSLConnectionSocketFactoryBuilder.create();
        if (customSslContext != null) {
            sslBuilder.setSslContext(customSslContext);
        } else {
            sslBuilder.setSslContext(SSLContexts.createDefault());
        }
        sslBuilder.setHostnameVerifier(HttpsSupport.getDefaultHostnameVerifier());

        SSLConnectionSocketFactory sslSocketFactory = sslBuilder.build();

        this.connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(this.dnsResolver)
                .setSSLSocketFactory(sslSocketFactory)
                .setDefaultSocketConfig(SocketConfig.custom()
                        .setSoTimeout(RESPONSE_TIMEOUT)
                        .build())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(CONNECT_TIMEOUT)
                        .setSocketTimeout(RESPONSE_TIMEOUT)
                        .setTimeToLive(TimeValue.of(15, TimeUnit.SECONDS))
                        .build())
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(10)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(CONNECT_TIMEOUT)
                .setResponseTimeout(RESPONSE_TIMEOUT)
                .setRedirectsEnabled(false) // Strictly disable redirects
                .build();

        this.httpClient = HttpClients.custom()
                .setConnectionManager(this.connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setConnectionReuseStrategy((request, response, context) -> false) // Close after delivery for per-request destination validation
                .setProxy(null) // Prohibit proxy routing bypass
                .disableCookieManagement()
                .build();
    }

    /**
     * Executes an HTTP request through the production bounded streaming response reader.
     * Enforces the 64 KB limit at consumption time, closing the underlying stream and connection.
     */
    public BoundedResponse execute(ClassicHttpRequest request) throws IOException {
        return this.httpClient.execute(request, BoundedResponseReader.createHandler(MAX_RESPONSE_BYTES));
    }

    public CloseableHttpClient getHttpClient() {
        return this.httpClient;
    }

    public SSRFShieldDnsResolver getDnsResolver() {
        return this.dnsResolver;
    }

    @PreDestroy
    public void shutdown() {
        try {
            this.httpClient.close();
        } catch (IOException ignored) {
        }
        this.connectionManager.close();
    }
}
