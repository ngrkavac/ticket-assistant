package com.example.assistant;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerController.class)
@Import({SecurityConfig.class, RequestIdFilter.class, ApiExceptionHandler.class})
@EnableConfigurationProperties(HelpdeskProperties.class)
class CustomerControllerTests {

  @Autowired MockMvc mvc;

  @MockitoBean JwtDecoder jwtDecoder;

  @MockitoBean TicketDirectoryClient tickets;

  @Test
  void listsEveryCustomerForAUserWithAllCustomers() throws Exception {
    given(tickets.customers()).willReturn(List.of("acme", "globex"));
    mvc.perform(get("/customers").with(TestUsers.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0]").value("acme"))
        .andExpect(jsonPath("$[1]").value("globex"));
  }

  @Test
  void listsOnlyTheCustomersInTheToken() throws Exception {
    given(tickets.customers()).willReturn(List.of("acme", "globex", "initech"));
    mvc.perform(get("/customers").with(TestUsers.agent("acme", "initech", "umbrella")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0]").value("acme"))
        .andExpect(jsonPath("$[1]").value("initech"));
  }

  @Test
  void userWithoutCustomersGetsAnEmptyList() throws Exception {
    given(tickets.customers()).willReturn(List.of("acme"));
    mvc.perform(get("/customers").with(TestUsers.agent()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void needsAHelpdeskRole() throws Exception {
    mvc.perform(get("/customers").with(TestUsers.user(Set.of(), Set.of("acme"), false)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/customers")
                .with(TestUsers.user(Set.of(SecurityConfig.ROLE_ADMIN), Set.of(), true)))
        .andExpect(status().isOk());
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/customers")).andExpect(status().isUnauthorized());
  }

  @Test
  void ticketServerFailureIsA503() throws Exception {
    given(tickets.customers()).willThrow(new TicketsUnavailableException("down", null));
    mvc.perform(get("/customers").with(TestUsers.admin()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value("The ticket system is temporarily unavailable."));
  }
}
