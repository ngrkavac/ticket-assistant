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

  /** What the wrapped tool returns, or the exception it throws when not null. */
  private String result = "result";

  private RuntimeException failure;

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
        if (failure != null) {
          throw failure;
        }
        return result;
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
    return context(customer, principal, null);
  }

  private static ToolContext context(String customer, Object principal, ToolCallAudit audit) {
    Map<String, Object> map = new HashMap<>();
    map.put(CustomerScopedToolCallback.CUSTOMER, customer);
    map.put(CustomerScopedToolCallback.PRINCIPAL, principal);
    map.put(CustomerScopedToolCallback.AUDIT, audit);
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

  private static final String CREATE_SCHEMA =
      """
      {"type":"object","properties":{"customer":{"type":"string"},"subject":{"type":"string"},
       "priority":{"type":"string"}}}
      """;

  private static final HelpdeskPrincipal ACME_WRITER =
      new HelpdeskPrincipal(
          "sub-3",
          "acme-writer",
          Set.of(SecurityConfig.ROLE_AGENT, SecurityConfig.ROLE_WRITER),
          Set.of("acme"),
          false);

  private CustomerScopedToolCallback createTicket() {
    return new CustomerScopedToolCallback(tool("createTicket", CREATE_SCHEMA), meters);
  }

  @Test
  void writeToolsAreOfferedOnlyToUsersWithTicketsWrite() {
    assertThat(createTicket().isWriteTool()).isTrue();
    assertThat(createTicket().offeredTo(ACME_WRITER)).isTrue();
    assertThat(createTicket().offeredTo(ACME_AGENT)).isFalse();
    assertThat(createTicket().offeredTo(null)).isFalse();
    assertThat(scoped().isWriteTool()).isFalse();
    assertThat(scoped().offeredTo(ACME_AGENT)).isTrue();
  }

  @Test
  void aWriteToolCalledWithoutTicketsWriteIsDeniedAndAudited() {
    ToolCallAudit audit = new ToolCallAudit();
    assertThatThrownBy(
            () -> createTicket().call("{\"subject\":\"VPN\"}", context("acme", ACME_AGENT, audit)))
        .isInstanceOf(ToolExecutionException.class)
        .hasMessageContaining(CustomerScopedToolCallback.WRITE_DENIED);
    assertThat(calls).isEmpty();
    assertThat(meters.find(CustomerScopedToolCallback.DENIED_METRIC).tag("tool", "createTicket"))
        .satisfies(c -> assertThat(c.counter().count()).isEqualTo(1));
    assertThat(audit.calls())
        .containsExactly(
            new ToolCallAudit.ToolCall("createTicket", "acme", ToolCallAudit.DENIED, null));
  }

  @Test
  void aCreatedTicketIsAuditedWithItsId() {
    result =
        "[{\"type\":\"text\",\"text\":\"{\\\"id\\\":\\\"T-1004\\\",\\\"customer\\\":\\\"acme\\\"}\"}]";
    ToolCallAudit audit = new ToolCallAudit();
    assertThat(createTicket().call("{\"subject\":\"VPN\"}", context("acme", ACME_WRITER, audit)))
        .isEqualTo(result);
    assertThat(audit.calls())
        .containsExactly(
            new ToolCallAudit.ToolCall("createTicket", "acme", ToolCallAudit.SUCCESS, "T-1004"));
  }

  @Test
  void readToolsAreAuditedWithoutAResultId() {
    result = "{\"id\":\"T-1001\"}";
    ToolCallAudit audit = new ToolCallAudit();
    scoped().call("{\"id\":\"T-1001\"}", context("acme", ACME_AGENT, audit));
    assertThat(audit.calls())
        .containsExactly(
            new ToolCallAudit.ToolCall("getTicket", "acme", ToolCallAudit.SUCCESS, null));
  }

  @Test
  void failedAndDeniedCallsAreAudited() {
    ToolCallAudit audit = new ToolCallAudit();
    failure = new ToolExecutionException(scoped().getToolDefinition(), new RuntimeException("x"));
    assertThatThrownBy(() -> scoped().call("{}", context("acme", ACME_AGENT, audit)))
        .isSameAs(failure);
    assertThatThrownBy(
            () -> scoped().call("{\"customer\":\"globex\"}", context("acme", ACME_AGENT, audit)))
        .hasMessageContaining(CustomerScopedToolCallback.CUSTOMER_DENIED);
    assertThatThrownBy(() -> scoped().call("{}", context("globex", ACME_AGENT, audit)))
        .hasMessageContaining(CustomerScopedToolCallback.CUSTOMER_DENIED);
    assertThat(audit.calls())
        .containsExactly(
            new ToolCallAudit.ToolCall("getTicket", "acme", ToolCallAudit.ERROR, null),
            new ToolCallAudit.ToolCall("getTicket", "acme", ToolCallAudit.DENIED, null),
            new ToolCallAudit.ToolCall("getTicket", null, ToolCallAudit.DENIED, null));
  }

  @Test
  void resultIdReadsTheIdOfTheReturnedObject() {
    assertThat(
            CustomerScopedToolCallback.resultId(
                "[{\"type\":\"text\",\"text\":\"{\\\"id\\\":\\\"T-7\\\"}\"}]"))
        .isEqualTo("T-7");
    assertThat(CustomerScopedToolCallback.resultId("{\"id\":\"T-8\"}")).isEqualTo("T-8");
    assertThat(CustomerScopedToolCallback.resultId("{\"id\":42}")).isEqualTo("42");
    assertThat(CustomerScopedToolCallback.resultId(null)).isNull();
    assertThat(CustomerScopedToolCallback.resultId("not json")).isNull();
    assertThat(CustomerScopedToolCallback.resultId("[]")).isNull();
    assertThat(CustomerScopedToolCallback.resultId("[{\"text\":\"plain\"}]")).isNull();
    assertThat(CustomerScopedToolCallback.resultId("{\"id\":{\"a\":1}}")).isNull();
    assertThat(CustomerScopedToolCallback.resultId("{\"id\":\" \"}")).isNull();
    assertThat(CustomerScopedToolCallback.resultId("\"T-1\"")).isNull();
  }
}
