package com.example.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

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
 * context is refused the same way. The tool context itself is not forwarded, so the principal never
 * ends up in the MCP request's {@code _meta}.
 */
final class CustomerScopedToolCallback implements ToolCallback {

  /** The tool argument, and the {@link ToolContext} key holding the request's customer. */
  static final String CUSTOMER = "customer";

  /** The {@link ToolContext} key holding the {@link HelpdeskPrincipal} of the request. */
  static final String PRINCIPAL = "principal";

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
    String scoped = scopedCustomer(toolContext);
    if (scoped == null) {
      throw deny(null, null, "no customer or user in the tool context");
    }
    ObjectNode args = arguments(toolInput);
    JsonNode requested = args.get(CUSTOMER);
    if (requested != null && !requested.isNull()) {
      String value = requested.isTextual() ? requested.asText().trim() : requested.toString();
      if (!value.isEmpty() && !value.toLowerCase(Locale.ROOT).equals(scoped)) {
        throw deny(scoped, value, "the model asked for another customer");
      }
    }
    args.put(CUSTOMER, scoped);
    // no tool context: it holds the principal, which must not reach the ticket server
    return delegate.call(args.toString());
  }

  /** The request's customer, or null unless the context's user may act for it. */
  private static String scopedCustomer(@Nullable ToolContext toolContext) {
    if (toolContext == null) {
      return null;
    }
    if (!(toolContext.getContext().get(CUSTOMER) instanceof String customer)
        || customer.isBlank()) {
      return null;
    }
    String c = customer.trim().toLowerCase(Locale.ROOT);
    return toolContext.getContext().get(PRINCIPAL) instanceof HelpdeskPrincipal principal
            && principal.canAccess(c)
        ? c
        : null;
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

  private ToolExecutionException deny(String scoped, String requested, String reason) {
    String tool = getToolDefinition().name();
    log.warn(
        "Tool {} denied: {} (request customer {}, requested customer {})",
        tool,
        reason,
        scoped,
        printable(requested));
    meters.counter(DENIED_METRIC, "tool", tool).increment();
    return new ToolExecutionException(getToolDefinition(), new CustomerNotAllowedException());
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
