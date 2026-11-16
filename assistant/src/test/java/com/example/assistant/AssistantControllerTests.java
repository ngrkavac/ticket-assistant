package com.example.assistant;

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
    given(service.ask("hi")).willReturn("hello");
    ask("{\"question\":\"hi\"}", "secret")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.answer").value("hello"))
        .andExpect(header().exists("X-Request-Id"));
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
    given(service.ask(anyString())).willThrow(new TooManyRequestsException());
    ask("{\"question\":\"hi\"}", "secret")
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "5"));
  }

  @Test
  void bugIs500WithoutLeakingDetails() throws Exception {
    given(service.ask(anyString())).willThrow(new IllegalStateException("secret internals"));
    ask("{\"question\":\"hi\"}", "secret")
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("Internal error."));
  }

  @Test
  void upstreamFailureIsA503WithoutLeakingDetails() throws Exception {
    given(service.ask(anyString()))
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
