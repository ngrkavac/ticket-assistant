package com.example.tickets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/** Tools exposed over MCP. Backed by in-memory demo data, keyed by customer. */
@Service
public class TicketTools {

  private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED");
  private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");
  private static final int MAX_SUBJECT_LENGTH = 200;

  /** Lowercase customer name -> (ticket id -> ticket), both sorted. Guarded by {@link #lock}. */
  private final Map<String, TreeMap<String, Ticket>> byCustomer = new TreeMap<>();

  private final ReadWriteLock lock = new ReentrantReadWriteLock();

  public TicketTools() {
    add(new Ticket("T-1001", "acme", "VPN drops every 10 minutes", "OPEN", "HIGH"));
    add(new Ticket("T-1002", "acme", "Cannot reset password", "RESOLVED", "LOW"));
    add(new Ticket("T-1003", "globex", "Laptop not charging", "IN_PROGRESS", "MEDIUM"));
    add(new Ticket("T-1004", "globex", "Printer offline on floor 3", "OPEN", "LOW"));
    add(new Ticket("T-1005", "acme", "Outlook keeps asking for credentials", "OPEN", "MEDIUM"));
    add(new Ticket("T-1006", "globex", "Monitor flickering", "RESOLVED", "LOW"));
    add(new Ticket("T-1007", "initech", "Shared drive not mapping after login", "OPEN", "HIGH"));
    add(
        new Ticket(
            "T-1008", "initech", "Request new laptop for new hire", "IN_PROGRESS", "MEDIUM"));
    add(new Ticket("T-1009", "initech", "Wi-Fi slow in meeting room B", "RESOLVED", "LOW"));
    add(new Ticket("T-1010", "acme", "MFA codes not arriving by SMS", "IN_PROGRESS", "HIGH"));
  }

  private void add(Ticket t) {
    byCustomer.computeIfAbsent(t.customer(), c -> new TreeMap<>()).put(t.id(), t);
  }

  private static String requireCustomer(String customer) {
    if (customer == null || customer.isBlank()) {
      throw new IllegalArgumentException("Customer name is required");
    }
    return customer.trim().toLowerCase(Locale.ROOT);
  }

  @Tool(
      description =
          "Get a support ticket of a customer by its id, e.g. T-1001. Fails with a message if the"
              + " customer has no such ticket.")
  public Ticket getTicket(
      @ToolParam(description = "Customer name, e.g. acme") String customer,
      @ToolParam(description = "Ticket id such as T-1001") String id) {
    String key = requireCustomer(customer);
    if (id == null || !id.trim().matches("(?i)T-\\d{1,8}")) {
      throw new IllegalArgumentException("Invalid ticket id, expected format T-1234");
    }
    lock.readLock().lock();
    try {
      Map<String, Ticket> tickets = byCustomer.get(key);
      Ticket t = tickets == null ? null : tickets.get(id.trim().toUpperCase(Locale.ROOT));
      if (t == null) {
        throw new IllegalArgumentException("Ticket " + id.trim() + " not found");
      }
      return t;
    } finally {
      lock.readLock().unlock();
    }
  }

  @Tool(description = "List all open (status OPEN or IN_PROGRESS) tickets of a customer.")
  public List<Ticket> listOpenTickets(
      @ToolParam(description = "Customer name, e.g. acme") String customer) {
    String key = requireCustomer(customer);
    return ticketsOf(key).stream().filter(t -> !t.status().equals("RESOLVED")).toList();
  }

  @Tool(description = "List all tickets of a customer, optionally filtered by status.")
  public List<Ticket> listTickets(
      @ToolParam(description = "Customer name, e.g. acme") String customer,
      @ToolParam(
              description = "Optional status filter: OPEN, IN_PROGRESS or RESOLVED",
              required = false)
          String status) {
    String key = requireCustomer(customer);
    String filter = null;
    if (status != null && !status.isBlank()) {
      filter = status.trim().toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(filter)) {
        throw new IllegalArgumentException(
            "Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED");
      }
    }
    String wanted = filter;
    return ticketsOf(key).stream()
        .filter(t -> wanted == null || t.status().equals(wanted))
        .toList();
  }

  @Tool(description = "List the names of all known customers.")
  public List<String> listCustomers() {
    lock.readLock().lock();
    try {
      return List.copyOf(byCustomer.keySet());
    } finally {
      lock.readLock().unlock();
    }
  }

  @Tool(
      description =
          "Create a new OPEN support ticket for a known customer and return it. The ticket id is"
              + " assigned by the server.")
  public Ticket createTicket(
      @ToolParam(description = "Customer name, e.g. acme") String customer,
      @ToolParam(description = "Short description of the problem") String subject,
      @ToolParam(description = "Priority: LOW, MEDIUM or HIGH") String priority) {
    String key = requireCustomer(customer);
    lock.writeLock().lock();
    try {
      TreeMap<String, Ticket> tickets = byCustomer.get(key);
      if (tickets == null) {
        throw new IllegalArgumentException("Unknown customer " + customer.trim());
      }
      if (subject == null || subject.isBlank()) {
        throw new IllegalArgumentException("Subject is required");
      }
      String trimmedSubject = subject.trim();
      if (trimmedSubject.codePointCount(0, trimmedSubject.length()) > MAX_SUBJECT_LENGTH) {
        throw new IllegalArgumentException("Subject must be at most 200 characters");
      }
      String normalizedPriority =
          priority == null ? null : priority.trim().toUpperCase(Locale.ROOT);
      if (normalizedPriority == null || !PRIORITIES.contains(normalizedPriority)) {
        throw new IllegalArgumentException("Invalid priority, expected one of LOW, MEDIUM, HIGH");
      }
      long maxId =
          byCustomer.values().stream()
              .flatMap(m -> m.keySet().stream())
              .mapToLong(id -> Long.parseLong(id.substring(2)))
              .max()
              .orElse(1000);
      Ticket created =
          new Ticket("T-" + (maxId + 1), key, trimmedSubject, "OPEN", normalizedPriority);
      tickets.put(created.id(), created);
      return created;
    } finally {
      lock.writeLock().unlock();
    }
  }

  private List<Ticket> ticketsOf(String key) {
    lock.readLock().lock();
    try {
      Map<String, Ticket> tickets = byCustomer.get(key);
      return tickets == null ? List.of() : new ArrayList<>(tickets.values());
    } finally {
      lock.readLock().unlock();
    }
  }
}
