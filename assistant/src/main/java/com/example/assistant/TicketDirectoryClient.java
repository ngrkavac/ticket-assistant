package com.example.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reads the customer list from the ticket MCP server by calling its {@code listCustomers} tool
 * directly (not through the model), for the UI's customer dropdown and to validate /ask. The list
 * is cached for {@code helpdesk.customers-cache-ttl}; an unknown customer reloads it once, so a
 * newly added customer is accepted without waiting for the cache to expire. Failures are never
 * cached.
 */
@Component
class TicketDirectoryClient {

  static final String CONNECTION = "tickets";

  static final String TOOL = "listCustomers";

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

  /** The only cache key: the whole customer list is one entry. */
  private static final String ALL = "all";

  private final McpSyncClient client;

  private final Cache<String, List<String>> cache;

  @Autowired
  TicketDirectoryClient(ObjectProvider<List<McpSyncClient>> clients, HelpdeskProperties props) {
    this(
        clients.getIfAvailable(List::of).stream()
            .filter(c -> RequestLogClient.isConnection(c.getClientInfo(), CONNECTION))
            .findFirst()
            .orElse(null),
        props.customersCacheTtl(),
        Ticker.systemTicker());
  }

  TicketDirectoryClient(McpSyncClient client, Duration ttl, Ticker ticker) {
    this.client = client;
    this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).ticker(ticker).build();
  }

  /**
   * The ticket server's customers (cached).
   *
   * @throws TicketsUnavailableException if the ticket server is missing, fails or answers oddly
   */
  List<String> customers() {
    return cache.get(ALL, k -> load());
  }

  private List<String> load() {
    if (client == null) {
      throw new TicketsUnavailableException("No '" + CONNECTION + "' MCP connection", null);
    }
    McpSchema.CallToolResult result;
    try {
      result =
          McpClientSupervisor.call(
              TOOL, () -> client.callTool(new McpSchema.CallToolRequest(TOOL, Map.of())));
    } catch (RuntimeException e) {
      throw new TicketsUnavailableException(TOOL + " failed", e);
    }
    if (Boolean.TRUE.equals(result.isError()) || result.content() == null) {
      throw new TicketsUnavailableException(TOOL + " returned an error", null);
    }
    String text =
        result.content().stream()
            .filter(McpSchema.TextContent.class::isInstance)
            .map(c -> ((McpSchema.TextContent) c).text())
            .findFirst()
            .orElseThrow(() -> new TicketsUnavailableException(TOOL + " returned no text", null));
    try {
      return List.copyOf(JSON.readValue(text, STRINGS));
    } catch (Exception e) {
      throw new TicketsUnavailableException(TOOL + " returned unexpected content", e);
    }
  }

  /**
   * Returns the customer as the ticket server spells it, or null if none was given.
   *
   * @throws UnknownCustomerException if the ticket server does not know the customer
   */
  String requireKnown(String customer) {
    if (customer == null || customer.isBlank()) {
      return null;
    }
    String c = customer.trim().toLowerCase(Locale.ROOT);
    if (!customers().contains(c)) {
      cache.invalidate(ALL); // the customer may have been added since the list was cached
      if (!customers().contains(c)) {
        throw new UnknownCustomerException();
      }
    }
    return c;
  }
}
