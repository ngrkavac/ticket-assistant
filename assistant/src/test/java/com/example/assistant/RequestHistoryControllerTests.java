package com.example.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

@WebMvcTest(RequestHistoryController.class)
@Import({SecurityConfig.class, RequestIdFilter.class, ApiExceptionHandler.class})
@EnableConfigurationProperties(HelpdeskProperties.class)
class RequestHistoryControllerTests {

  @Autowired MockMvc mvc;

  @MockitoBean JwtDecoder jwtDecoder;

  @MockitoBean RequestLogClient requestLog;

  @Test
  void returnsRecentRequests() throws Exception {
    given(requestLog.recent(20, null))
        .willReturn(
            List.of(
                new RequestLogClient.LoggedRequest(
                    "r-1", "VPN?", "SUCCESS", null, "2026-11-15T10:42:00Z")));
    mvc.perform(get("/requests").with(TestUsers.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].requestId").value("r-1"))
        .andExpect(jsonPath("$[0].question").value("VPN?"))
        .andExpect(jsonPath("$[0].answer").doesNotExist());
  }

  @Test
  void filtersByCustomer() throws Exception {
    given(requestLog.recent(5, "acme"))
        .willReturn(
            List.of(
                new RequestLogClient.LoggedRequest(
                    "r-2", "Tickets?", "SUCCESS", "acme", "2026-11-15T10:42:00Z")));
    mvc.perform(get("/requests?limit=5&customer=Acme").with(TestUsers.agent("acme")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].customer").value("acme"));
  }

  @Test
  void rejectsInvalidCustomer() throws Exception {
    mvc.perform(get("/requests?customer=a%20b").with(TestUsers.admin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void customerNotInTheTokenIs403() throws Exception {
    mvc.perform(get("/requests?customer=globex").with(TestUsers.agent("acme")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Not allowed for this customer"));
    verify(requestLog, never()).recent(anyInt(), any());
  }

  @Test
  void allCustomersQueryNeedsAUserWithAllCustomers() throws Exception {
    mvc.perform(get("/requests").with(TestUsers.agent("acme", "globex")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Not allowed for this customer"));
    verify(requestLog, never()).recent(anyInt(), any());
  }

  @Test
  void needsAHelpdeskRole() throws Exception {
    mvc.perform(
            get("/requests?customer=acme").with(TestUsers.user(Set.of(), Set.of("acme"), false)))
        .andExpect(status().isForbidden());
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/requests")).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsLimitOutOfRange() throws Exception {
    mvc.perform(get("/requests?limit=0").with(TestUsers.admin()))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/requests?limit=101").with(TestUsers.admin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void logServerFailureIsA503() throws Exception {
    given(requestLog.recent(5, null)).willThrow(new RequestLogUnavailableException("down", null));
    mvc.perform(get("/requests?limit=5").with(TestUsers.admin()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value("Request history is temporarily unavailable."));
  }
}
