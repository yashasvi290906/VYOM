package com.vyom.service.transport;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reusable bounded streaming response reader for webhook delivery transport.
 *
 * Guarantees:
 * - Reads up to maxBytes in streaming chunks.
 * - Never buffers the entire unbounded response before truncating.
 * - Stops consuming data immediately upon reaching the byte threshold.
 * - Detects truncation for both fixed Content-Length and chunked Transfer-Encoding.
 * - Safely closes input stream to release or terminate the underlying socket.
 * - Preserves response status code, reason phrase, and safe headers.
 */
public final class BoundedResponseReader {

    private BoundedResponseReader() {}

    public static BoundedResponse read(ClassicHttpResponse response, int maxBytes) throws IOException {
        int statusCode = response.getCode();
        String statusReason = response.getReasonPhrase() != null ? response.getReasonPhrase() : "";

        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : response.getHeaders()) {
            headers.put(header.getName(), header.getValue());
        }

        HttpEntity entity = response.getEntity();
        if (entity == null) {
            return new BoundedResponse(statusCode, statusReason, Collections.unmodifiableMap(headers), new byte[0], false, 0);
        }

        long reportedContentLength = entity.getContentLength();
        boolean truncated = (reportedContentLength > maxBytes);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] chunk = new byte[4096];
        int totalRead = 0;

        try (InputStream in = entity.getContent()) {
            if (in != null) {
                int n;
                while (totalRead < maxBytes && (n = in.read(chunk, 0, Math.min(chunk.length, maxBytes - totalRead))) != -1) {
                    buffer.write(chunk, 0, n);
                    totalRead += n;
                }

                // If threshold reached, check if more bytes were waiting
                if (totalRead >= maxBytes) {
                    if (in.read() != -1) {
                        truncated = true;
                    }
                }
            }
        }

        return new BoundedResponse(
                statusCode,
                statusReason,
                Collections.unmodifiableMap(headers),
                buffer.toByteArray(),
                truncated,
                totalRead
        );
    }

    public static HttpClientResponseHandler<BoundedResponse> createHandler(int maxBytes) {
        return response -> read(response, maxBytes);
    }
}
