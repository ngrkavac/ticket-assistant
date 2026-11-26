package com.example.requestlog;

import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.Transport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * OTLP span export (T4.2, K8), switched on by the standard OpenTelemetry variable {@value
 * #ENDPOINT_ENV} (base URL of the collector, e.g. {@code http://otel-collector:4318}).
 *
 * <p>Spring Boot's own {@code management.otlp.tracing.endpoint} takes the full traces URL and
 * exports as soon as it is set, even to an empty value. This bean follows the OpenTelemetry
 * convention instead: the signal path {@code /v1/traces} (HTTP) is appended to the base URL, and a
 * blank or missing endpoint means no exporter. Tracing itself stays on either way, so incoming
 * {@code traceparent} headers are continued and {@code traceId}/{@code spanId} reach the logs.
 */
@Configuration(proxyBeanMethods = false)
class OtlpTracingConfig {

  static final String ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_ENDPOINT";

  @Bean
  @Conditional(EndpointConfigured.class)
  OtlpTracingConnectionDetails otlpTracingConnectionDetails(Environment environment) {
    String endpoint = environment.getProperty(ENDPOINT_ENV, "");
    return transport -> tracesUrl(endpoint, transport);
  }

  /** The traces URL for a collector base URL: {@code /v1/traces} for HTTP, as is for gRPC. */
  static String tracesUrl(String endpoint, Transport transport) {
    String base = endpoint.strip();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return transport == Transport.GRPC ? base : base + "/v1/traces";
  }

  static final class EndpointConfigured implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String endpoint = context.getEnvironment().getProperty(ENDPOINT_ENV);
      return endpoint != null && !endpoint.isBlank();
    }
  }
}
