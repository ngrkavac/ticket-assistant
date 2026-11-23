package com.example.assistant;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
  void listsCustomers() throws Exception {
    given(tickets.customers()).willReturn(List.of("acme", "globex"));
    mvc.perform(get("/customers").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("acme"))
        .andExpect(jsonPath("$[1]").value("globex"));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/customers")).andExpect(status().isUnauthorized());
  }

  @Test
  void ticketServerFailureIsA503() throws Exception {
    given(tickets.customers()).willThrow(new TicketsUnavailableException("down", null));
    mvc.perform(get("/customers").with(jwt()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value("The ticket system is temporarily unavailable."));
  }
}
