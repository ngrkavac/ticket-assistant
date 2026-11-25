package com.example.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AssistantController.class)
@Import({SecurityConfig.class, RequestIdFilter.class, ApiExceptionHandler.class})
@TestPropertySource(properties = {"helpdesk.max-question-length=50"})
@org.springframework.boot.context.properties.EnableConfigurationProperties(HelpdeskProperties.class)
class AssistantControllerTests {

  @Autowired MockMvc mvc;

  @MockitoBean JwtDecoder jwtDecoder;

  @MockitoBean AssistantService service;

  @MockitoBean TicketDirectoryClient tickets;

  private org.springframework.test.web.servlet.ResultActions ask(String body, String key)
      throws Exception {
    var req = post("/ask").contentType(MediaType.APPLICATION_JSON).content(body);
    if (key != null) {
      req.with(TestUsers.agent("acme"));
    }
    return mvc.perform(req);
  }

  @BeforeEach
  void knownCustomers() {
    given(tickets.requireKnown("acme")).willReturn("acme");
    given(tickets.requireKnown("globex")).willReturn("globex");
  }

  @Test
  void answersWithValidKey() throws Exception {
    given(service.ask("hi", "acme")).willReturn("hello");
    ask("{\"question\":\"hi\",\"customer\":\"acme\"}", "secret")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("hello"))
        .andExpect(header().exists("X-Request-Id"));
  }

  @Test
  void passesTheKnownCustomerToTheService() throws Exception {
    given(tickets.requireKnown("ACME")).willReturn("acme"); // the token says "acme"
    given(service.ask("hi", "acme")).willReturn("hello acme");
    ask("{\"question\":\"hi\",\"customer\":\"ACME\"}", "secret")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("hello acme"));
  }

  @Test
  void unknownCustomerIs400() throws Exception {
    given(tickets.requireKnown("umbrella")).willThrow(new UnknownCustomerException());
    mvc.perform(
            post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\",\"customer\":\"umbrella\"}")
                .with(TestUsers.admin()))
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
  void customerIsRequired() throws Exception {
    ask("{\"question\":\"hi\"}", "secret").andExpect(status().isBadRequest());
    ask("{\"question\":\"hi\",\"customer\":\" \"}", "secret").andExpect(status().isBadRequest());
    verify(service, never()).ask(anyString(), any());
  }

  @Test
  void customerNotInTheTokenIs403BeforeTheTicketServerIsAsked() throws Exception {
    mvc.perform(
            post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\",\"customer\":\"globex\"}")
                .with(TestUsers.agent("acme")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Not allowed for this customer"));
    verify(tickets, never()).requireKnown("globex");
    verify(service, never()).ask(anyString(), any());
  }

  @Test
  void userWithAllCustomersMayAskAboutAnyCustomer() throws Exception {
    given(service.ask("hi", "globex")).willReturn("hello globex");
    mvc.perform(
            post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\",\"customer\":\"globex\"}")
                .with(TestUsers.admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("hello globex"));
  }

  @Test
  void askNeedsTheAgentRole() throws Exception {
    for (var user :
        List.of(
            TestUsers.user(Set.of(SecurityConfig.ROLE_ADMIN), Set.of(), true),
            TestUsers.user(Set.of(SecurityConfig.ROLE_WRITER), Set.of("acme"), false),
            TestUsers.user(Set.of(), Set.of("acme"), false))) {
      mvc.perform(
              post("/ask")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\":\"hi\",\"customer\":\"acme\"}")
                  .with(user))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.title").value("Forbidden"));
    }
    verify(service, never()).ask(anyString(), any());
  }

  @Test
  void callerWithoutAHelpdeskPrincipalIs403() throws Exception {
    mvc.perform(
            post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\",\"customer\":\"acme\"}")
                .with(jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.ROLE_AGENT))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Access denied."));
    verify(service, never()).ask(anyString(), any());
  }

  @Test
  void requiresAuthentication() throws Exception {
    ask("{\"question\":\"hi\",\"customer\":\"acme\"}", null).andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsBlankQuestion() throws Exception {
    ask("{\"question\":\"  \",\"customer\":\"acme\"}", "secret").andExpect(status().isBadRequest());
  }

  @Test
  void rejectsTooLongQuestion() throws Exception {
    ask("{\"question\":\"" + "x".repeat(51) + "\",\"customer\":\"acme\"}", "secret")
        .andExpect(status().isBadRequest());
  }

  @Test
  void busyIs429WithRetryAfter() throws Exception {
    given(service.ask(anyString(), any())).willThrow(new TooManyRequestsException());
    ask("{\"question\":\"hi\",\"customer\":\"acme\"}", "secret")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "5"));
  }

  @Test
  void retryAfterComesFromTheRejection() throws Exception {
    given(service.ask(anyString(), any()))
        .willThrow(new TooManyRequestsException(java.time.Duration.ofMillis(1500)));
    ask("{\"question\":\"hi\",\"customer\":\"acme\"}", "secret")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "2"));
  }

  @Test
  void keepsAValidIncomingRequestId() throws Exception {
    given(service.ask("hi", "acme")).willReturn("hello");
    mvc.perform(
            post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\",\"customer\":\"acme\"}")
                .with(TestUsers.agent("acme"))
                .header("X-Request-Id", "ingress-1.2:abc_X-9"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", "ingress-1.2:abc_X-9"));
  }

  @Test
  void replacesAnInvalidIncomingRequestId() throws Exception {
    given(service.ask("hi", "acme")).willReturn("hello");
    mvc.perform(
            post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\",\"customer\":\"acme\"}")
                .with(TestUsers.agent("acme"))
                .header("X-Request-Id", "bad id with spaces & symbols!"))
        .andExpect(status().isOk())
        .andExpect(
            header().string("X-Request-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
  }

  @Test
  void controlCharacterInAHeaderIs400() throws Exception {
    // the security firewall rejects it when MVC reads the headers of the JSON request
    mvc.perform(
            post("/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"hi\",\"customer\":\"acme\"}")
                .with(TestUsers.agent("acme"))
                .header("X-Request-Id", "bad id\nwith newline"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Malformed request."));
  }

  @Test
  void bugIs500WithoutLeakingDetails() throws Exception {
    given(service.ask(anyString(), any())).willThrow(new IllegalStateException("secret internals"));
    ask("{\"question\":\"hi\",\"customer\":\"acme\"}", "secret")
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("Internal error."));
  }

  @Test
  void upstreamFailureIsA503WithoutLeakingDetails() throws Exception {
    given(service.ask(anyString(), any()))
        .willThrow(
            new org.springframework.web.client.ResourceAccessException(
                "ollama at 10.0.0.5 timed out"));
    ask("{\"question\":\"hi\",\"customer\":\"acme\"}", "secret")
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            jsonPath("$.detail")
                .value("The assistant is temporarily unavailable. Please try again later."));
  }
}
