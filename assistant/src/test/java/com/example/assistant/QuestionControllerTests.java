package com.example.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(QuestionController.class)
@Import({ApiKeyFilter.class, RequestIdFilter.class, ApiExceptionHandler.class})
@TestPropertySource(
    properties = {"helpdesk.security.api-key=secret", "helpdesk.max-question-length=50"})
@EnableConfigurationProperties(HelpdeskProperties.class)
class QuestionControllerTests {

  @Autowired MockMvc mvc;

  @MockitoBean RequestLogClient requestLog;

  private ResultActions add(String body, String key) throws Exception {
    var req = post("/questions").contentType(MediaType.APPLICATION_JSON).content(body);
    if (key != null) {
      req.header("X-API-Key", key);
    }
    return mvc.perform(req);
  }

  @Test
  void listsQuestions() throws Exception {
    given(requestLog.questions())
        .willReturn(List.of(new RequestLogClient.CatalogQuestion(1, "VPN?")));
    mvc.perform(get("/questions").header("X-API-Key", "secret"))
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
    add("{\"text\":\"" + "x".repeat(51) + "\"}", "secret").andExpect(status().isBadRequest());
    verify(requestLog, never()).addQuestion(any());
  }

  @Test
  void requiresApiKey() throws Exception {
    mvc.perform(get("/questions")).andExpect(status().isUnauthorized());
    add("{\"text\":\"q\"}", null).andExpect(status().isUnauthorized());
  }

  @Test
  void logServerFailureIsA503() throws Exception {
    given(requestLog.questions()).willThrow(new RequestLogUnavailableException("down", null));
    mvc.perform(get("/questions").header("X-API-Key", "secret"))
        .andExpect(status().isServiceUnavailable());
  }
}
