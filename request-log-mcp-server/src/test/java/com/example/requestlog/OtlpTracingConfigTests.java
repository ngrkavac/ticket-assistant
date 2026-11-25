package com.example.requestlog;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.Transport;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** OTLP export is switched on by OTEL_EXPORTER_OTLP_ENDPOINT only (K8). */
class OtlpTracingConfigTests {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(OtlpTracingConfig.class)
          .withConfiguration(
              AutoConfigurations.of(
                  OpenTelemetryAutoConfiguration.class,
                  OpenTelemetryTracingAutoConfiguration.class,
                  OtlpTracingAutoConfiguration.class));

  @Test
  void noEndpointMeansNoExporter() {
    runner.run(context -> assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class));
  }

  @Test
  void blankEndpointMeansNoExporter() {
    runner
        .withPropertyValues("OTEL_EXPORTER_OTLP_ENDPOINT= ")
        .run(context -> assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class));
  }

  @Test
  void anEndpointExportsToItsTracesPath() {
    runner
        .withPropertyValues("OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318/")
        .run(
            context -> {
              assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class);
              assertThat(context.getBean(OtlpTracingConnectionDetails.class).getUrl())
                  .isEqualTo("http://otel-collector:4318/v1/traces");
            });
  }

  @Test
  void grpcUsesTheEndpointAsIs() {
    assertThat(OtlpTracingConfig.tracesUrl(" http://collector:4317 ", Transport.GRPC))
        .isEqualTo("http://collector:4317");
    assertThat(OtlpTracingConfig.tracesUrl("http://collector:4318//", Transport.HTTP))
        .isEqualTo("http://collector:4318/v1/traces");
  }
}
