package com.example.assistant;

/** Against stateful MCP servers, whose sessions are lost when they restart. */
class StatefulMcpReconnectTests extends McpReconnectTests {

  StatefulMcpReconnectTests() {
    super(false);
  }
}
