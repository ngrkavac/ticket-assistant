package com.example.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;

/**
 * Restricts a ticket tool to the customer of the current request (T1.4). The customer and the
 * signed-in user come from the Spring AI {@link ToolContext} that {@link AssistantService} sets per
 * request, never from the model. Before the call is forwarded, the tool's {@code customer} argument
 * is checked and overwritten:
 *
 * <ul>
 *   <li>missing or blank: it is filled in with the request's customer;
 *   <li>the request's customer (any case or surrounding spaces): it is normalized;
 *   <li>any other value: the call is refused with "Not allowed for this customer", logged at WARN
 *       and counted in {@code helpdesk_tool_denied_total{tool}} (K9). The ticket server is not
 *       called.
 * </ul>
 *
 * <p>Fails closed: a call without a customer or without a user who may act for it in the tool
 * context is refused the same way, and so is a write tool ({@link #WRITE_TOOLS}) for a user without
 * the {@code tickets-write} role. Every call, refused or not, is added to the request's {@link
 * ToolCallAudit} if the tool context has one. The tool context itself is not forwarded, so the
 * principal never ends up in the MCP request's {@code _meta}.
 */
final class CustomerScopedToolCallback implements ToolCallback {

  /** The tool argument, and the {@link ToolContext} key holding the request's customer. */
  static final String CUSTOMER = "customer";

  /** The {@link ToolContext} key holding the {@link HelpdeskPrincipal} of the request. */
  static final String PRINCIPAL = "principal";

  /** The {@link ToolContext} key holding the request's {@link ToolCallAudit}. */
  static final String AUDIT = "toolCallAudit";

  /**
   * Tools that change data. They are offered to the model only for users with the {@code
   * tickets-write} role, and a call by anyone else is refused.
   */
  static final Set<String> WRITE_TOOLS = Set.of("createTicket");

  /** Tool error text for a call outside the request's customer (as for /ask, see K3). */
  static final String CUSTOMER_DENIED = "Not allowed for this customer";

  /** Tool error text for a write tool called without the {@code tickets-write} role. */
  static final String WRITE_DENIED = "Not allowed for this user";

  static final String DENIED_METRIC = "helpdesk.tool.denied";

  private static final Logger log = LoggerFactory.getLogger(CustomerScopedToolCallback.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ToolCallback delegate;

  private final MeterRegistry meters;

  CustomerScopedToolCallback(ToolCallback delegate, MeterRegistry meters) {
    this.delegate = delegate;
    this.meters = meters;
  }

  /**
   * Whether the tool has a {@code customer} argument and so can be scoped. Tools without one are
   * not offered to the model.
   */
  static boolean takesCustomer(ToolCallback tool) {
    try {
      JsonNode schema = JSON.readTree(tool.getToolDefinition().inputSchema());
      return schema != null && schema.path("properties").has(CUSTOMER);
    } catch (JsonProcessingException | IllegalArgumentException e) {
      return false;
    }
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return delegate.getToolMetadata();
  }

  /** Without a tool context there is no customer to scope to, so the call is refused. */
  @Override
  public String call(String toolInput) {
    return call(toolInput, null);
  }

  @Override
  public String call(String toolInput, @Nullable ToolContext toolContext) {
    ToolCallAudit audit = audit(toolContext);
    HelpdeskPrincipal principal = principal(toolContext);
    String scoped = scopedCustomer(toolContext, principal);
    if (scoped == null) {
      throw deny(audit, null, null, "no customer or user in the tool context", CUSTOMER_DENIED);
    }
    if (!offeredTo(principal)) {
      throw deny(audit, scoped, null, "the user may not use write tools", WRITE_DENIED);
    }
    ObjectNode args = arguments(toolInput);
    JsonNode requested = args.get(CUSTOMER);
    if (requested != null && !requested.isNull()) {
      String value = requested.isTextual() ? requested.asText().trim() : requested.toString();
      if (!value.isEmpty() && !value.toLowerCase(Locale.ROOT).equals(scoped)) {
        throw deny(audit, scoped, value, "the model asked for another customer", CUSTOMER_DENIED);
      }
    }
    args.put(CUSTOMER, scoped);
    String tool = getToolDefinition().name();
    String result;
    try {
      // no tool context: it holds the principal, which must not reach the ticket server
      result = delegate.call(args.toString());
    } catch (RuntimeException e) {
      record(audit, tool, scoped, ToolCallAudit.ERROR, null);
      throw e;
    }
    record(audit, tool, scoped, ToolCallAudit.SUCCESS, isWriteTool() ? resultId(result) : null);
    return result;
  }

  /**
   * Whether the model may be offered this tool for {@code principal}: read tools for everyone,
   * write tools ({@link #WRITE_TOOLS}) only with the {@code tickets-write} role (T1.5).
   */
  boolean offeredTo(@Nullable HelpdeskPrincipal principal) {
    return !isWriteTool() || (principal != null && principal.hasRole(SecurityConfig.ROLE_WRITER));
  }

  boolean isWriteTool() {
    return WRITE_TOOLS.contains(getToolDefinition().name());
  }

  @Nullable
  private static HelpdeskPrincipal principal(@Nullable ToolContext toolContext) {
    return toolContext != null
            && toolContext.getContext().get(PRINCIPAL) instanceof HelpdeskPrincipal principal
        ? principal
        : null;
  }

  @Nullable
  private static ToolCallAudit audit(@Nullable ToolContext toolContext) {
    return toolContext != null && toolContext.getContext().get(AUDIT) instanceof ToolCallAudit audit
        ? audit
        : null;
  }

  /** The request's customer, or null unless the context's user may act for it. */
  @Nullable
  private static String scopedCustomer(
      @Nullable ToolContext toolContext, @Nullable HelpdeskPrincipal principal) {
    if (toolContext == null || principal == null) {
      return null;
    }
    if (!(toolContext.getContext().get(CUSTOMER) instanceof String customer)
        || customer.isBlank()) {
      return null;
    }
    String c = customer.trim().toLowerCase(Locale.ROOT);
    return principal.canAccess(c) ? c : null;
  }

  private static void record(
      @Nullable ToolCallAudit audit,
      String tool,
      @Nullable String customer,
      String outcome,
      @Nullable String resultId) {
    if (audit != null) {
      audit.add(tool, customer, outcome, resultId);
    }
  }

  /**
   * The {@code id} of the object a write tool returned, e.g. the new ticket's id. An MCP tool's
   * result is the JSON list of its content items; the ticket is the JSON text of the first text
   * item. Null if the result has another shape.
   */
  @Nullable
  static String resultId(@Nullable String result) {
    if (result == null) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(result);
      if (node != null && node.isArray()) {
        JsonNode text = node.path(0).path("text");
        node = text.isTextual() ? JSON.readTree(text.asText()) : null;
      }
      JsonNode id = node == null ? null : node.get("id");
      return id != null && id.isValueNode() && !id.asText().isBlank() ? id.asText() : null;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private ObjectNode arguments(String toolInput) {
    if (toolInput == null || toolInput.isBlank()) {
      return JSON.createObjectNode();
    }
    try {
      if (JSON.readTree(toolInput) instanceof ObjectNode args) {
        return args;
      }
    } catch (JsonProcessingException e) {
      // reported below
    }
    throw new ToolExecutionException(
        getToolDefinition(), new IllegalArgumentException("Invalid tool arguments"));
  }

  private ToolExecutionException deny(
      @Nullable ToolCallAudit audit,
      String scoped,
      String requested,
      String reason,
      String message) {
    String tool = getToolDefinition().name();
    record(audit, tool, scoped, ToolCallAudit.DENIED, null);
    log.warn(
        "Tool {} denied: {} (request customer {}, requested customer {})",
        tool,
        reason,
        scoped,
        printable(requested));
    meters.counter(DENIED_METRIC, "tool", tool).increment();
    return new ToolExecutionException(getToolDefinition(), new AccessDeniedException(message));
  }

  /** Model output goes into the log: keep it short and on one line. */
  private static String printable(String value) {
    if (value == null) {
      return null;
    }
    String clean = value.replaceAll("[^\\p{Alnum} ._:@-]", "?");
    return clean.length() > 64 ? clean.substring(0, 64) + "..." : clean;
  }
}
