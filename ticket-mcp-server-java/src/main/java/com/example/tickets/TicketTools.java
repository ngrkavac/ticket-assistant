package com.example.tickets;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/** Tools exposed over MCP. Backed by in-memory demo data. */
@Service
public class TicketTools {

  private final Map<String, Ticket> tickets =
      Map.of(
          "T-1001", new Ticket("T-1001", "acme", "VPN drops every 10 minutes", "OPEN", "HIGH"),
          "T-1002", new Ticket("T-1002", "acme", "Cannot reset password", "RESOLVED", "LOW"),
          "T-1003", new Ticket("T-1003", "globex", "Laptop not charging", "IN_PROGRESS", "MEDIUM"),
          "T-1004", new Ticket("T-1004", "globex", "Printer offline on floor 3", "OPEN", "LOW"));

  @Tool(
      description =
          "Get a support ticket by its id, e.g. T-1001. Fails with a message if it does not exist.")
  public Ticket getTicket(@ToolParam(description = "Ticket id such as T-1001") String id) {
    if (id == null || !id.trim().matches("(?i)T-\\d{1,8}")) {
      throw new IllegalArgumentException("Invalid ticket id, expected format T-1234");
    }
    Ticket t = tickets.get(id.trim().toUpperCase());
    if (t == null) {
      throw new IllegalArgumentException("Ticket " + id.trim() + " not found");
    }
    return t;
  }

  @Tool(description = "List all open (status OPEN or IN_PROGRESS) tickets of a customer.")
  public List<Ticket> listOpenTickets(
      @ToolParam(description = "Customer name, e.g. acme") String customer) {
    if (customer == null || customer.isBlank()) {
      throw new IllegalArgumentException("Customer name is required");
    }
    return tickets.values().stream()
        .filter(t -> t.customer().equalsIgnoreCase(customer.trim()))
        .filter(t -> !t.status().equals("RESOLVED"))
        .sorted(Comparator.comparing(Ticket::id))
        .toList();
  }
}
