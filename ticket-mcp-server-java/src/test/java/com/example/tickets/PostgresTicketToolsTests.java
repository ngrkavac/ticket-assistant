package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The tool suite on real PostgreSQL (Testcontainers, needs Docker), plus checks of the schema
 * itself.
 */
@Testcontainers
class PostgresTicketToolsTests extends AbstractTicketToolsTests {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired JdbcClient jdbc;

  @Test
  void runsOnPostgres() {
    assertThat(jdbc.sql("SELECT version()").query(String.class).single()).startsWith("PostgreSQL");
  }

  @Test
  void createdTicketIsStoredWithTimestamp() {
    Instant before = Instant.now().minusSeconds(5);
    Ticket created = tools.createTicket("globex", "Docking station dead", "LOW");

    var row =
        jdbc.sql("SELECT id, created_by, created_at FROM ticket WHERE id = :id")
            .param("id", Long.parseLong(created.id().substring(2)))
            .query()
            .singleRow();
    assertThat(row.get("created_by")).isNull();
    assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isAfter(before);
  }

  @Test
  void schemaRejectsInvalidRows() {
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO ticket (id, customer, subject, status, priority)"
                            + " VALUES (1, 'acme', 'x', 'CLOSED', 'LOW')")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ticket_status_valid");
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO ticket (id, customer, subject, status, priority)"
                            + " VALUES (1, 'acme', 'x', 'OPEN', 'URGENT')")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ticket_priority_valid");
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "INSERT INTO ticket (id, customer, subject, status, priority)"
                            + " VALUES (1, 'umbrella', 'x', 'OPEN', 'LOW')")
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.sql("INSERT INTO customer (id) VALUES ('Umbrella')").update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("customer_id_lower_case");
  }

  @Test
  void newCustomerCanGetTickets() {
    jdbc.sql("INSERT INTO customer (id) VALUES ('umbrella')").update();

    assertThat(tools.listCustomers()).containsExactly("acme", "globex", "initech", "umbrella");
    Ticket created = tools.createTicket("Umbrella", "First ticket", "MEDIUM");
    assertThat(created)
        .isEqualTo(new Ticket("T-1011", "umbrella", "First ticket", "OPEN", "MEDIUM"));
    assertThat(tools.listOpenTickets("umbrella")).containsExactly(created);
  }
}
