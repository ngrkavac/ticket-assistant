package com.example.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AssistantController {

  private final AssistantService service;

  private final HelpdeskProperties props;

  AssistantController(AssistantService service, HelpdeskProperties props) {
    this.service = service;
    this.props = props;
  }

  // Hard upper bound; the configurable helpdesk.max-question-length is checked in the handler.
  record Question(@NotBlank @Size(max = 10000) String question) {}

  record Answer(String answer) {}

  @PostMapping("/ask")
  Answer ask(@Valid @RequestBody Question q) {
    if (q.question().length() > props.maxQuestionLength()) {
      throw new QuestionTooLongException(props.maxQuestionLength());
    }
    return new Answer(service.ask(q.question()));
  }
}
