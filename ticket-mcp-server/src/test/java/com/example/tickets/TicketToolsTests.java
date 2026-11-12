package com.example.tickets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TicketToolsTests {

	private final TicketTools tools = new TicketTools();

	@Test
	void findsTicketCaseInsensitively() {
		assertThat(tools.getTicket(" t-1001 ").subject()).contains("VPN");
	}

	@Test
	void listsOnlyOpenTicketsOfCustomer() {
		assertThat(tools.listOpenTickets("acme")).extracting(Ticket::id).containsExactly("T-1001");
	}

}
