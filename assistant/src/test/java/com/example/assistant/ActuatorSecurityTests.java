package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Real ports: Kubernetes probes and Prometheus reach actuator on the management port without a
 * token, while the application port still needs one.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.ai.model.chat=none",
      "spring.ai.model.embedding=none",
      "spring.ai.mcp.client.enabled=false",
      "helpdesk.request-log.enabled=false",
      "management.server.port=0",
      // a JWK set URI instead of issuer discovery: nothing is fetched at startup
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/helpdesk",
      "helpdesk.security.jwk-set-uri=http://localhost:1/certs"
    })
@Import(AssistantIntegrationTests.Fakes.class)
class ActuatorSecurityTests {

  @LocalServerPort int port;

  @LocalManagementPort int managementPort;

  @Autowired JwtDecoder jwtDecoder;

  private final HttpClient http = HttpClient.newHttpClient();

  private int status(int port, String path) throws Exception {
    return http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
            HttpResponse.BodyHandlers.discarding())
        .statusCode();
  }

  @Test
  void probesAndMetricsNeedNoTokenOnTheManagementPort() throws Exception {
    assertThat(status(managementPort, "/actuator/health/liveness")).isEqualTo(200);
    assertThat(status(managementPort, "/actuator/metrics")).isEqualTo(200);
  }

  @Test
  void usesTheJwkSetUriDecoder() {
    assertThat(jwtDecoder).isInstanceOf(NimbusJwtDecoder.class);
  }

  @Test
  void applicationPortStillNeedsAToken() throws Exception {
    assertThat(status(port, "/customers")).isEqualTo(401);
    assertThat(status(port, "/questions")).isEqualTo(401);
  }
}
