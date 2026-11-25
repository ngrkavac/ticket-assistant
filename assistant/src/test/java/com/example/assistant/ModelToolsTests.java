package com.example.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

class ModelToolsTests {

  private static final String WITH_CUSTOMER =
      "{\"type\":\"object\",\"properties\":{\"customer\":{},\"id\":{}}}";

  private static ToolCallback tool(String name, String schema) {
    return FunctionToolCallback.builder(name, (Map<String, String> in) -> "x")
        .inputType(Map.class)
        .inputSchema(schema)
        .build();
  }

  private final ModelTools tools =
      new ModelTools(
          () ->
              new ToolCallback[] {
                tool("getTicket", WITH_CUSTOMER),
                tool("createTicket", WITH_CUSTOMER),
                tool("ping", "{\"type\":\"object\",\"properties\":{}}")
              },
          new SimpleMeterRegistry());

  private static HelpdeskPrincipal user(String... roles) {
    return new HelpdeskPrincipal("sub", "user", Set.of(roles), Set.of("acme"), false);
  }

  private static String[] names(Iterable<? extends ToolCallback> offered) {
    var list = new java.util.ArrayList<String>();
    offered.forEach(t -> list.add(t.getToolDefinition().name()));
    return list.toArray(String[]::new);
  }

  @Test
  void everyToolIsCustomerScopedAndToolsWithoutCustomerAreDropped() {
    assertThat(tools.all())
        .allSatisfy(t -> assertThat(t).isInstanceOf(CustomerScopedToolCallback.class));
    assertThat(names(tools.all())).containsExactly("getTicket", "createTicket");
  }

  @Test
  void createTicketIsOfferedOnlyWithTicketsWrite() {
    assertThat(names(tools.offeredTo(user(SecurityConfig.ROLE_AGENT))))
        .containsExactly("getTicket");
    assertThat(names(tools.offeredTo(user(SecurityConfig.ROLE_ADMIN))))
        .containsExactly("getTicket");
    assertThat(names(tools.offeredTo(user(SecurityConfig.ROLE_AGENT, SecurityConfig.ROLE_WRITER))))
        .containsExactly("getTicket", "createTicket");
    assertThat(names(tools.offeredTo(HelpdeskPrincipal.LOCAL)))
        .containsExactly("getTicket", "createTicket");
  }
}
