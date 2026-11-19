package com.example.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AssistantController.class)
@Import({ApiKeyFilter.class, RequestIdFilter.class, ApiExceptionHandler.class})
@TestPropertySource(
    properties = {"helpdesk.security.api-key=secret", "helpdesk.max-question-length=50"})
@org.springframework.boot.context.properties.EnableConfigurationProperties(HelpdeskProperties.class)
class AssistantControllerTests {

  @Autowired MockMvc mvc;

  @MockitoBean AssistantService service;

  @MockitoBean TicketDirectoryClient tickets;

  private org.springframework.test.web.servlet.ResultActions ask(String body, String key)
      throws Exception {
    var req = post("/ask").contentType(MediaType.APPLICATION_JSON).content(body);
    if (key != null) {
      req.header("X-API-Key", key);
    }
    return mvc.perform(req);
  }

  @Test
  void answersWithValidKey() throws Exception {
    given(service.ask("hi", null)).willReturn("hello");
    ask("{\"question\":\"hi\"}", "secret")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("hello"))
        .andExpect(header().exists("X-Request-Id"));
  }

  @Test
  void passesTheKnownCustomerToTheService() throws Exception {
    given(tickets.requireKnown("ACME")).willReturn("acme");
    given(service.ask("hi", "acme")).willReturn("hello acme");
    ask("{\"question\":\"hi\",\"customer\":\"ACME\"}", "secret")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("hello acme"));
  }

  @Test
  void unknownCustomerIs400() throws Exception {
    given(tickets.requireKnown("umbrella")).willThrow(new UnknownCustomerException());
    ask("{\"question\":\"hi\",\"customer\":\"umbrella\"}", "secret")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Unknown customer"));
  }

  @Test
  void ticketServerDownWhileCheckingCustomerIs503() throws Exception {
    given(tickets.requireKnown("acme")).willThrow(new TicketsUnavailableException("down", null));
    ask("{\"question\":\"hi\",\"customer\":\"acme\"}", "secret")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.detail").value("The ticket system is temporarily unavailable."));
  }

  @Test
  void rejectsMissingOrWrongKey() throws Exception {
    ask("{\"question\":\"hi\"}", null).andExpect(status().isUnauthorized());
    ask("{\"question\":\"hi\"}", "nope").andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsBlankQuestion() throws Exception {
    ask("{\"question\":\"  \"}", "secret").andExpect(status().isBadRequest());
  }

  @Test
  void rejectsTooLongQuestion() throws Exception {
    ask("{\"question\":\"" + "x".repeat(51) + "\"}", "secret").andExpect(status().isBadRequest());
  }

  @Test
  void busyIs429WithRetryAfter() throws Exception {
    given(service.ask(anyString(), any())).willThrow(new TooManyRequestsException());
    ask("{\"question\":\"hi\"}", "secret")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "5"));
  }

  @Test
  void retryAfterComesFromTheRejection() throws Exception {
    given(service.ask(anyString(), any()))
        .willThrow(new TooManyRequestsException(java.time.Duration.ofMillis(1500)));
    ask("{\"question\":\"hi\"}", "secret")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"));
  }

  @Test
  void keepsAValidIncomingRequestId() throws Exception {
    given(service.ask("hi", null)).willReturn("hello");
    mvc.perform(
            post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\"}")
                .header("X-API-Key", "secret")
                .header("X-Request-Id", "ingress-1.2:abc_X-9"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", "ingress-1.2:abc_X-9"));
  }

  @Test
  void replacesAnInvalidIncomingRequestId() throws Exception {
    given(service.ask("hi", null)).willReturn("hello");
    mvc.perform(
            post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\"}")
                .header("X-API-Key", "secret")
                .header("X-Request-Id", "bad id\nwith newline"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
  }

  @Test
  void bugIs500WithoutLeakingDetails() throws Exception {
    given(service.ask(anyString(), any())).willThrow(new IllegalStateException("secret internals"));
    ask("{\"question\":\"hi\"}", "secret")
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("Internal error."));
  }

  @Test
  void upstreamFailureIsA503WithoutLeakingDetails() throws Exception {
    given(service.ask(anyString(), any()))
        .willThrow(
            new org.springframework.web.client.ResourceAccessException(
                "ollama at 10.0.0.5 timed out"));
    ask("{\"question\":\"hi\"}", "secret")
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            jsonPath("$.detail")
                .value("The assistant is temporarily unavailable. Please try again later."));
  }
}
