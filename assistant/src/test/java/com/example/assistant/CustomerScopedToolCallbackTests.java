package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

class CustomerScopedToolCallbackTests {

  private static final String TICKET_SCHEMA =
      """
      {"type":"object","properties":{"customer":{"type":"string"},"id":{"type":"string"}},
       "required":["customer","id"]}
      """;

  private static final HelpdeskPrincipal ACME_AGENT =
      new HelpdeskPrincipal(
          "sub-1", "acme-agent", Set.of(SecurityConfig.ROLE_AGENT), Set.of("acme"), false);

  private static final ObjectMapper JSON = new ObjectMapper();

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  /** What the wrapped tool was called with, and whether it got a tool context. */
  private final List<String> calls = new CopyOnWriteArrayList<>();

  private final List<ToolContext> contexts = new CopyOnWriteArrayList<>();

  private ToolCallback tool(String name, String schema) {
    ToolDefinition definition =
        ToolDefinition.builder().name(name).description("d").inputSchema(schema).build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().returnDirect(true).build();
      }

      @Override
      public String call(String toolInput) {
        calls.add(toolInput);
        return "result";
      }

      @Override
      public String call(String toolInput, ToolContext toolContext) {
        contexts.add(toolContext);
        return call(toolInput);
      }
    };
  }

  private CustomerScopedToolCallback scoped() {
    return new CustomerScopedToolCallback(tool("getTicket", TICKET_SCHEMA), meters);
  }

  private static ToolContext context(String customer, Object principal) {
    Map<String, Object> map = new HashMap<>();
    map.put(CustomerScopedToolCallback.CUSTOMER, customer);
    map.put(CustomerScopedToolCallback.PRINCIPAL, principal);
    map.values().removeIf(v -> v == null);
    return new ToolContext(map);
  }

  private double denied() {
    var counter = meters.find(CustomerScopedToolCallback.DENIED_METRIC).tag("tool", "getTicket");
    return counter.counter() == null ? 0 : counter.counter().count();
  }

  private Map<String, Object> forwarded() throws Exception {
    assertThat(calls).hasSize(1);
    return JSON.readValue(
        calls.get(0), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
  }

  @Test
  void passesTheRequestsCustomerThrough() throws Exception {
    String result =
        scoped().call("{\"customer\":\"acme\",\"id\":\"T-1001\"}", context("acme", ACME_AGENT));
    assertThat(result).isEqualTo("result");
    assertThat(forwarded()).containsEntry("customer", "acme").containsEntry("id", "T-1001");
    assertThat(denied()).isZero();
  }

  @Test
  void fillsInAMissingOrBlankCustomer() throws Exception {
    scoped().call("{\"id\":\"T-1001\"}", context("acme", ACME_AGENT));
    assertThat(forwarded()).containsEntry("customer", "acme");

    calls.clear();
    scoped().call("{\"customer\":\" \",\"id\":\"T-1001\"}", context("acme", ACME_AGENT));
    assertThat(forwarded()).containsEntry("customer", "acme");

    calls.clear();
    scoped().call("{\"customer\":null,\"id\":\"T-1001\"}", context("acme", ACME_AGENT));
    assertThat(forwarded()).containsEntry("customer", "acme");
  }

  @Test
  void emptyInputGetsTheCustomer() throws Exception {
    scoped().call("", context("acme", ACME_AGENT));
    assertThat(forwarded()).containsEntry("customer", "acme");
  }

  @Test
  void normalizesTheSameCustomerInAnotherSpelling() throws Exception {
    scoped().call("{\"customer\":\" ACME \",\"id\":\"T-1001\"}", context("acme", ACME_AGENT));
    assertThat(forwarded()).containsEntry("customer", "acme");
  }

  @Test
  void anotherCustomerIsDeniedWithoutCallingTheTool() {
    assertThatThrownBy(
            () ->
                scoped()
                    .call(
                        "{\"customer\":\"globex\",\"id\":\"T-1003\"}", context("acme", ACME_AGENT)))
        .isInstanceOf(ToolExecutionException.class)
        .hasMessageContaining("Not allowed for this customer")
        .satisfies(
            e ->
                assertThat(((ToolExecutionException) e).getToolDefinition().name())
                    .isEqualTo("getTicket"));
    assertThat(calls).isEmpty();
    assertThat(denied()).isEqualTo(1);
  }

  @Test
  void aNonTextCustomerIsDenied() {
    assertThatThrownBy(
            () -> scoped().call("{\"customer\":[\"globex\"]}", context("acme", ACME_AGENT)))
        .hasMessageContaining("Not allowed for this customer");
    assertThat(calls).isEmpty();
  }

  @Test
  void withoutToolContextTheCallIsDenied() {
    assertThatThrownBy(() -> scoped().call("{\"customer\":\"acme\"}"))
        .hasMessageContaining("Not allowed for this customer");
    assertThatThrownBy(() -> scoped().call("{\"customer\":\"acme\"}", null))
        .hasMessageContaining("Not allowed for this customer");
    assertThatThrownBy(() -> scoped().call("{\"customer\":\"acme\"}", new ToolContext(Map.of())))
        .hasMessageContaining("Not allowed for this customer");
    assertThat(calls).isEmpty();
    assertThat(denied()).isEqualTo(3);
  }

  @Test
  void withoutAUserWhoMayActForTheCustomerTheCallIsDenied() {
    assertThatThrownBy(() -> scoped().call("{}", context("acme", null)))
        .hasMessageContaining("Not allowed for this customer");
    assertThatThrownBy(() -> scoped().call("{}", context("acme", "not a principal")))
        .hasMessageContaining("Not allowed for this customer");
    assertThatThrownBy(() -> scoped().call("{}", context("globex", ACME_AGENT)))
        .hasMessageContaining("Not allowed for this customer");
    assertThatThrownBy(() -> scoped().call("{}", context(" ", ACME_AGENT)))
        .hasMessageContaining("Not allowed for this customer");
    assertThat(calls).isEmpty();
  }

  @Test
  void anAdminMayActForAnyCustomerButStillOnlyForTheRequestsOne() throws Exception {
    var admin =
        new HelpdeskPrincipal("sub-2", "admin", Set.of(SecurityConfig.ROLE_ADMIN), Set.of(), true);
    scoped().call("{\"customer\":\"globex\"}", context("globex", admin));
    assertThat(forwarded()).containsEntry("customer", "globex");
    assertThatThrownBy(() -> scoped().call("{\"customer\":\"acme\"}", context("globex", admin)))
        .hasMessageContaining("Not allowed for this customer");
  }

  @Test
  void theToolContextIsNotForwarded() {
    scoped().call("{}", context("acme", ACME_AGENT));
    assertThat(calls).hasSize(1);
    assertThat(contexts).isEmpty(); // the principal must not reach the MCP request's _meta
  }

  @Test
  void invalidArgumentsAreAToolError() {
    assertThatThrownBy(() -> scoped().call("not json", context("acme", ACME_AGENT)))
        .isInstanceOf(ToolExecutionException.class)
        .hasMessageContaining("Invalid tool arguments");
    assertThatThrownBy(() -> scoped().call("[1]", context("acme", ACME_AGENT)))
        .hasMessageContaining("Invalid tool arguments");
    assertThat(calls).isEmpty();
    assertThat(denied()).isZero();
  }

  @Test
  void keepsTheDefinitionAndMetadataOfTheTool() {
    CustomerScopedToolCallback scoped = scoped();
    assertThat(scoped.getToolDefinition().name()).isEqualTo("getTicket");
    assertThat(scoped.getToolDefinition().inputSchema()).isEqualTo(TICKET_SCHEMA);
    assertThat(scoped.getToolMetadata().returnDirect()).isTrue();
  }

  @Test
  void longOrOddRequestedCustomersAreDeniedToo() {
    String odd = "globex\\n" + "x".repeat(100);
    assertThatThrownBy(
            () -> scoped().call("{\"customer\":\"" + odd + "\"}", context("acme", ACME_AGENT)))
        .hasMessageContaining("Not allowed for this customer");
  }

  @Test
  void onlyToolsWithACustomerArgumentCanBeScoped() {
    assertThat(CustomerScopedToolCallback.takesCustomer(tool("getTicket", TICKET_SCHEMA))).isTrue();
    assertThat(
            CustomerScopedToolCallback.takesCustomer(
                tool("listCustomers", "{\"type\":\"object\",\"properties\":{}}")))
        .isFalse();
    assertThat(CustomerScopedToolCallback.takesCustomer(tool("x", "{\"type\":\"object\"}")))
        .isFalse();
    assertThat(CustomerScopedToolCallback.takesCustomer(tool("x", "not json"))).isFalse();
  }
}
