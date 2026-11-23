package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HelpdeskPropertiesTests {

  @Test
  void appliesDefaultsWhenNothingIsConfigured() {
    var p = new HelpdeskProperties(null, null, null, null, 0, 0, 0, null, null, null);
    assertThat(p.rag().topK()).isEqualTo(3);
    assertThat(p.rag().similarityThreshold()).isEqualTo(0.3);
    assertThat(p.security().apiKey()).isNull();
    assertThat(p.security().rolesClaim()).isEqualTo("roles");
    assertThat(p.security().customersClaim()).isEqualTo("customers");
    assertThat(p.kb().location()).isEqualTo("classpath:kb/*.md");
    assertThat(p.maxQuestionLength()).isEqualTo(1000);
    assertThat(p.maxConcurrentRequests()).isEqualTo(8);
    assertThat(p.maxCatalogQuestionLength()).isEqualTo(1000);
    assertThat(p.queueWait()).isEqualTo(Duration.ofSeconds(1));
    assertThat(p.retryAfter()).isEqualTo(Duration.ofSeconds(5));
    assertThat(p.retryAfterSeconds()).isEqualTo(5);
    assertThat(p.customersCacheTtl()).isEqualTo(Duration.ofSeconds(60));
    assertThat(p.requestLog().enabled()).isTrue();
    assertThat(p.requestLog().connection()).isEqualTo("requestlog");
    assertThat(p.requestLog().queueCapacity()).isEqualTo(1000);
    assertThat(p.requestLog().writeThreads()).isEqualTo(4);
  }

  @Test
  void keepsConfiguredRequestLogQueueSettings() {
    var r = new HelpdeskProperties.RequestLog(true, "log", 50, 2);
    assertThat(r.connection()).isEqualTo("log");
    assertThat(r.queueCapacity()).isEqualTo(50);
    assertThat(r.writeThreads()).isEqualTo(2);
  }

  @Test
  void keepsConfiguredValuesAndFallsBackOnBlankKbLocation() {
    var p =
        new HelpdeskProperties(
            new HelpdeskProperties.Rag(5, 0.5),
            new HelpdeskProperties.Security("k", " realm_access.roles ", "tenants"),
            new HelpdeskProperties.Kb(" "),
            new HelpdeskProperties.RequestLog(false, " ", 0, 0),
            200,
            2,
            300,
            Duration.ofMillis(250),
            Duration.ofMillis(1500),
            Duration.ZERO);
    assertThat(p.rag().topK()).isEqualTo(5);
    assertThat(p.security().apiKey()).isEqualTo("k");
    assertThat(p.security().rolesClaim()).isEqualTo("realm_access.roles");
    assertThat(p.security().customersClaim()).isEqualTo("tenants");
    assertThat(p.kb().location()).isEqualTo("classpath:kb/*.md");
    assertThat(p.maxQuestionLength()).isEqualTo(200);
    assertThat(p.maxConcurrentRequests()).isEqualTo(2);
    assertThat(p.maxCatalogQuestionLength()).isEqualTo(300);
    assertThat(p.queueWait()).isEqualTo(Duration.ofMillis(250));
    assertThat(p.retryAfterSeconds()).isEqualTo(2); // 1.5s rounded up
    assertThat(p.customersCacheTtl()).isZero();
    assertThat(p.requestLog().enabled()).isFalse();
    assertThat(p.requestLog().connection()).isEqualTo("requestlog");
  }

  @Test
  void zeroQueueWaitIsAllowedButNegativeDurationsAreNot() {
    assertThat(props(Duration.ZERO, null).queueWait()).isZero();
    assertThatThrownBy(() -> props(Duration.ofSeconds(-1), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("queue-wait");
    assertThatThrownBy(() -> props(null, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retry-after");
    assertThatThrownBy(
            () ->
                new HelpdeskProperties(
                    null, null, null, null, 0, 0, 0, null, null, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("customers-cache-ttl");
  }

  private static HelpdeskProperties props(Duration queueWait, Duration retryAfter) {
    return new HelpdeskProperties(null, null, null, null, 0, 0, 0, queueWait, retryAfter, null);
  }
}
