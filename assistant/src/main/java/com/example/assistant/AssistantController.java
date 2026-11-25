package com.example.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AssistantController {

  private final AssistantService service;

  private final HelpdeskProperties props;

  private final TicketDirectoryClient tickets;

  AssistantController(
      AssistantService service, HelpdeskProperties props, TicketDirectoryClient tickets) {
    this.service = service;
    this.props = props;
    this.tickets = tickets;
  }

  /**
   * The customer is required (K3): it must be in the caller's token (403 otherwise) and known to
   * the ticket server (400 otherwise). Hard upper bound on the question; the configurable
   * helpdesk.max-question-length is checked in the handler.
   */
  record Question(
      @NotBlank @Size(max = 10000) String question, @NotBlank @Size(max = 64) String customer) {}

  record Answer(String answer) {}

  @PostMapping("/ask")
  Answer ask(@Valid @RequestBody Question q, Authentication authentication) {
    if (q.question().length() > props.maxQuestionLength()) {
      throw new QuestionTooLongException(props.maxQuestionLength());
    }
    // checked before the ticket server is asked, so callers cannot probe which customers exist
    HelpdeskPrincipal principal = HelpdeskPrincipal.require(authentication);
    principal.requireAccess(q.customer());
    return new Answer(service.ask(q.question(), tickets.requireKnown(q.customer()), principal));
  }
}
