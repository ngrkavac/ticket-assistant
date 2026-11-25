package com.example.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The questions offered in the UI, stored by the request-log server. Every helpdesk user can list
 * them; only {@code helpdesk-admin} can add one (enforced by {@link SecurityConfig}). Adding the
 * same text twice returns the existing question. Catalog questions are limited to
 * helpdesk.max-catalog-question-length characters (the request-log server's own limit), which is
 * separate from the /ask limit.
 */
@RestController
class QuestionController {

  private final RequestLogClient requestLog;

  private final HelpdeskProperties props;

  QuestionController(RequestLogClient requestLog, HelpdeskProperties props) {
    this.requestLog = requestLog;
    this.props = props;
  }

  record NewQuestion(@NotBlank @Size(max = 10000) String text) {}

  @GetMapping("/questions")
  List<RequestLogClient.CatalogQuestion> questions() {
    return requestLog.questions();
  }

  @PostMapping("/questions")
  @ResponseStatus(HttpStatus.CREATED)
  RequestLogClient.CatalogQuestion add(@Valid @RequestBody NewQuestion q) {
    String text = q.text().trim();
    if (text.length() > props.maxCatalogQuestionLength()) {
      throw new QuestionTooLongException(props.maxCatalogQuestionLength());
    }
    return requestLog.addQuestion(text);
  }
}
