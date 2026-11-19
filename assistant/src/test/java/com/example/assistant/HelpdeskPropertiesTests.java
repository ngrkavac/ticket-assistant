package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HelpdeskPropertiesTests {

  @Test
  void appliesDefaultsWhenNothingIsConfigured() {
    var p = new HelpdeskProperties(null, null, null, null, 0, 0, 0, null, null);
    assertThat(p.rag().topK()).isEqualTo(3);
    assertThat(p.rag().similarityThreshold()).isEqualTo(0.3);
    assertThat(p.security().apiKey()).isNull();
    assertThat(p.kb().location()).isEqualTo("classpath:kb/*.md");
    assertThat(p.maxQuestionLength()).isEqualTo(1000);
    assertThat(p.maxConcurrentRequests()).isEqualTo(8);
    assertThat(p.maxCatalogQuestionLength()).isEqualTo(1000);
    assertThat(p.queueWait()).isEqualTo(Duration.ofSeconds(1));
    assertThat(p.retryAfter()).isEqualTo(Duration.ofSeconds(5));
    assertThat(p.retryAfterSeconds()).isEqualTo(5);
    assertThat(p.requestLog().enabled()).isTrue();
    assertThat(p.requestLog().connection()).isEqualTo("requestlog");
  }

  @Test
  void keepsConfiguredValuesAndFallsBackOnBlankKbLocation() {
    var p =
        new HelpdeskProperties(
            new HelpdeskProperties.Rag(5, 0.5),
            new HelpdeskProperties.Security("k"),
            new HelpdeskProperties.Kb(" "),
            new HelpdeskProperties.RequestLog(false, " "),
            200,
            2,
            300,
            Duration.ofMillis(250),
            Duration.ofMillis(1500));
    assertThat(p.rag().topK()).isEqualTo(5);
    assertThat(p.security().apiKey()).isEqualTo("k");
    assertThat(p.kb().location()).isEqualTo("classpath:kb/*.md");
    assertThat(p.maxQuestionLength()).isEqualTo(200);
    assertThat(p.maxConcurrentRequests()).isEqualTo(2);
    assertThat(p.maxCatalogQuestionLength()).isEqualTo(300);
    assertThat(p.queueWait()).isEqualTo(Duration.ofMillis(250));
    assertThat(p.retryAfterSeconds()).isEqualTo(2); // 1.5s rounded up
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
  }

  private static HelpdeskProperties props(Duration queueWait, Duration retryAfter) {
    return new HelpdeskProperties(null, null, null, null, 0, 0, 0, queueWait, retryAfter);
  }
}
