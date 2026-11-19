package com.example.assistant;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Customer names for the UI's customer dropdown, read from the ticket server. */
@RestController
class CustomerController {

  private final TicketDirectoryClient tickets;

  CustomerController(TicketDirectoryClient tickets) {
    this.tickets = tickets;
  }

  @GetMapping("/customers")
  List<String> customers() {
    return tickets.customers();
  }
}
