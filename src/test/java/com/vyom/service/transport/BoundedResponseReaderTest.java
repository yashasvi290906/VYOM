package com.vyom.service.transport;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

public class BoundedResponseReaderTest {

    private static final int MAX_BYTES = 64 * 1024; // 64 KB

    @Test
    @DisplayName("Read payload smaller than 64 KB without truncation")
    void testReadSmallPayload() throws IOException {
        String content = "{\"status\":\"ok\",\"processed\":true}";
        ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        response.setHeader("Content-Type", "application/json");
        response.setEntity(new StringEntity(content, ContentType.APPLICATION_JSON));

        BoundedResponse result = BoundedResponseReader.read(response, MAX_BYTES);

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.statusReason()).isEqualTo("OK");
        assertThat(result.truncated()).isFalse();
        assertThat(result.bytesRead()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.getBodyAsString()).isEqualTo(content);
        assertThat(result.headers()).containsEntry("Content-Type", "application/json");
    }

    @Test
    @DisplayName("Read payload exactly 64 KB without truncation")
    void testReadExactlyMaxBytes() throws IOException {
        byte[] payload = new byte[MAX_BYTES];
        Arrays.fill(payload, (byte) 'A');

        ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        response.setEntity(new ByteArrayEntity(payload, ContentType.APPLICATION_OCTET_STREAM));

        BoundedResponse result = BoundedResponseReader.read(response, MAX_BYTES);

        assertThat(result.truncated()).isFalse();
        assertThat(result.bytesRead()).isEqualTo(MAX_BYTES);
        assertThat(result.body()).hasSize(MAX_BYTES);
        assertThat(result.body()).isEqualTo(payload);
    }

    @Test
    @DisplayName("Truncate fixed Content-Length payload exceeding 64 KB at exactly 64 KB")
    void testTruncateFixedContentLengthExceedingMax() throws IOException {
        int originalSize = 100 * 1024; // 100 KB
        byte[] payload = new byte[originalSize];
        Arrays.fill(payload, (byte) 'B');

        ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        response.setEntity(new ByteArrayEntity(payload, ContentType.APPLICATION_OCTET_STREAM));

        BoundedResponse result = BoundedResponseReader.read(response, MAX_BYTES);

        assertThat(result.truncated()).isTrue();
        assertThat(result.bytesRead()).isEqualTo(MAX_BYTES);
        assertThat(result.body()).hasSize(MAX_BYTES);
        // Verify buffer has exactly first 64 KB of 'B'
        assertThat(result.body()[0]).isEqualTo((byte) 'B');
        assertThat(result.body()[MAX_BYTES - 1]).isEqualTo((byte) 'B');
    }

    @Test
    @DisplayName("Truncate chunked streaming payload without Content-Length exceeding 64 KB")
    void testTruncateChunkedStreamExceedingMax() throws IOException {
        int originalSize = 128 * 1024; // 128 KB
        byte[] payload = new byte[originalSize];
        Arrays.fill(payload, (byte) 'C');

        ByteArrayInputStream in = new ByteArrayInputStream(payload);
        // InputStreamEntity with -1 length indicates chunked / unknown length stream
        InputStreamEntity chunkedEntity = new InputStreamEntity(in, -1, ContentType.APPLICATION_OCTET_STREAM);

        ClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        response.setEntity(chunkedEntity);

        BoundedResponse result = BoundedResponseReader.read(response, MAX_BYTES);

        assertThat(result.truncated()).isTrue();
        assertThat(result.bytesRead()).isEqualTo(MAX_BYTES);
        assertThat(result.body()).hasSize(MAX_BYTES);
        assertThat(result.body()[0]).isEqualTo((byte) 'C');
    }

    @Test
    @DisplayName("Handle null entity or empty body correctly")
    void testEmptyOrNullEntity() throws IOException {
        ClassicHttpResponse responseNoEntity = new BasicClassicHttpResponse(204, "No Content");
        BoundedResponse resultNoEntity = BoundedResponseReader.read(responseNoEntity, MAX_BYTES);

        assertThat(resultNoEntity.statusCode()).isEqualTo(204);
        assertThat(resultNoEntity.truncated()).isFalse();
        assertThat(resultNoEntity.bytesRead()).isEqualTo(0);
        assertThat(resultNoEntity.body()).isEmpty();
        assertThat(resultNoEntity.getBodyAsString()).isEmpty();

        ClassicHttpResponse responseEmptyEntity = new BasicClassicHttpResponse(200, "OK");
        responseEmptyEntity.setEntity(new ByteArrayEntity(new byte[0], ContentType.APPLICATION_OCTET_STREAM));
        BoundedResponse resultEmpty = BoundedResponseReader.read(responseEmptyEntity, MAX_BYTES);

        assertThat(resultEmpty.statusCode()).isEqualTo(200);
        assertThat(resultEmpty.truncated()).isFalse();
        assertThat(resultEmpty.bytesRead()).isEqualTo(0);
        assertThat(resultEmpty.body()).isEmpty();
    }

    @Test
    @DisplayName("Preserve response status code, reason phrase, and headers")
    void testPreserveMetadata() throws IOException {
        ClassicHttpResponse response = new BasicClassicHttpResponse(404, "Not Found");
        response.setHeader("X-Custom-Header", "Value123");
        response.setHeader("Content-Type", "text/plain");
        response.setEntity(new StringEntity("Resource not found", ContentType.TEXT_PLAIN));

        BoundedResponse result = BoundedResponseReader.read(response, MAX_BYTES);

        assertThat(result.statusCode()).isEqualTo(404);
        assertThat(result.statusReason()).isEqualTo("Not Found");
        assertThat(result.headers()).containsEntry("X-Custom-Header", "Value123");
        assertThat(result.headers()).containsEntry("Content-Type", "text/plain");
        assertThat(result.getBodyAsString()).isEqualTo("Resource not found");
    }
}
