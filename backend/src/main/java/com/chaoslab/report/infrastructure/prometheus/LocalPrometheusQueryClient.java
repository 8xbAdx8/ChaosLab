package com.chaoslab.report.infrastructure.prometheus;

import com.chaoslab.report.application.port.PrometheusQueryClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Component
public class LocalPrometheusQueryClient implements PrometheusQueryClient {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI baseUri;

    @Autowired
    public LocalPrometheusQueryClient(
            ObjectMapper mapper,
            @Value("${chaoslab.report.prometheus-url:http://127.0.0.1:19090}") String url
    ) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build(), mapper, URI.create(url));
    }

    LocalPrometheusQueryClient(HttpClient client, ObjectMapper mapper, URI baseUri) {
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
        this.baseUri = Objects.requireNonNull(baseUri);
        if (!"http".equals(baseUri.getScheme())
                || !("127.0.0.1".equals(baseUri.getHost())
                || "localhost".equals(baseUri.getHost()))
                || baseUri.getUserInfo() != null
                || !(baseUri.getPath().isEmpty() || "/".equals(baseUri.getPath()))
                || baseUri.getQuery() != null || baseUri.getFragment() != null
                || baseUri.getPort() < 1) {
            throw new IllegalArgumentException("report Prometheus URL must be a local HTTP root URL");
        }
    }

    @Override
    public Double query(String expression, Instant at) {
        String parameters = "query=" + URLEncoder.encode(expression, StandardCharsets.UTF_8)
                + "&time=" + (at.getEpochSecond() + at.getNano() / 1_000_000_000.0);
        URI uri = URI.create(baseUri.toString().replaceAll("/$", "")
                + "/api/v1/query?" + parameters);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream()
            );
            try (InputStream body = response.body()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.statusCode() != 200 || bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("Prometheus response is invalid or too large");
                }
                JsonNode root = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
                if (!"success".equals(text(root.get("status")))) {
                    throw new IllegalStateException("Prometheus query failed");
                }
                JsonNode data = root.get("data");
                if (data == null || !"vector".equals(text(data.get("resultType")))) {
                    throw new IllegalStateException("Prometheus did not return a vector");
                }
                JsonNode results = data.get("result");
                if (results == null || !results.isArray() || results.size() > 1) {
                    throw new IllegalStateException("Prometheus did not return a single series");
                }
                if (results.isEmpty()) {
                    return null;
                }
                JsonNode value = results.get(0).get("value");
                if (value == null || !value.isArray() || value.size() != 2) {
                    throw new IllegalStateException("Prometheus sample is invalid");
                }
                double number = Double.parseDouble(text(value.get(1)));
                return Double.isFinite(number) ? number : null;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Prometheus is unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Prometheus query was interrupted", exception);
        }
    }

    private String text(JsonNode node) {
        return node == null ? null : node.asText();
    }
}
