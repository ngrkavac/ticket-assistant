package com.example.assistant;

/** Against stateless MCP servers, as the ticket and request-log servers are since B10. */
class StatelessMcpReconnectTests extends McpReconnectTests {

  StatelessMcpReconnectTests() {
    super(true);
  }
}
