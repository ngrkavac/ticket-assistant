package com.example.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(QuestionController.class)
@Import({SecurityConfig.class, RequestIdFilter.class, ApiExceptionHandler.class})
@TestPropertySource(properties = {"helpdesk.max-question-length=50"})
@EnableConfigurationProperties(HelpdeskProperties.class)
class QuestionControllerTests {

  @Autowired MockMvc mvc;

  @MockitoBean JwtDecoder jwtDecoder;

  @MockitoBean RequestLogClient requestLog;

  private ResultActions add(String body, String key) throws Exception {
    var req = post("/questions").contentType(MediaType.APPLICATION_JSON).content(body);
    if (key != null) {
      req.with(jwt());
    }
    return mvc.perform(req);
  }

  @Test
  void listsQuestions() throws Exception {
    given(requestLog.questions())
        .willReturn(List.of(new RequestLogClient.CatalogQuestion(1, "VPN?")));
    mvc.perform(get("/questions").with(jwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1))
        .andExpect(jsonPath("$[0].text").value("VPN?"));
  }

  @Test
  void addsTrimmedQuestion() throws Exception {
    given(requestLog.addQuestion("Printer?"))
        .willReturn(new RequestLogClient.CatalogQuestion(7, "Printer?"));
    add("{\"text\":\"  Printer? \"}", "secret")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(7))
        .andExpect(jsonPath("$.text").value("Printer?"));
  }

  @Test
  void rejectsBlankOrTooLongQuestion() throws Exception {
    add("{\"text\":\"  \"}", "secret").andExpect(status().isBadRequest());
    add("{\"text\":\"" + "x".repeat(1001) + "\"}", "secret")
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail").value("Question exceeds the maximum length of 1000 characters"));
    verify(requestLog, never()).addQuestion(any());
  }

  @Test
  void catalogLimitIsSeparateFromTheAskLimit() throws Exception {
    String text = "x".repeat(1000); // longer than helpdesk.max-question-length=50
    given(requestLog.addQuestion(text)).willReturn(new RequestLogClient.CatalogQuestion(8, text));
    add("{\"text\":\"" + text + "\"}", "secret").andExpect(status().isCreated());
  }

  @Test
  void requestLogValidationErrorIsA400() throws Exception {
    given(requestLog.addQuestion("q"))
        .willThrow(new RequestLogRejectedException("addQuestion rejected: text is invalid"));
    add("{\"text\":\"q\"}", "secret")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("The request was rejected as invalid."));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mvc.perform(get("/questions")).andExpect(status().isUnauthorized());
    add("{\"text\":\"q\"}", null).andExpect(status().isUnauthorized());
  }

  @Test
  void logServerFailureIsA503() throws Exception {
    given(requestLog.questions()).willThrow(new RequestLogUnavailableException("down", null));
    mvc.perform(get("/questions").with(jwt())).andExpect(status().isServiceUnavailable());
  }
}
