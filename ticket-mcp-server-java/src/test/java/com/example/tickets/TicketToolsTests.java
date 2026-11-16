package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class TicketToolsTests {

  private final TicketTools tools = new TicketTools();

  private static List<String> ids(List<Ticket> tickets) {
    return tickets.stream().map(Ticket::id).toList();
  }

  // --- seed data ---

  @Test
  void listsKnownCustomersSorted() {
    assertThat(tools.listCustomers()).containsExactly("acme", "globex", "initech");
  }

  @Test
  void seedTicketsPerCustomer() {
    assertThat(ids(tools.listTickets("acme", null)))
        .containsExactly("T-1001", "T-1002", "T-1005", "T-1010");
    assertThat(ids(tools.listTickets("globex", null)))
        .containsExactly("T-1003", "T-1004", "T-1006");
    assertThat(ids(tools.listTickets("initech", null)))
        .containsExactly("T-1007", "T-1008", "T-1009");
  }

  @Test
  void seedTicketFields() {
    assertThat(tools.getTicket("acme", "T-1001"))
        .isEqualTo(new Ticket("T-1001", "acme", "VPN drops every 10 minutes", "OPEN", "HIGH"));
    assertThat(tools.getTicket("acme", "T-1005"))
        .isEqualTo(
            new Ticket("T-1005", "acme", "Outlook keeps asking for credentials", "OPEN", "MEDIUM"));
    assertThat(tools.getTicket("globex", "T-1006"))
        .isEqualTo(new Ticket("T-1006", "globex", "Monitor flickering", "RESOLVED", "LOW"));
    assertThat(tools.getTicket("initech", "T-1007"))
        .isEqualTo(
            new Ticket(
                "T-1007", "initech", "Shared drive not mapping after login", "OPEN", "HIGH"));
    assertThat(tools.getTicket("initech", "T-1008"))
        .isEqualTo(
            new Ticket(
                "T-1008", "initech", "Request new laptop for new hire", "IN_PROGRESS", "MEDIUM"));
    assertThat(tools.getTicket("initech", "T-1009"))
        .isEqualTo(
            new Ticket("T-1009", "initech", "Wi-Fi slow in meeting room B", "RESOLVED", "LOW"));
    assertThat(tools.getTicket("acme", "T-1010"))
        .isEqualTo(
            new Ticket("T-1010", "acme", "MFA codes not arriving by SMS", "IN_PROGRESS", "HIGH"));
  }

  // --- getTicket ---

  @Test
  void findsTicketCaseInsensitively() {
    assertThat(tools.getTicket(" ACME ", " t-1001 ").subject()).contains("VPN");
  }

  @Test
  void ticketOfAnotherCustomerIsNotFound() {
    assertThatThrownBy(() -> tools.getTicket("globex", " T-1001 "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Ticket T-1001 not found");
  }

  @Test
  void unknownCustomerTicketIsNotFound() {
    assertThatThrownBy(() -> tools.getTicket("umbrella", "t-1001"))
        .hasMessage("Ticket t-1001 not found");
  }

  @Test
  void unknownTicketFails() {
    assertThatThrownBy(() -> tools.getTicket("acme", "T-9999"))
        .hasMessage("Ticket T-9999 not found");
  }

  @Test
  void malformedIdIsRejected() {
    assertThatThrownBy(() -> tools.getTicket("acme", "1001; drop"))
        .hasMessage("Invalid ticket id, expected format T-1234");
    assertThatThrownBy(() -> tools.getTicket("acme", null))
        .hasMessage("Invalid ticket id, expected format T-1234");
  }

  @Test
  void getTicketRequiresCustomerBeforeId() {
    assertThatThrownBy(() -> tools.getTicket(" ", "bad")).hasMessage("Customer name is required");
    assertThatThrownBy(() -> tools.getTicket(null, "T-1001"))
        .hasMessage("Customer name is required");
  }

  // --- listOpenTickets ---

  @Test
  void listsOnlyOpenTicketsOfCustomer() {
    assertThat(ids(tools.listOpenTickets(" Acme "))).containsExactly("T-1001", "T-1005", "T-1010");
  }

  @Test
  void openTicketsOfUnknownCustomerIsEmpty() {
    assertThat(tools.listOpenTickets("umbrella")).isEmpty();
  }

  @Test
  void blankCustomerIsRejected() {
    assertThatThrownBy(() -> tools.listOpenTickets(" ")).hasMessage("Customer name is required");
    assertThatThrownBy(() -> tools.listOpenTickets(null)).hasMessage("Customer name is required");
  }

  // --- listTickets ---

  @Test
  void blankStatusMeansNoFilter() {
    assertThat(ids(tools.listTickets("globex", " "))).containsExactly("T-1003", "T-1004", "T-1006");
  }

  @Test
  void filtersByStatusCaseInsensitively() {
    assertThat(ids(tools.listTickets("acme", " in_progress "))).containsExactly("T-1010");
    assertThat(ids(tools.listTickets("acme", "RESOLVED"))).containsExactly("T-1002");
    assertThat(ids(tools.listTickets("initech", "open"))).containsExactly("T-1007");
  }

  @Test
  void invalidStatusIsRejected() {
    assertThatThrownBy(() -> tools.listTickets("acme", "CLOSED"))
        .hasMessage("Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED");
  }

  @Test
  void invalidStatusIsCheckedBeforeCustomerLookup() {
    assertThatThrownBy(() -> tools.listTickets("umbrella", "CLOSED"))
        .hasMessage("Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED");
  }

  @Test
  void customerIsCheckedBeforeStatus() {
    assertThatThrownBy(() -> tools.listTickets(" ", "CLOSED"))
        .hasMessage("Customer name is required");
  }

  @Test
  void ticketsOfUnknownCustomerIsEmpty() {
    assertThat(tools.listTickets("umbrella", null)).isEmpty();
    assertThat(tools.listTickets("umbrella", "OPEN")).isEmpty();
  }

  // --- createTicket ---

  @Test
  void createsTicketWithNextGlobalId() {
    Ticket created = tools.createTicket(" ACME ", "  Keyboard broken  ", " high ");
    assertThat(created).isEqualTo(new Ticket("T-1011", "acme", "Keyboard broken", "OPEN", "HIGH"));
    assertThat(tools.getTicket("acme", "T-1011")).isEqualTo(created);
    assertThat(ids(tools.listOpenTickets("acme"))).endsWith("T-1011");

    Ticket second = tools.createTicket("initech", "Mouse broken", "low");
    assertThat(second.id()).isEqualTo("T-1012");
    assertThat(second.customer()).isEqualTo("initech");
    assertThatThrownBy(() -> tools.getTicket("acme", "T-1012"))
        .hasMessage("Ticket T-1012 not found");
  }

  @Test
  void createRequiresCustomer() {
    assertThatThrownBy(() -> tools.createTicket(" ", "x", "LOW"))
        .hasMessage("Customer name is required");
    assertThatThrownBy(() -> tools.createTicket(null, "x", "LOW"))
        .hasMessage("Customer name is required");
  }

  @Test
  void createRejectsUnknownCustomerBeforeOtherChecks() {
    assertThatThrownBy(() -> tools.createTicket(" Umbrella ", null, "bogus"))
        .hasMessage("Unknown customer Umbrella");
  }

  @Test
  void createRequiresSubject() {
    assertThatThrownBy(() -> tools.createTicket("acme", " ", "bogus"))
        .hasMessage("Subject is required");
    assertThatThrownBy(() -> tools.createTicket("acme", null, "LOW"))
        .hasMessage("Subject is required");
  }

  @Test
  void subjectLengthCountsCodePoints() {
    String emoji = "😀"; // one code point, two chars
    String ok = emoji.repeat(200);
    assertThat(tools.createTicket("acme", "  " + ok + "  ", "LOW").subject()).isEqualTo(ok);
    assertThatThrownBy(() -> tools.createTicket("acme", "a".repeat(201), "bogus"))
        .hasMessage("Subject must be at most 200 characters");
    assertThatThrownBy(() -> tools.createTicket("acme", emoji.repeat(201), "LOW"))
        .hasMessage("Subject must be at most 200 characters");
  }

  @Test
  void createRejectsInvalidPriority() {
    assertThatThrownBy(() -> tools.createTicket("acme", "x", "URGENT"))
        .hasMessage("Invalid priority, expected one of LOW, MEDIUM, HIGH");
    assertThatThrownBy(() -> tools.createTicket("acme", "x", null))
        .hasMessage("Invalid priority, expected one of LOW, MEDIUM, HIGH");
    assertThat(tools.listTickets("acme", null)).hasSize(4);
  }

  @Test
  void concurrentCreatesGetUniqueIds() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Ticket>> jobs = new ArrayList<>();
      for (int i = 0; i < 50; i++) {
        String customer = i % 2 == 0 ? "acme" : "globex";
        jobs.add(() -> tools.createTicket(customer, "Concurrent", "MEDIUM"));
      }
      Set<String> created = new HashSet<>();
      for (Future<Ticket> f : pool.invokeAll(jobs)) {
        created.add(f.get().id());
      }
      assertThat(created).hasSize(50).contains("T-1011", "T-1060");
    } finally {
      pool.shutdown();
    }
  }
}
