package com.example.tickets;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/** Tools exposed over MCP. Backed by in-memory demo data. */
@Service
public class TicketTools {

	private final Map<String, Ticket> tickets = Map.of(
			"T-1001", new Ticket("T-1001", "acme", "VPN drops every 10 minutes", "OPEN", "HIGH"),
			"T-1002", new Ticket("T-1002", "acme", "Cannot reset password", "RESOLVED", "LOW"),
			"T-1003", new Ticket("T-1003", "globex", "Laptop not charging", "IN_PROGRESS", "MEDIUM"),
			"T-1004", new Ticket("T-1004", "globex", "Printer offline on floor 3", "OPEN", "LOW"));

	@Tool(description = "Get a support ticket by its id, e.g. T-1001. Returns null if it does not exist.")
	public Ticket getTicket(@ToolParam(description = "Ticket id such as T-1001") String id) {
		return tickets.get(id.trim().toUpperCase());
	}

	@Tool(description = "List all open (status OPEN or IN_PROGRESS) tickets of a customer.")
	public List<Ticket> listOpenTickets(@ToolParam(description = "Customer name, e.g. acme") String customer) {
		return tickets.values()
			.stream()
			.filter(t -> t.customer().equalsIgnoreCase(customer.trim()))
			.filter(t -> !t.status().equals("RESOLVED"))
			.sorted(java.util.Comparator.comparing(Ticket::id))
			.toList();
	}

}
