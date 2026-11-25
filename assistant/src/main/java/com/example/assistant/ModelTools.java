package com.example.assistant;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

/**
 * The ticket tools offered to the model, chosen per request (T1.4, T1.5). Every tool is wrapped in
 * a {@link CustomerScopedToolCallback}, so each call is limited to the request's customer. A tool
 * without a {@code customer} argument cannot be scoped and is never offered (fail closed). Write
 * tools such as {@code createTicket} are offered only to users with the {@code tickets-write} role,
 * so the model of any other user does not even know they exist.
 *
 * <p>The MCP tools are resolved on every request, so tools the server adds later are scoped too.
 */
class ModelTools {

  private final ToolCallbackProvider mcpTools;

  private final MeterRegistry meters;

  ModelTools(ToolCallbackProvider mcpTools, MeterRegistry meters) {
    this.mcpTools = mcpTools;
    this.meters = meters;
  }

  /** Every tool the model may be offered, customer-scoped. */
  List<CustomerScopedToolCallback> all() {
    return Arrays.stream(mcpTools.getToolCallbacks())
        .filter(CustomerScopedToolCallback::takesCustomer)
        .map(tool -> new CustomerScopedToolCallback(tool, meters))
        .toList();
  }

  /** The tools offered to the model for a request by {@code principal}. */
  List<ToolCallback> offeredTo(HelpdeskPrincipal principal) {
    return all().stream()
        .filter(tool -> tool.offeredTo(principal))
        .map(ToolCallback.class::cast)
        .toList();
  }
}
