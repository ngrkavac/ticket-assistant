package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

class TicketDirectoryClientTests {

  private final McpSyncClient client = mock(McpSyncClient.class);

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
    assertThat(new TicketDirectoryClient(client).customers())
        .containsExactly("acme", "globex", "initech");
  }

  @Test
  void requireKnownNormalizesAndValidates() {
    answer("[\"acme\",\"globex\"]");
    var tickets = new TicketDirectoryClient(client);
    assertThat(tickets.requireKnown(" Acme ")).isEqualTo("acme");
    assertThatThrownBy(() -> tickets.requireKnown("umbrella"))
        .isInstanceOf(UnknownCustomerException.class);
  }

  @Test
  void noCustomerSkipsTheTicketServer() {
    var tickets = new TicketDirectoryClient(client);
    assertThat(tickets.requireKnown(null)).isNull();
    assertThat(tickets.requireKnown(" ")).isNull();
    verify(client, never()).callTool(any());
  }

  @Test
  void failuresAreTicketsUnavailable() {
    when(client.callTool(any())).thenThrow(new IllegalStateException("refused"));
    assertThatThrownBy(() -> new TicketDirectoryClient(client).customers())
        .isInstanceOf(TicketsUnavailableException.class);

    answer("not json");
    assertThatThrownBy(() -> new TicketDirectoryClient(client).customers())
        .isInstanceOf(TicketsUnavailableException.class);

    assertThatThrownBy(() -> new TicketDirectoryClient((McpSyncClient) null).customers())
        .isInstanceOf(TicketsUnavailableException.class);
  }
}
