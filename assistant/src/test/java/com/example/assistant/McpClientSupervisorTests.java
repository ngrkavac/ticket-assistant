package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpClientSupervisorTests {

  private final AtomicInteger attempts = new AtomicInteger();

  /** A call that throws {@code failure} the first {@code times} times, then answers "ok". */
  private String call(RuntimeException failure, int times) {
    return McpClientSupervisor.call(
        "test",
        () -> {
          if (attempts.incrementAndGet() <= times) {
            throw failure;
          }
          return "ok";
        });
  }

  @Test
  void aCallThatWorksRunsOnce() {
    assertThat(call(new IllegalStateException(), 0)).isEqualTo("ok");
    assertThat(attempts).hasValue(1);
  }

  @Test
  void aCallThatRanIntoALostSessionIsRepeatedOnce() {
    assertThat(call(new RuntimeException(McpClientSupervisor.SESSION_TERMINATED), 1))
        .isEqualTo("ok");
    assertThat(attempts).hasValue(2);
  }

  @Test
  void theSessionLossMayBeACause() {
    RuntimeException wrapped =
        new IllegalStateException(
            "tool failed", new McpTransportSessionNotFoundException("session-1"));
    assertThat(call(wrapped, 1)).isEqualTo("ok");
    assertThat(attempts).hasValue(2);
  }

  @Test
  void aSecondFailureIsThrown() {
    RuntimeException lost = new RuntimeException(McpClientSupervisor.SESSION_TERMINATED);
    assertThatThrownBy(() -> call(lost, 2)).isSameAs(lost);
    assertThat(attempts).hasValue(2);
  }

  @Test
  void otherFailuresAreNotRepeated() {
    RuntimeException down = new RuntimeException(new ConnectException("Connection refused"));
    assertThatThrownBy(() -> call(down, 1)).isSameAs(down);
    assertThat(attempts).hasValue(1);
    assertThat(McpClientSupervisor.isSessionLost(new RuntimeException((String) null))).isFalse();
  }
}
