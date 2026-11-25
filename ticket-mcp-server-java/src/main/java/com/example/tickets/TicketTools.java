package com.example.tickets;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Tools exposed over MCP, backed by the ticket database ({@link TicketRepository}). Customer names
 * are trimmed and lower-cased; a customer only ever sees its own tickets.
 */
@Service
public class TicketTools {

  private static final Logger log = LoggerFactory.getLogger(TicketTools.class);

  private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED");
  private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");
  private static final int MAX_SUBJECT_LENGTH = 200;
  private static final Pattern TICKET_ID = Pattern.compile("(?i)T-\\d{1,8}");

  private final TicketRepository repository;

  public TicketTools(TicketRepository repository) {
    this.repository = repository;
  }

  private static String requireCustomer(String customer) {
    if (customer == null || customer.isBlank()) {
      throw new IllegalArgumentException("Customer name is required");
    }
    return customer.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Runs a database call. A database failure becomes a generic error, so SQL and connection details
   * are logged here but never sent to the MCP client (which shows tool errors to the model).
   */
  private static <T> T db(Supplier<T> call) {
    try {
      return call.get();
    } catch (DataAccessException e) {
      log.error("Ticket database call failed", e);
      throw new IllegalStateException("Ticket database is unavailable, please try again later");
    }
  }

  @Tool(
      description =
          "Get a support ticket of a customer by its id, e.g. T-1001. Fails with a message if the"
              + " customer has no such ticket.")
  public Ticket getTicket(
      @ToolParam(description = "Customer name, e.g. acme") String customer,
      @ToolParam(description = "Ticket id such as T-1001") String id) {
    String key = requireCustomer(customer);
    if (id == null || !TICKET_ID.matcher(id.trim()).matches()) {
      throw new IllegalArgumentException("Invalid ticket id, expected format T-1234");
    }
    String normalizedId = id.trim().toUpperCase(Locale.ROOT);
    long number = Long.parseLong(normalizedId.substring(2));
    // Ids are stored without leading zeros: T-01001 is not T-1001 (as with the in-memory map).
    if (!normalizedId.equals(Ticket.formatId(number))) {
      throw new IllegalArgumentException("Ticket " + id.trim() + " not found");
    }
    return db(() -> repository.find(key, number))
        .orElseThrow(() -> new IllegalArgumentException("Ticket " + id.trim() + " not found"));
  }

  @Tool(description = "List all open (status OPEN or IN_PROGRESS) tickets of a customer.")
  public List<Ticket> listOpenTickets(
      @ToolParam(description = "Customer name, e.g. acme") String customer) {
    String key = requireCustomer(customer);
    return db(() -> repository.findOpenByCustomer(key));
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
    return db(() -> repository.findByCustomer(key, wanted));
  }

  @Tool(description = "List the names of all known customers.")
  public List<String> listCustomers() {
    return db(repository::customers);
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
    if (!db(() -> repository.customerExists(key))) {
      throw new IllegalArgumentException("Unknown customer " + customer.trim());
    }
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("Subject is required");
    }
    String trimmedSubject = subject.trim();
    if (trimmedSubject.codePointCount(0, trimmedSubject.length()) > MAX_SUBJECT_LENGTH) {
      throw new IllegalArgumentException("Subject must be at most 200 characters");
    }
    String normalizedPriority = priority == null ? null : priority.trim().toUpperCase(Locale.ROOT);
    if (normalizedPriority == null || !PRIORITIES.contains(normalizedPriority)) {
      throw new IllegalArgumentException("Invalid priority, expected one of LOW, MEDIUM, HIGH");
    }
    return db(() -> repository.create(key, trimmedSubject, normalizedPriority));
  }
}
