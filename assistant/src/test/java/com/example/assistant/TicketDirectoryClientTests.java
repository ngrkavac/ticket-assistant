package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TicketDirectoryClientTests {

  private final McpSyncClient client = mock(McpSyncClient.class);

  private final AtomicLong nanos = new AtomicLong();

  private TicketDirectoryClient directory(McpSyncClient c) {
    return new TicketDirectoryClient(c, Duration.ofSeconds(60), nanos::get);
  }

  private void advance(Duration d) {
    nanos.addAndGet(d.toNanos());
  }

  private void answer(String json) {
    // doReturn: re-stubbing with when() would call an earlier throwing stub
    doReturn(
            McpSchema.CallToolResult.builder()
                .isError(false)
                .addContent(new McpSchema.TextContent(json))
                .build())
        .when(client)
        .callTool(any());
  }

  @Test
  void parsesCustomers() {
    answer("[\"acme\",\"globex\",\"initech\"]");
    assertThat(directory(client).customers()).containsExactly("acme", "globex", "initech");
  }

  @Test
  void callsTheTicketServerOncePerTtl() {
    answer("[\"acme\"]");
    var tickets = directory(client);
    assertThat(tickets.customers()).containsExactly("acme");
    assertThat(tickets.requireKnown("acme")).isEqualTo("acme");
    advance(Duration.ofSeconds(59));
    assertThat(tickets.customers()).containsExactly("acme");
    verify(client, times(1)).callTool(any());

    answer("[\"acme\",\"globex\"]");
    advance(Duration.ofSeconds(2));
    assertThat(tickets.customers()).containsExactly("acme", "globex");
    verify(client, times(2)).callTool(any());
  }

  @Test
  void unknownCustomerReloadsTheListOnce() {
    answer("[\"acme\"]");
    var tickets = directory(client);
    assertThat(tickets.customers()).containsExactly("acme");

    answer("[\"acme\",\"globex\"]"); // globex added after the list was cached
    assertThat(tickets.requireKnown("globex")).isEqualTo("globex");
    verify(client, times(2)).callTool(any());

    assertThatThrownBy(() -> tickets.requireKnown("umbrella"))
        .isInstanceOf(UnknownCustomerException.class);
    verify(client, times(3)).callTool(any());
    assertThat(tickets.requireKnown("acme")).isEqualTo("acme"); // reloaded list is cached
    verify(client, times(3)).callTool(any());
  }

  @Test
  void failuresAreNotCached() {
    when(client.callTool(any())).thenThrow(new IllegalStateException("refused"));
    var tickets = directory(client);
    assertThatThrownBy(tickets::customers).isInstanceOf(TicketsUnavailableException.class);
    answer("[\"acme\"]");
    assertThat(tickets.customers()).containsExactly("acme");
  }

  @Test
  void zeroTtlDisablesTheCache() {
    answer("[\"acme\"]");
    var tickets = new TicketDirectoryClient(client, Duration.ZERO, nanos::get);
    tickets.customers();
    tickets.customers();
    verify(client, times(2)).callTool(any());
  }

  @Test
  void requireKnownNormalizesAndValidates() {
    answer("[\"acme\",\"globex\"]");
    var tickets = directory(client);
    assertThat(tickets.requireKnown(" Acme ")).isEqualTo("acme");
    assertThatThrownBy(() -> tickets.requireKnown("umbrella"))
        .isInstanceOf(UnknownCustomerException.class);
  }

  @Test
  void noCustomerSkipsTheTicketServer() {
    var tickets = directory(client);
    assertThat(tickets.requireKnown(null)).isNull();
    assertThat(tickets.requireKnown(" ")).isNull();
    verify(client, never()).callTool(any());
  }

  @Test
  void failuresAreTicketsUnavailable() {
    when(client.callTool(any())).thenThrow(new IllegalStateException("refused"));
    assertThatThrownBy(() -> directory(client).customers())
        .isInstanceOf(TicketsUnavailableException.class);

    answer("not json");
    assertThatThrownBy(() -> directory(client).customers())
        .isInstanceOf(TicketsUnavailableException.class);

    assertThatThrownBy(() -> directory(null).customers())
        .isInstanceOf(TicketsUnavailableException.class);
  }
}
