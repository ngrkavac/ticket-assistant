package com.example.assistant;

import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps MCP calls working across MCP server restarts (T3.2).
 *
 * <p>The clients are created with {@code spring.ai.mcp.client.initialized=false}, so the assistant
 * starts while an MCP server is down: the MCP SDK opens the session on the first call and, if that
 * fails, again on the next one. The ticket and request-log servers are stateless (B10): they keep
 * no session, so a restart only fails the calls made while they are down. A stateful Streamable
 * HTTP server is handled too: when it restarts, it no longer knows the client's session (Streamable
 * HTTP answers 404). The SDK then drops the session and opens a new one on its own, so the client
 * never has to be recreated ({@code McpReconnectTests} checks this against a real restart). But the
 * call that ran into the lost session fails. {@link #call} repeats such a call once, so no user
 * request fails because of a restart.
 *
 * <p>Only a lost session is retried, never a timeout, a refused connection or a tool error (the
 * server may have run the call, or is still down). The SDK also fails calls that were still in
 * flight on the old session this way, and one of them may have reached the old server just before
 * it stopped. So only calls that are safe to repeat go through here: reads, and {@code logRequest},
 * whose request id is unique on the server. {@code createTicket} is not repeated (see {@link
 * CustomerScopedToolCallback}).
 */
final class McpClientSupervisor {

  /** What the MCP SDK fails pending calls with when it drops a lost session. */
  static final String SESSION_TERMINATED = "MCP session with server terminated";

  private static final Logger log = LoggerFactory.getLogger(McpClientSupervisor.class);

  private McpClientSupervisor() {}

  /**
   * Runs {@code call}; if it fails because the server lost the MCP session, runs it once more on
   * the new session.
   */
  static <T> T call(String what, Supplier<T> call) {
    try {
      return call.get();
    } catch (RuntimeException e) {
      if (!isSessionLost(e)) {
        throw e;
      }
      log.info("MCP session lost (server restarted?); repeating {} on a new session", what);
      return call.get();
    }
  }

  /** Whether {@code e} or one of its causes says the server lost the MCP session. */
  static boolean isSessionLost(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof McpTransportSessionNotFoundException
          || SESSION_TERMINATED.equals(t.getMessage())) {
        return true;
      }
    }
    return false;
  }
}
