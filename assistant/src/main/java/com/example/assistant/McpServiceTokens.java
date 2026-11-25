package com.example.assistant;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * Sends each MCP server's service token (contract K5): every HTTP request of an MCP connection
 * carries {@code Authorization: Bearer <token>}. The tokens are set per connection name ({@code
 * helpdesk.mcp.tokens.<connection>}, from {@code TICKETS_MCP_TOKEN} and {@code
 * REQUEST_LOG_MCP_TOKEN}) and matched to the requests by the origin (scheme, host and port) of the
 * connection's URL, so a token is only ever sent to its own server. The MCP transports resolve the
 * SSE and message endpoints against that origin, so the URL's path is not compared. A blank token
 * sends no header, which is what a server without a token (local development) expects.
 */
final class McpServiceTokens implements McpSyncHttpClientRequestCustomizer {

  private static final Logger log = LoggerFactory.getLogger(McpServiceTokens.class);

  /** A connection's URL and its token. */
  record Target(String connection, String scheme, String host, int port, String token) {

    boolean matches(URI uri) {
      return uri.getScheme() != null
          && uri.getHost() != null
          && scheme.equalsIgnoreCase(uri.getScheme())
          && host.equalsIgnoreCase(uri.getHost())
          && port == portOf(uri);
    }

    boolean sameOrigin(Target other) {
      return scheme.equals(other.scheme) && host.equalsIgnoreCase(other.host) && port == other.port;
    }
  }

  private final List<Target> targets;

  /**
   * @param urls the URL of each MCP connection, by connection name
   * @param tokens the service token of each connection, by connection name; blank or missing for
   *     none
   * @throws IllegalArgumentException if a token is set for a connection with an invalid URL, a
   *     token contains whitespace or control characters, or two connections with different tokens
   *     share an origin (it would be unclear which token to send)
   */
  McpServiceTokens(Map<String, String> urls, Map<String, String> tokens) {
    List<Target> found = new ArrayList<>();
    tokens.forEach(
        (connection, value) -> {
          String token = value == null ? "" : value.strip();
          if (token.isEmpty()) {
            log.info("MCP connection '{}': no service token configured", connection);
            return;
          }
          if (!token.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw new IllegalArgumentException(
                "The service token of MCP connection '"
                    + connection
                    + "' must be printable ASCII without spaces");
          }
          String url = urls.get(connection);
          if (url == null || url.isBlank()) {
            log.warn(
                "MCP connection '{}' has a service token but no URL; the token is not used",
                connection);
            return;
          }
          Target target = target(connection, url, token);
          for (Target other : found) {
            if (other.sameOrigin(target) && !other.token().equals(token)) {
              throw new IllegalArgumentException(
                  "MCP connections '"
                      + other.connection()
                      + "' and '"
                      + connection
                      + "' have the same host and port but different service tokens");
            }
          }
          found.add(target);
          log.info("MCP connection '{}': sending the service token", connection);
        });
    this.targets = List.copyOf(found);
  }

  private static Target target(String connection, String url, String token) {
    URI uri;
    try {
      uri = URI.create(url.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid URL of MCP connection '" + connection + "'", e);
    }
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw new IllegalArgumentException("Invalid URL of MCP connection '" + connection + "'");
    }
    return new Target(
        connection, uri.getScheme().toLowerCase(Locale.ROOT), uri.getHost(), portOf(uri), token);
  }

  private static int portOf(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  /** The token to send with a request to {@code uri}, or null for none. */
  @Nullable
  String tokenFor(URI uri) {
    if (uri == null) {
      return null;
    }
    return targets.stream().filter(t -> t.matches(uri)).map(Target::token).findFirst().orElse(null);
  }

  @Override
  public void customize(
      HttpRequest.Builder builder,
      String method,
      URI endpoint,
      String body,
      McpTransportContext context) {
    String token = tokenFor(endpoint);
    if (token != null) {
      builder.setHeader("Authorization", "Bearer " + token);
    }
  }
}
