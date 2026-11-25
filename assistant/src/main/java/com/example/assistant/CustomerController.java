package com.example.assistant;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer names for the UI's customer dropdown, read from the ticket server: only the customers in
 * the caller's token (K3), or all of them for a user with {@code customers: ["*"]}.
 */
@RestController
class CustomerController {

  private final TicketDirectoryClient tickets;

  CustomerController(TicketDirectoryClient tickets) {
    this.tickets = tickets;
  }

  @GetMapping("/customers")
  List<String> customers(Authentication authentication) {
    HelpdeskPrincipal principal = HelpdeskPrincipal.require(authentication);
    return tickets.customers().stream().filter(principal::canAccess).toList();
  }
}
