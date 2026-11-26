package com.example.requestlog;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * /actuator/prometheus (T4.3, K7, K9) and traceparent propagation (T4.2) on the running server.
 * {@code @AutoConfigureObservability} turns on what Spring Boot tests switch off by default: the
 * Prometheus registry and tracing.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mcp.service-token=s3cret-token",
      "management.tracing.sampling.probability=1.0",
      "management.tracing.opentelemetry.export.schedule-delay=50ms"
    })
@ActiveProfiles("local")
@AutoConfigureObservability
class ObservabilityTests {

  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Value("${local.server.port}")
  int port;

  @Autowired RecordingSpanExporter spans;

  @Autowired ApplicationContext context;

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return HTTP.send(
        request.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
  }

  @Test
  void prometheusIsServedOnTheAppPortWithoutAToken() throws Exception {
    // A rejected MCP request: the auth filter runs inside the observation filter, so it is counted.
    assertThat(
            send(request("/mcp/message").POST(HttpRequest.BodyPublishers.ofString("{}")))
                .statusCode())
        .isEqualTo(401);

    HttpResponse<String> response = send(request("/actuator/prometheus"));

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("http_server_requests_seconds_bucket{")
        .containsPattern(
            "http_server_requests_seconds_count\\{[^}]*method=\"POST\",[^}]*status=\"401\"")
        .contains("hikaricp_connections_max{")
        .contains("application=\"request-log-mcp-server\"")
        .contains("requestlog_purged_rows_total{");
  }

  @Test
  void anIncomingTraceparentIsContinued() throws Exception {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";

    send(
        request("/actuator/health/readiness")
            .header("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"));

    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!spans.traceIds().contains(traceId) && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(spans.traceIds()).contains(traceId);
  }

  @Test
  void withoutOtelExporterOtlpEndpointThereIsNoOtlpExporter() {
    assertThat(context.getBeansOfType(OtlpHttpSpanExporter.class)).isEmpty();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Spans {
    @Bean
    RecordingSpanExporter recordingSpanExporter() {
      return new RecordingSpanExporter();
    }
  }

  /** Keeps exported spans in memory. */
  static final class RecordingSpanExporter implements SpanExporter {
    private final List<SpanData> exported = new CopyOnWriteArrayList<>();

    List<String> traceIds() {
      return exported.stream().map(SpanData::getTraceId).toList();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      exported.addAll(spans);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
