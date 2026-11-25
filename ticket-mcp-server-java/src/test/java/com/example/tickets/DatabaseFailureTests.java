package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** A database failure reaches the MCP client as a generic message, never as SQL or JDBC details. */
class DatabaseFailureTests {

  private static final String UNAVAILABLE =
      "Ticket database is unavailable, please try again later";

  private static DataAccessResourceFailureException failure() {
    return new DataAccessResourceFailureException("Connection to db:5432 refused; SELECT ...");
  }

  /** A repository whose every call fails like an unreachable database. */
  private final TicketTools tools =
      new TicketTools(
          new TicketRepository(null) {
            @Override
            List<String> customers() {
              throw failure();
            }

            @Override
            boolean customerExists(String customer) {
              throw failure();
            }

            @Override
            Optional<Ticket> find(String customer, long number) {
              throw failure();
            }

            @Override
            List<Ticket> findByCustomer(String customer, String status) {
              throw failure();
            }

            @Override
            List<Ticket> findOpenByCustomer(String customer) {
              throw failure();
            }
          });

  @Test
  void everyToolHidesDatabaseErrors() {
    assertThatThrownBy(tools::listCustomers)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(UNAVAILABLE)
        .hasNoCause();
    assertThatThrownBy(() -> tools.getTicket("acme", "T-1001")).hasMessage(UNAVAILABLE);
    assertThatThrownBy(() -> tools.listTickets("acme", null)).hasMessage(UNAVAILABLE);
    assertThatThrownBy(() -> tools.listOpenTickets("acme")).hasMessage(UNAVAILABLE);
    assertThatThrownBy(() -> tools.createTicket("acme", "x", "LOW")).hasMessage(UNAVAILABLE);
  }

  @Test
  void validationStillComesFirst() {
    assertThatThrownBy(() -> tools.getTicket("acme", "bad"))
        .hasMessage("Invalid ticket id, expected format T-1234");
    assertThatThrownBy(() -> tools.listTickets("acme", "CLOSED"))
        .hasMessage("Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED");
  }
}
