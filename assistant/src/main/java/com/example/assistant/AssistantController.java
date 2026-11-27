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

  private final AskRateLimiter rateLimiter;

  AssistantController(
      AssistantService service,
      HelpdeskProperties props,
      TicketDirectoryClient tickets,
      AskRateLimiter rateLimiter) {
    this.service = service;
    this.props = props;
    this.tickets = tickets;
    this.rateLimiter = rateLimiter;
  }

  /**
   * The customer is required (K3): it must be in the caller's token (403 otherwise) and known to
   * the ticket server (400 otherwise). Hard upper bound on the question; the configurable
   * helpdesk.max-question-length is checked in the handler. Every ask with a valid body counts
   * towards the caller's per-minute limit ({@link AskRateLimiter}, 429 when it is used up), also
   * one that is then denied for its customer, so the limit also slows down probing.
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
    rateLimiter.acquire(principal);
    principal.requireAccess(q.customer());
    return new Answer(service.ask(q.question(), tickets.requireKnown(q.customer()), principal));
  }
}
