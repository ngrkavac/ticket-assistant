package com.example.tickets;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC access to the {@code customer} and {@code ticket} tables (schema in {@code
 * db/migration/V1__tickets.sql}). Customer ids passed in are already normalized (trimmed, lower
 * case). Ticket ids are the numeric part of {@code T-<number>}.
 */
@Repository
class TicketRepository {

  private static final String COLUMNS = "id, customer, subject, status, priority";

  private final JdbcClient jdbc;

  TicketRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** All customer ids, sorted by {@link String#compareTo} (independent of the DB collation). */
  List<String> customers() {
    return jdbc.sql("SELECT id FROM customer").query(String.class).list().stream()
        .sorted()
        .toList();
  }

  boolean customerExists(String customer) {
    return jdbc.sql("SELECT COUNT(*) FROM customer WHERE id = :customer")
            .param("customer", customer)
            .query(Long.class)
            .single()
        > 0;
  }

  Optional<Ticket> find(String customer, long number) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM ticket WHERE id = :id AND customer = :customer")
        .param("id", number)
        .param("customer", customer)
        .query(TicketRepository::map)
        .optional();
  }

  /** Tickets of the customer in id order; {@code status} null means all. */
  List<Ticket> findByCustomer(String customer, String status) {
    String where = status == null ? "" : " AND status = :status";
    JdbcClient.StatementSpec spec =
        jdbc.sql(
                "SELECT "
                    + COLUMNS
                    + " FROM ticket WHERE customer = :customer"
                    + where
                    + " ORDER BY id")
            .param("customer", customer);
    if (status != null) {
      spec = spec.param("status", status);
    }
    return spec.query(TicketRepository::map).list();
  }

  /** Tickets of the customer that are not RESOLVED, in id order. */
  List<Ticket> findOpenByCustomer(String customer) {
    return jdbc.sql(
            "SELECT "
                + COLUMNS
                + " FROM ticket WHERE customer = :customer AND status <> 'RESOLVED' ORDER BY id")
        .param("customer", customer)
        .query(TicketRepository::map)
        .list();
  }

  /**
   * Inserts an OPEN ticket with the next number from {@code ticket_number_seq}. The sequence makes
   * concurrent creates (also from other replicas) get unique ids without any lock.
   */
  @Transactional
  Ticket create(String customer, String subject, String priority) {
    long number =
        jdbc.sql("SELECT nextval('ticket_number_seq')").query(Long.class).single().longValue();
    jdbc.sql(
            "INSERT INTO ticket (id, customer, subject, status, priority)"
                + " VALUES (:id, :customer, :subject, 'OPEN', :priority)")
        .param("id", number)
        .param("customer", customer)
        .param("subject", subject)
        .param("priority", priority)
        .update();
    return new Ticket(Ticket.formatId(number), customer, subject, "OPEN", priority);
  }

  private static Ticket map(ResultSet rs, int row) throws SQLException {
    return new Ticket(
        Ticket.formatId(rs.getLong("id")),
        rs.getString("customer"),
        rs.getString("subject"),
        rs.getString("status"),
        rs.getString("priority"));
  }
}
