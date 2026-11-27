package com.example.assistant;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;

/**
 * An in-process MCP server on Streamable HTTP at {@code /mcp} (K6), built from the MCP SDK's
 * servlet transports on embedded Tomcat. It is either stateless, like the ticket and request-log
 * servers since B10 ({@code spring.ai.mcp.server.protocol=STATELESS}: no session id, {@code GET
 * /mcp} answers 405), or stateful (each client gets a session id, which a restart loses). Like
 * those servers it checks the service token (K5): 401 without {@code Authorization: Bearer
 * <token>}. {@link #restart()} stops it and starts a fresh server on the same port, as when a pod
 * is replaced.
 */
final class TestMcpServer implements AutoCloseable {

  private final String token;

  private final boolean stateless;

  private final int port;

  /** The text each tool answers with, by tool name. */
  private final Map<String, String> tools;

  /** How often each tool was run. */
  private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

  /** The Authorization header of every request ("none" when missing). */
  final List<String> authorizations = new CopyOnWriteArrayList<>();

  /** How many sessions the clients opened (initialize requests of a stateful server). */
  final AtomicInteger initializations = new AtomicInteger();

  private Tomcat tomcat;

  /** Closes the running MCP server. */
  private Runnable server;

  /**
   * @param stateless whether the server keeps no sessions (as the real servers since B10)
   * @param token the service token the server requires, or null for none
   * @param tools the text each tool answers with, by tool name; every tool takes a {@code customer}
   */
  TestMcpServer(boolean stateless, String token, Map<String, String> tools) {
    this.stateless = stateless;
    this.token = token;
    this.port = freePort();
    this.tools = Map.copyOf(tools);
  }

  /** How often the tool was run, over all restarts. */
  int calls(String tool) {
    AtomicInteger n = calls.get(tool);
    return n == null ? 0 : n.get();
  }

  private static McpSchema.Tool definition(String name) {
    return McpSchema.Tool.builder()
        .name(name)
        .description(name)
        .inputSchema(
            new McpSchema.JsonSchema(
                "object",
                Map.of("customer", Map.of("type", "string")),
                List.of(),
                null,
                null,
                null))
        .build();
  }

  private McpSchema.CallToolResult run(String name, String text) {
    calls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(text)))
        .isError(false)
        .build();
  }

  private McpServerFeatures.SyncToolSpecification tool(String name, String text) {
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(definition(name))
        .callHandler((exchange, request) -> run(name, text))
        .build();
  }

  private McpStatelessServerFeatures.SyncToolSpecification statelessTool(String name, String text) {
    return McpStatelessServerFeatures.SyncToolSpecification.builder()
        .tool(definition(name))
        .callHandler((context, request) -> run(name, text))
        .build();
  }

  /** Builds the MCP server and returns its servlet. */
  private HttpServlet mcpServlet() {
    McpSchema.ServerCapabilities capabilities =
        McpSchema.ServerCapabilities.builder().tools(true).build();
    if (stateless) {
      HttpServletStatelessServerTransport transport =
          HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();
      McpStatelessSyncServer mcp =
          McpServer.sync(transport)
              .serverInfo("test-mcp-server", "1")
              .capabilities(capabilities)
              .tools(
                  tools.entrySet().stream()
                      .map(e -> statelessTool(e.getKey(), e.getValue()))
                      .toList())
              .build();
      server = mcp::close;
      return transport;
    }
    HttpServletStreamableServerTransportProvider transport =
        HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
    McpSyncServer mcp =
        McpServer.sync(transport)
            .serverInfo("test-mcp-server", "1")
            .capabilities(capabilities)
            .tools(tools.entrySet().stream().map(e -> tool(e.getKey(), e.getValue())).toList())
            .build();
    server = mcp::close;
    return transport;
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The base URL, without the {@code /mcp} endpoint. */
  String url() {
    return "http://127.0.0.1:" + port;
  }

  synchronized TestMcpServer start() {
    HttpServlet mcp = mcpServlet();
    tomcat = new Tomcat();
    tomcat.setBaseDir(System.getProperty("java.io.tmpdir") + "/test-mcp-server-" + port);
    tomcat.setPort(port);
    tomcat.getConnector().setProperty("address", "127.0.0.1");
    Context context = tomcat.addContext("", null);
    Wrapper wrapper = Tomcat.addServlet(context, "mcp", new TokenCheck(mcp));
    wrapper.setAsyncSupported(true);
    context.addServletMappingDecoded("/mcp", "mcp");
    try {
      tomcat.start();
    } catch (LifecycleException e) {
      throw new IllegalStateException(e);
    }
    return this;
  }

  synchronized void stop() {
    if (tomcat == null) {
      return;
    }
    server.run();
    try {
      tomcat.stop();
      tomcat.destroy();
    } catch (LifecycleException e) {
      throw new IllegalStateException(e);
    }
    tomcat = null;
    server = null;
  }

  /** Stops the server and starts a new one on the same port: every session is gone. */
  void restart() {
    stop();
    start();
  }

  @Override
  public void close() {
    stop();
  }

  /** Checks the service token like stream B's servers, then hands the request to the MCP SDK. */
  private final class TokenCheck extends HttpServlet {

    private final HttpServlet mcp;

    TokenCheck(HttpServlet mcp) {
      this.mcp = mcp;
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      String auth = req.getHeader("Authorization");
      authorizations.add(auth == null ? "none" : auth);
      if (token != null && !("Bearer " + token).equals(auth)) {
        resp.setHeader("WWW-Authenticate", "Bearer");
        resp.sendError(401);
        return;
      }
      if (!stateless && "POST".equals(req.getMethod()) && req.getHeader("Mcp-Session-Id") == null) {
        initializations.incrementAndGet();
      }
      mcp.service(req, resp);
    }
  }
}
