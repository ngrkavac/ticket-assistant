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
    mvc.perform(get("/requests").with(jwt()))
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
    mvc.perform(get("/requests?limit=5&customer=Acme").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].customer").value("acme"));
  }

  @Test
  void rejectsInvalidCustomer() throws Exception {
    mvc.perform(get("/requests?customer=a%20b").with(jwt())).andExpect(status().isBadRequest());
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/requests")).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsLimitOutOfRange() throws Exception {
    mvc.perform(get("/requests?limit=0").with(jwt())).andExpect(status().isBadRequest());
    mvc.perform(get("/requests?limit=101").with(jwt())).andExpect(status().isBadRequest());
  }

  @Test
  void logServerFailureIsA503() throws Exception {
    given(requestLog.recent(5, null)).willThrow(new RequestLogUnavailableException("down", null));
    mvc.perform(get("/requests?limit=5").with(jwt()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value("Request history is temporarily unavailable."));
  }
}
